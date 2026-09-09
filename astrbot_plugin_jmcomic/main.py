# -*- coding: utf-8 -*-
"""jm下载姬 - 禁漫本子下载插件

自包含依赖：jmcomic 源码内嵌在 vendor/，装上即用，卸载即清。
命令：
  搜本 <关键词> [页码]     搜索本子
  下本 <ID> [格式]         下载本子 (longimg/zip/pdf)
  排行 [周|月|日]          查看榜单
  我的任务                 查看任务
  取消 <任务号>            取消任务
"""

import asyncio
import importlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import zipfile
from pathlib import Path

_PLUGIN_DIR = Path(__file__).resolve().parent
_DEPS_DIR = _PLUGIN_DIR / ".deps"
_VENDOR = _PLUGIN_DIR / "vendor"
# 插件私有依赖优先于系统包。curl-cffi 会安装在 .deps，绝不污染系统 pip 环境。
for _path in (str(_VENDOR), str(_DEPS_DIR)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from astrbot.api.event import filter, AstrMessageEvent
from astrbot.api.star import Context, Star
from astrbot.api import logger
from astrbot.core import AstrBotConfig
from astrbot.core.message.components import Plain, Image, File
from astrbot.core.message.message_event_result import MessageChain

def _ensure_curl_cffi() -> bool:
    """确保插件私有 curl_cffi 存在，不存在则自动安装。"""
    if (_DEPS_DIR / "curl_cffi").is_dir():
        return True
    try:
        _DEPS_DIR.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(
            [sys.executable, "-m", "pip", "install", "curl-cffi", "-q",
             "--target", str(_DEPS_DIR),
             "-i", "https://pypi.tuna.tsinghua.edu.cn/simple/"],
            timeout=600,
            check=False,
        )
        # 关键：pip 装完后强制刷新 import 缓存，否则 Python 仍按"空目录"缓存找不到新包
        importlib.invalidate_caches()
        return result.returncode == 0 and (_DEPS_DIR / "curl_cffi").is_dir()
    except Exception:
        return False

# 模块加载时先保证依赖就绪，再导入 jmcomic，避免 import 崩导致自动安装永远无法触发
_ensure_curl_cffi()

try:
    import jmcomic
    JM_READY = True
except Exception as _jm_import_err:
    JM_READY = False
    JM_IMPORT_ERR = _jm_import_err
    logger.warning(f"[jm下载姬] jmcomic 导入失败，插件暂不可用: {_jm_import_err}")

_IMG_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"}
_PYPI_JSON_URLS = [
    "https://pypi.org/pypi/jmcomic/json",
    "https://pypi.tuna.tsinghua.edu.cn/pypi/jmcomic/json",
]
_UPDATE_CHECK_INTERVAL = 3600  # 运行中每小时检查一次上游版本
_FMT_ALIAS = {
    "longimg": "longimg", "长图": "longimg", "图": "longimg",
    "zip": "zip", "压缩包": "zip", "包": "zip",
    "pdf": "pdf", "文档": "pdf",
}

STATUS_TEXT = {
    "queued": "排队中",
    "downloading": "下载中",
    "converting": "转换中",
    "sending": "发送中",
    "done": "已完成",
    "failed": "失败",
    "cancelled": "已取消",
}


class JmComicPlugin(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context, config)
        self.config = config or {}
        self._tasks: dict[int, dict] = {}
        self._bg_tasks: set = set()
        self._next_tid = 1
        self._update_lock = threading.Lock()
        self._update_notice: str | None = None
        self._update_timer_task = None
        self._start_auto_update()
        self._tid_lock = threading.Lock()
        self._startup_check_dep()

    # ---------------- 依赖自管理 ----------------

    def _startup_check_dep(self) -> None:
        if self._curl_ok():
            logger.info("[jm下载姬] 插件私有 curl-cffi 已就绪")
            return
        logger.info("[jm下载姬] 未检测到私有 curl-cffi，后台自动安装中")
        threading.Thread(target=self._pip_install_curl, daemon=True).start()

    def _start_auto_update(self) -> None:
        """启动自动更新：立即后台查一次，并挂上每小时定时检查。"""
        try:
            if not str(self._cfg("auto_update", "on")).strip().lower() in ("on", "true", "1", "开"):
                logger.info("[jm下载姬] 自动更新已关闭")
                return
        except Exception:
            pass
        threading.Thread(target=self._auto_update_check, daemon=True).start()
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        self._update_timer_task = loop.create_task(self._update_timer_loop())
        self._bg_tasks.add(self._update_timer_task)
        self._update_timer_task.add_done_callback(self._bg_tasks.discard)

    @staticmethod
    def _pip_install_curl() -> None:
        try:
            _DEPS_DIR.mkdir(parents=True, exist_ok=True)
            result = subprocess.run(
                [sys.executable, "-m", "pip", "install", "curl-cffi", "-q",
                 "--target", str(_DEPS_DIR),
                 "-i", "https://pypi.tuna.tsinghua.edu.cn/simple/"],
                timeout=600,
                check=False,
            )
            if result.returncode == 0:
                logger.info("[jm下载姬] 私有 curl-cffi 安装完成")
            else:
                logger.error(f"[jm下载姬] curl-cffi 安装失败，退出码 {result.returncode}")
        except Exception as e:
            logger.error(f"[jm下载姬] curl-cffi 自动安装失败: {e}")

    @staticmethod
    def _curl_ok() -> bool:
        return JM_READY

    # ---------------- 上游自动更新 ----------------

    @staticmethod
    def _get_latest_pypi_version(timeout: int = 5):
        """多源查询 jmcomic 最新版本：官方源优先，镜像兜底。全部失败返回 None。"""
        import urllib.request
        for url in _PYPI_JSON_URLS:
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "jm-downloader/1.0"})
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                version = data.get("info", {}).get("version")
                if version:
                    return version
            except Exception as e:
                logger.debug(f"[jm下载姬] 上游版本源失败 {url}: {e}")
        return None

    @staticmethod
    def _vendor_version() -> str | None:
        """读取内嵌 jmcomic 的版本号。"""
        try:
            import jmcomic
            return getattr(jmcomic, "__version__", None)
        except Exception:
            return None

    def _auto_update_check(self) -> None:
        """后台线程：检查上游版本，有新版则尝试更新。"""
        try:
            latest = self._get_latest_pypi_version()
            if not latest:
                return
            current = self._vendor_version()
            if not current:
                self._update_notice = "内嵌库版本异常，建议重装插件。"
                return
            if self._is_newer(latest, current):
                ok, msg = self._apply_update(latest)
                if ok:
                    self._update_notice = f"上游更新已生效：{current} → {latest}"
                    logger.info(f"[jm下载姬] 自动更新成功: {current} → {latest}")
                else:
                    self._update_notice = f"发现上游新版 {latest}，但更新失败，继续用旧版 {current}。"
                    logger.warning(f"[jm下载姬] 自动更新失败: {msg}")
        except Exception as e:
            logger.debug(f"[jm下载姬] 自动更新检查异常: {e}")

    @staticmethod
    def _is_newer(latest: str, current: str) -> bool:
        def to_tuple(v: str):
            nums = []
            for part in v.replace("v", "").split("."):
                m = re.search(r"\d+", part)
                nums.append(int(m.group()) if m else 0)
            return tuple(nums)
        try:
            return to_tuple(latest) > to_tuple(current)
        except Exception:
            return latest != current

    def _apply_update(self, latest: str) -> tuple[bool, str]:
        """下载新版到临时目录，校验通过后覆盖 vendor。失败不动 vendor，旧版继续可用。"""
        if not self._update_lock.acquire(blocking=False):
            return False, "已有更新任务在进行"
        tmp_dir = _PLUGIN_DIR / ".update_tmp"
        try:
            if tmp_dir.exists():
                shutil.rmtree(tmp_dir, ignore_errors=True)
            tmp_dir.mkdir(parents=True, exist_ok=True)
            result = subprocess.run(
                [sys.executable, "-m", "pip", "install", "jmcomic", "-q",
                 "--target", str(tmp_dir), "--no-deps",
                 "-i", "https://pypi.tuna.tsinghua.edu.cn/simple/"],
                timeout=300,
                check=False,
            )
            if result.returncode != 0:
                return False, f"pip下载失败(码{result.returncode})"
            importlib.invalidate_caches()
            if not (tmp_dir / "jmcomic").is_dir():
                return False, "下载目录缺少jmcomic包"
            # 校验：临时版本号一致
            tmp_ver = self._probe_version(tmp_dir)
            if tmp_ver != latest:
                return False, f"版本校验不符(期望{latest},实际{tmp_ver or '未知'})"
            # 校验：能真正 import 且关键函数可用
            if not self._probe_usable(tmp_dir):
                return False, "新版导入自检失败"
            # 覆盖 vendor（旧版被替换，只留新版）
            shutil.rmtree(str(_VENDOR / "jmcomic"), ignore_errors=True)
            shutil.move(str(tmp_dir / "jmcomic"), str(_VENDOR / "jmcomic"))
            # 若新版带 common，也同步替换
            if (tmp_dir / "common").is_dir():
                shutil.rmtree(str(_VENDOR / "common"), ignore_errors=True)
                shutil.move(str(tmp_dir / "common"), str(_VENDOR / "common"))
            importlib.invalidate_caches()
            return True, f"更新至 {latest}"
        except Exception as e:
            return False, str(e)
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)
            self._update_lock.release()

    @staticmethod
    def _probe_version(tmp_dir) -> str | None:
        """子进程读临时目录 jmcomic 版本号（隔离环境，vendor 提供 common 兜底）。"""
        script = (
            "import sys; sys.path.insert(0, %r); sys.path.insert(0, %r); sys.path.insert(0, %r); "
            "import jmcomic; print(jmcomic.__version__)"
            % (str(tmp_dir), str(_VENDOR), str(_DEPS_DIR))
        )
        try:
            r = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=60)
            out = r.stdout.strip()
            return out.splitlines()[-1] if out else None
        except Exception:
            return None

    @staticmethod
    def _probe_usable(tmp_dir) -> bool:
        """子进程验证新版 jmcomic 能导入关键组件。"""
        script = (
            "import sys; sys.path.insert(0, %r); sys.path.insert(0, %r); sys.path.insert(0, %r); "
            "import jmcomic; from jmcomic import JmOption; print('OK')"
            % (str(tmp_dir), str(_VENDOR), str(_DEPS_DIR))
        )
        try:
            r = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=60)
            return r.returncode == 0 and "OK" in r.stdout
        except Exception:
            return False

    async def _update_timer_loop(self) -> None:
        """运行中定时检查上游更新。"""
        while True:
            try:
                await asyncio.sleep(_UPDATE_CHECK_INTERVAL)
                await asyncio.to_thread(self._auto_update_check)
            except asyncio.CancelledError:
                break
            except Exception:
                pass

    # ---------------- 配置读取（面板改动全部生效） ----------------

    def _cfg(self, key: str, default=None):
        try:
            return self.config.get(key, default)
        except Exception:
            try:
                return self.config[key]
            except Exception:
                return default

    def _get_tmp_dir(self) -> Path:
        d = str(self._cfg("tmp_dir", "/AstrBot/data/MengliRoom/漫画/tmp")).strip()
        p = Path(d)
        p.mkdir(parents=True, exist_ok=True)
        return p

    # ---------------- 权限 ----------------

    def _check_access(self, event: AstrMessageEvent) -> bool:
        mode = str(self._cfg("access_mode", "admin")).strip().lower()
        uid = str(event.get_sender_id())
        if mode == "all":
            return True
        if mode == "whitelist":
            wl = [str(x) for x in (self._cfg("whitelist", []) or [])]
            return uid in wl
        admins = [str(x) for x in (self._cfg("admin_ids", []) or [])]
        return uid in admins

    @staticmethod
    def _deny_text() -> str:
        return "你没有使用权限哦，找管理员开权限吧。"

    # ---------------- 辅助 ----------------

    @staticmethod
    def _strip_cmd(text: str, cmd: str) -> str:
        t = text.strip()
        for prefix in (cmd, "/" + cmd):
            if t.startswith(prefix):
                return t[len(prefix):].strip()
        return t

    def _send(self, session: str, chain: list) -> None:
        try:
            task = asyncio.create_task(self._send_async(session, chain))
            self._bg_tasks.add(task)
            task.add_done_callback(self._bg_tasks.discard)
        except RuntimeError:
            loop = asyncio.new_event_loop()
            loop.run_until_complete(self._send_async(session, chain))
            loop.close()

    async def _send_async(self, session: str, chain: list) -> None:
        try:
            await self.context.send_message(session, MessageChain(chain=chain))
        except Exception as e:
            logger.error(f"[jm下载姬] 发送失败: {e}")

    async def _notify(self, session: str, text: str) -> None:
        await self._send_async(session, [Plain(text)])

    # ---------------- 任务系统 ----------------

    def _new_tid(self) -> int:
        with self._tid_lock:
            tid = self._next_tid
            self._next_tid += 1
            return tid

    def _user_active_cnt(self, uid: str) -> int:
        return sum(
            1 for t in self._tasks.values()
            if t["user_id"] == uid and t["status"] in ("queued", "downloading", "converting", "sending")
        )

    def _global_active_cnt(self) -> int:
        return sum(
            1 for t in self._tasks.values()
            if t["status"] in ("downloading", "converting", "sending")
        )

    # ---------------- 命令：搜本 ----------------

    @filter.command("搜本")
    async def cmd_search(self, event: AstrMessageEvent):
        if not self._check_access(event):
            yield event.plain_result(self._deny_text())
            return
        if self._update_notice:
            notice = self._update_notice
            self._update_notice = None
            yield event.plain_result("🔔 " + notice)
        arg = self._strip_cmd(event.get_message_str(), "搜本")
        parts = arg.split()
        if not parts:
            yield event.plain_result("用法：搜本 <关键词> [页码]。比如：搜本 无望菜志")
            return
        keyword = parts[0]
        page = 1
        if len(parts) > 1 and parts[1].isdigit():
            page = max(1, int(parts[1]))
        if not self._curl_ok():
            yield event.plain_result("加密扩展还在装，稍等几秒再试哦。")
            return
        try:
            result = await asyncio.to_thread(
                self._do_search, keyword, page
            )
        except Exception as e:
            logger.error(f"[jm下载姬] 搜索失败: {e}\n{traceback.format_exc()}")
            yield event.plain_result(f"搜索失败了：{e}")
            return
        if not result or not result[0]:
            yield event.plain_result("啥也没搜到，换个关键词试试。")
            return
        lines, total_page = result
        head = f"搜「{keyword}」第{page}/{total_page}页，共{len(lines)}条：\n"
        yield event.plain_result(head + "\n".join(lines) + "\n回复「下本 ID」就能下载")

    @staticmethod
    def _do_search(keyword: str, page: int):
        client = jmcomic.JmOption.default().new_jm_client()
        sp = client.search_site(keyword, page=page)
        lines = []
        for aid, title in sp.iter_id_title():
            lines.append(f"{aid}｜{title[:45]}")
        return lines, max(1, sp.page_count)

    # ---------------- 命令：排行 ----------------

    @filter.command("排行")
    async def cmd_rank(self, event: AstrMessageEvent):
        if not self._check_access(event):
            yield event.plain_result(self._deny_text())
            return
        if self._update_notice:
            notice = self._update_notice
            self._update_notice = None
            yield event.plain_result("🔔 " + notice)
        arg = self._strip_cmd(event.get_message_str(), "排行")
        kind = "周"
        if arg:
            kind = "月" if "月" in arg else ("日" if "日" in arg else "周")
        if not self._curl_ok():
            yield event.plain_result("加密扩展还在装，稍等几秒再试哦。")
            return
        try:
            lines = await asyncio.to_thread(self._do_rank, kind)
        except Exception as e:
            logger.error(f"[jm下载姬] 排行失败: {e}")
            yield event.plain_result(f"榜单拉取失败：{e}")
            return
        yield event.plain_result(f"{kind}排行 Top10：\n" + "\n".join(lines) + "\n回复「下本 ID」就能下载")

    @staticmethod
    def _do_rank(kind: str):
        client = jmcomic.JmOption.default().new_jm_client()
        if kind == "月":
            sp = client.month_ranking(1)
        elif kind == "日":
            sp = client.day_ranking(1)
        else:
            sp = client.week_ranking(1)
        lines = []
        for i, (aid, title) in enumerate(sp.iter_id_title(), 1):
            lines.append(f"{i}. {aid}｜{title[:40]}")
            if i >= 10:
                break
        return lines

    # ---------------- 命令：下本 ----------------

    @filter.command("下本")
    async def cmd_download(self, event: AstrMessageEvent):
        if not self._check_access(event):
            yield event.plain_result(self._deny_text())
            return
        if self._update_notice:
            notice = self._update_notice
            self._update_notice = None
            yield event.plain_result("🔔 " + notice)
        arg = self._strip_cmd(event.get_message_str(), "下本")
        parts = arg.split()
        if not parts or not parts[0].isdigit():
            yield event.plain_result("用法：下本 <ID> [格式]。比如：下本 243484 长图")
            return
        album_id = parts[0]
        fmt = _FMT_ALIAS.get(parts[1], None) if len(parts) > 1 else None
        if fmt is None:
            fmt = _FMT_ALIAS.get(str(self._cfg("default_format", "longimg")).strip(), "longimg")
        if not self._curl_ok():
            yield event.plain_result("加密扩展还在装，稍等几秒再试哦。")
            return
        uid = str(event.get_sender_id())
        session = event.unified_msg_origin
        limit = max(1, int(self._cfg("per_user_limit", 1)))
        if self._user_active_cnt(uid) >= limit:
            yield event.plain_result(f"你手头任务满了（同时最多{limit}个），等前面的完成再来吧。")
            return
        tid = self._new_tid()
        task_dir = self._get_tmp_dir() / str(tid)
        try:
            task_dir.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            yield event.plain_result(f"中转目录创建失败：{e}")
            return
        self._tasks[tid] = {
            "tid": tid, "user_id": uid, "session": session,
            "album_id": album_id, "fmt": fmt, "status": "queued",
            "title": "", "dir": str(task_dir), "error": "",
            "started": time.time(),
        }
        task = asyncio.create_task(self._run_task(tid))
        self._bg_tasks.add(task)
        task.add_done_callback(self._bg_tasks.discard)
        yield event.plain_result(f"任务{tid}已开始，ID：{album_id}，格式：{fmt}。下好发你。")

    # ---------------- 命令：我的任务 / 取消 ----------------

    @filter.command("我的任务")
    async def cmd_my_tasks(self, event: AstrMessageEvent):
        if not self._check_access(event):
            yield event.plain_result(self._deny_text())
            return
        if self._update_notice:
            notice = self._update_notice
            self._update_notice = None
            yield event.plain_result("🔔 " + notice)
        uid = str(event.get_sender_id())
        mine = [t for t in self._tasks.values() if t["user_id"] == uid]
        if not mine:
            yield event.plain_result("你还没有任务哦。")
            return
        lines = [f"任务{t['tid']}｜{STATUS_TEXT.get(t['status'], t['status'])}｜{t['album_id']}" for t in mine]
        yield event.plain_result("\n".join(lines))

    @filter.command("取消")
    async def cmd_cancel(self, event: AstrMessageEvent):
        if not self._check_access(event):
            yield event.plain_result(self._deny_text())
            return
        if self._update_notice:
            notice = self._update_notice
            self._update_notice = None
            yield event.plain_result("🔔 " + notice)
        arg = self._strip_cmd(event.get_message_str(), "取消")
        if not arg.isdigit():
            yield event.plain_result("用法：取消 <任务号>")
            return
        tid = int(arg)
        t = self._tasks.get(tid)
        if not t:
            yield event.plain_result("没有这个任务号。")
            return
        if str(event.get_sender_id()) != t["user_id"]:
            yield event.plain_result("只能取消自己的任务哦。")
            return
        if t["status"] in ("done", "failed", "cancelled"):
            yield event.plain_result(f"任务{tid}状态是{STATUS_TEXT.get(t['status'])}，没法取消了。")
            return
        if t["status"] == "queued":
            t["status"] = "cancelled"
            self._cleanup_task(t)
            yield event.plain_result(f"任务{tid}已取消。")
        else:
            t["status"] = "cancelled"
            yield event.plain_result(f"任务{tid}正在跑，已标记取消，完成后不再发送。")

    # ---------------- 任务执行核心 ----------------

    async def _run_task(self, tid: int) -> None:
        t = self._tasks.get(tid)
        if not t:
            return
        try:
            # 每次开跑都读一次配置，面板修改并发数无需重载插件即可生效。
            while self._global_active_cnt() >= max(1, int(self._cfg("max_concurrent", 3))):
                if t["status"] == "cancelled":
                    return
                await asyncio.sleep(0.2)
            if t["status"] == "cancelled":
                return
            t["status"] = "downloading"
            try:
                ok = await asyncio.to_thread(self._download_album, t)
            except Exception as e:
                t["status"] = "failed"
                t["error"] = str(e)
                logger.error(f"[jm下载姬] 任务{tid}下载异常: {e}\n{traceback.format_exc()}")
                await self._notify(t["session"], f"任务{tid}下载出错了：{e}")
                return
            if not ok:
                t["status"] = "failed"
                await self._notify(t["session"], f"任务{tid}下载失败：{t['error']}")
                return
            if t["status"] == "cancelled":
                return
            t["status"] = "converting"
            try:
                deliver = await asyncio.to_thread(self._convert, t)
            except Exception as e:
                t["status"] = "failed"
                t["error"] = str(e)
                logger.error(f"[jm下载姬] 任务{tid}转换异常: {e}\n{traceback.format_exc()}")
                await self._notify(t["session"], f"任务{tid}转换出错了：{e}")
                return
            if t["status"] == "cancelled":
                return
            t["status"] = "sending"
            try:
                await self._deliver(t, deliver)
                if t["status"] == "cancelled":
                    await self._notify(t["session"], f"任务{tid}已取消，文件就不发啦。")
                else:
                    t["status"] = "done"
            except Exception as e:
                t["status"] = "failed"
                t["error"] = str(e)
                logger.error(f"[jm下载姬] 任务{tid}发送异常: {e}\n{traceback.format_exc()}")
                await self._notify(t["session"], f"任务{tid}发送出错了：{e}")
        finally:
            self._cleanup_task(t)

    def _download_album(self, t: dict) -> bool:
        album_id = t["album_id"]
        try:
            option = jmcomic.create_option_by_str(f"""
dir_rule:
  base_dir: {t['dir']}
  rule: Bd_Aid
download:
  image:
    suffix: .jpg
""")
            try:
                client = option.new_jm_client()
                detail = client.get_album_detail(album_id)
                t["title"] = getattr(detail, "title", album_id)
            except Exception as e:
                logger.warning(f"[jm下载姬] 获取标题失败，用ID代替: {e}")
                t["title"] = album_id
            jmcomic.download_album(album_id, option=option)
            return True
        except Exception as e:
            t["error"] = str(e)
            return False

    def _convert(self, t: dict) -> dict:
        imgs = self._collect_imgs(t)
        if not imgs:
            raise RuntimeError("下载的图片是空的")
        fmt = t["fmt"]
        if fmt == "zip":
            return self._make_zip(t, imgs)
        if fmt == "pdf":
            return self._make_pdf(t, imgs)
        return self._make_longimg(t, imgs)

    @staticmethod
    def _collect_imgs(t: dict) -> list[str]:
        root = Path(t["dir"])
        found = []
        for p in root.rglob("*"):
            if p.is_file() and p.suffix.lower() in _IMG_EXTS:
                found.append(str(p))
        found.sort()
        return found

    def _safe_name(self, t: dict) -> str:
        # 只保留 ASCII 字母数字与 -_，其余全部替换为 _，避免 file:// 路径特殊字符导致发送失败
        title = (t.get("title") or t["album_id"]).strip()
        title = re.sub(r"[^A-Za-z0-9_-]+", "_", title).strip("_")
        return (title or t["album_id"])[:60]

    def _make_zip(self, t: dict, imgs: list[str]) -> dict:
        name = self._safe_name(t)
        zpath = str(Path(t["dir"]) / f"{name}.zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as zf:
            for img in imgs:
                zf.write(img, os.path.basename(img))
        return {"kind": "file", "path": zpath, "name": f"{name}.zip"}

    def _make_pdf(self, t: dict, imgs: list[str]) -> dict:
        from PIL import Image
        name = self._safe_name(t)
        pdf_path = str(Path(t["dir"]) / f"{name}.pdf")
        pils = []
        try:
            for img in imgs:
                pils.append(Image.open(img).convert("RGB"))
            pils[0].save(pdf_path, "PDF", save_all=True, append_images=pils[1:])
        finally:
            for p in pils:
                try:
                    p.close()
                except Exception:
                    pass
        return {"kind": "file", "path": pdf_path, "name": f"{name}.pdf"}

    def _make_longimg(self, t: dict, imgs: list[str]) -> dict:
        from PIL import Image
        name = self._safe_name(t)
        width = 800
        max_height = 20_000  # QQ 对超高图片兼容性不稳定，自动切片保证交付成功。
        pages: list[list[tuple[str, int]]] = []
        current: list[tuple[str, int]] = []
        current_height = 0
        for p in imgs:
            with Image.open(p) as source:
                h = max(1, int(source.height * width / source.width))
            if current and current_height + h > max_height:
                pages.append(current)
                current = []
                current_height = 0
            current.append((p, h))
            current_height += h
        if current:
            pages.append(current)

        paths = []
        for index, page in enumerate(pages, 1):
            height = sum(h for _, h in page)
            canvas = Image.new("RGB", (width, height), (255, 255, 255))
            y = 0
            for p, h in page:
                with Image.open(p) as source:
                    image = source.convert("RGB").resize((width, h))
                canvas.paste(image, (0, y))
                image.close()
                y += h
            suffix = f"_第{index}段" if len(pages) > 1 else ""
            out_path = str(Path(t["dir"]) / f"{name}{suffix}.jpg")
            canvas.save(out_path, "JPEG", quality=85)
            canvas.close()
            paths.append(out_path)
        return {"kind": "images", "paths": paths, "name": f"{name}.jpg"}

    # ---------------- 交付 ----------------

    async def _deliver(self, t: dict, deliver: dict) -> None:
        if t["status"] == "cancelled":
            return
        name = deliver["name"]
        if deliver["kind"] == "images":
            paths = deliver["paths"]
            title_display = t.get("title") or t["album_id"]
            first = [Plain(f"任务{t['tid']}完成：{title_display}，共{len(paths)}段。")]
            first.extend(Image(file=item) for item in paths[:4])
            await self._send_async(t["session"], first)
            for i in range(4, len(paths), 4):
                batch = [Image(file=item) for item in paths[i:i + 4]]
                await self._send_async(t["session"], batch)
            return
        path = deliver["path"]
        # napcat 容器可访问路径保障：确保路径在 /AstrBot/data 下（软链打通）
        real = os.path.realpath(path)
        if not real.startswith("/AstrBot/data"):
            fallback_dir = Path("/AstrBot/data/MengliRoom/漫画/tmp/deliver")
            fallback_dir.mkdir(parents=True, exist_ok=True)
            dest = fallback_dir / name
            shutil.copy2(path, dest)
            path = str(dest)
        await self._send_async(t["session"], [Plain(f"任务{t['tid']}完成：{name}"), File(name=name, file=path)])

    # ---------------- 清理 ----------------

    def _cleanup_task(self, t: dict) -> None:
        try:
            d = t.get("dir")
            if d and os.path.isdir(d):
                shutil.rmtree(d, ignore_errors=True)
        except Exception as e:
            logger.error(f"[jm下载姬] 清理任务目录失败: {e}")

    # ---------------- 生命周期 ----------------

    async def terminate(self) -> None:
        logger.info("[jm下载姬] 插件卸载，清理依赖")
        try:
            if self._update_timer_task:
                self._update_timer_task.cancel()
        except Exception:
            pass
        try:
            await asyncio.to_thread(self._uninstall_dep)
        except Exception as e:
            logger.error(f"[jm下载姬] 卸载清理异常: {e}")

    @staticmethod
    def _uninstall_dep() -> None:
        # 所有二进制依赖都限定在插件 .deps，直接删除即可，不会影响系统或其他插件。
        try:
            shutil.rmtree(_DEPS_DIR, ignore_errors=True)
            logger.info("[jm下载姬] 私有依赖目录已清理")
        except Exception as e:
            logger.error(f"[jm下载姬] 私有依赖清理失败: {e}")
