#!/usr/bin/env python3
"""
璃光 服务端
WebSocket 服务：接收/下发指令，鉴权连接
  ws://0.0.0.0:8910/ws   设备长连接
  http://0.0.0.0:8911/cmd  下发指令入口 (POST JSON，需 X-Auth-Token)
  http://0.0.0.0:8911/status|devices|history (需 X-Auth-Token)
  http://0.0.0.0:8911/version|apk (公开，供 App 更新检测/下载)
"""
import asyncio
import json
import logging
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("liguang-server")

WS_HOST = "0.0.0.0"
WS_PORT = 8910
HTTP_PORT = 8911
import os
TOKEN = os.environ.get("LIGUANG_TOKEN", "")  # 部署时从环境变量注入

DEVICES = {}
_lock = asyncio.Lock()
_event_loop = None

# 指令历史：环形队列，最多 200 条（含时间戳/来源/内容/结果）
CMD_HISTORY = deque(maxlen=200)
HIST_LOCK = threading.Lock()

# 最近一次 App 确认（ack）的完整数据，供 /lastack 查询（读应用/读后台结果等）
LAST_ACK = {}
LAST_ACK_LOCK = threading.Lock()


def record_history(src, content, result):
    with HIST_LOCK:
        CMD_HISTORY.appendleft({
            "t": time.strftime("%H:%M:%S"),
            "src": src,
            "cmd": content,
            "ok": result,
        })


def history_snapshot(limit=50):
    with HIST_LOCK:
        return list(CMD_HISTORY)[:limit]


async def handler(ws):
    auth = ws.request.headers.get("X-Auth-Token", "")
    if auth != TOKEN:
        log.warning("鉴权失败，拒绝连接")
        await ws.close(code=4401, reason="unauthorized")
        return
    if ws.request.path != "/ws":
        await ws.close(code=4404, reason="not found")
        return

    info = {"battery": None, "charging": None, "network": None, "screen_on": None, "last_screen_time": 0, "foreground_app": None, "foreground_at": 0, "last_seen": time.time()}
    remote = "?"
    try:
        remote = "%s:%s" % ws.remote_address
    except Exception:
        pass
    async with _lock:
        DEVICES[ws] = info
    log.info("设备已连接(%s)，当前设备数: %s", remote, len(DEVICES))
    try:
        async for message in ws:
            info["last_seen"] = time.time()
            try:
                data = json.loads(message)
                mtype = data.get("type", "")
                if mtype == "hello":
                    info["device"] = data.get("device", "unknown")
                    info["android"] = data.get("android", "?")
                    info["app"] = data.get("app", "?")
                    # 同设备去重：同一台设备只保留最新连接，旧连接踢掉，避免重复登录
                    async with _lock:
                        for old_ws in list(DEVICES.keys()):
                            if old_ws is not ws and DEVICES[old_ws].get("device") == info["device"]:
                                DEVICES.pop(old_ws, None)
                                try:
                                    await old_ws.close(code=4000, reason="duplicate replaced")
                                except Exception:
                                    pass
                                log.info("关闭同设备旧连接: %s", info["device"])
                    log.info("设备上报: %s (Android %s, %s)",
                             info["device"], info["android"], info["app"])
                elif mtype == "report":
                    # 遥测上报：电量/充电/网络/屏幕亮灭/前台应用
                    info["battery"] = data.get("battery")
                    info["charging"] = data.get("charging")
                    info["network"] = data.get("network")
                    info["screen_on"] = data.get("screen_on")
                    info["last_screen_time"] = data.get("last_screen_time")
                    info["foreground_app"] = data.get("foreground_app")
                    info["foreground_at"] = data.get("foreground_at")
                    record_history("app", "遥测上报", True)
                elif mtype == "pong":
                    pass
                elif mtype == "ping":
                    # App 主动心跳，立即回 pong，让 App 能发现假死连接并主动重建
                    await ws.send(json.dumps({"type": "pong"}))
                elif mtype == "ack":
                    log.info("设备确认指令: %s ok=%s", data.get("cmd"), data.get("ok"))
                    record_history("app", "ack:%s" % data.get("cmd"), data.get("ok", False))
                    with LAST_ACK_LOCK:
                        LAST_ACK["t"] = time.strftime("%H:%M:%S")
                        LAST_ACK["cmd"] = data.get("cmd")
                        LAST_ACK["ok"] = data.get("ok", False)
                        LAST_ACK["data"] = data.get("data")
                else:
                    log.info("未知消息: %s", data)
            except json.JSONDecodeError:
                log.warning("非法消息: %r", message[:200])
    except websockets.ConnectionClosed:
        pass
    finally:
        async with _lock:
            DEVICES.pop(ws, None)
        log.info("设备断开，当前设备数: %s", len(DEVICES))


# 假死连接清理：超过 STALE_SECONDS 没有任何消息（含 App 心跳 ping）则判定假死强制断开，
# 防止客户端进程冻结/TCP 假死后 /devices 残留空壳设备
STALE_SECONDS = 90


async def reap_stale_connections():
    while True:
        await asyncio.sleep(30)
        now = time.time()
        stale = []
        async with _lock:
            for ws, info in list(DEVICES.items()):
                last = info.get("last_seen") or now
                if now - last > STALE_SECONDS:
                    stale.append(ws)
        for ws in stale:
            try:
                await ws.close(code=4001, reason="heartbeat timeout")
            except Exception:
                pass
            log.info("心跳超时，清理假死连接")


async def push_command(cmd: dict) -> tuple:
    if not DEVICES:
        return False, "no device"
    payload = json.dumps(cmd, ensure_ascii=False)
    sent = 0
    for ws in list(DEVICES.keys()):
        try:
            await ws.send(payload)
            sent += 1
        except Exception:
            pass
    return sent > 0, f"sent to {sent}"


async def get_devices_snapshot():
    async with _lock:
        return [dict(info) for info in DEVICES.values()]


def is_authed(handler) -> bool:
    auth = handler.headers.get("X-Auth-Token", "")
    return auth == TOKEN


class CmdHandler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        resp = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(resp)

    def do_POST(self):
        if self.path in ("/cmd", "/upload"):
            if not is_authed(self):
                self._json(401, {"ok": False, "msg": "unauthorized"})
                return
        if self.path == "/cmd":
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._json(400, {"ok": False, "msg": "bad json"})
                return
            loop = _event_loop
            if loop is None:
                self._json(500, {"ok": False, "msg": "server not ready"})
                return
            fut = asyncio.run_coroutine_threadsafe(push_command(body), loop)
            ok, msg = fut.result(timeout=5)
            record_history("web", str(body.get("cmd", body.get("type", "?"))), ok)
            self._json(200, {"ok": ok, "msg": msg})
            log.info("HTTP 下发指令 %s -> %s", body.get("cmd"), msg)
            return
        if self.path == "/upload":
            import os
            import base64
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._json(400, {"ok": False, "msg": "bad json"})
                return
            b64 = body.get("image_base64", "")
            if not b64:
                self._json(400, {"ok": False, "msg": "no image"})
                return
            try:
                raw = base64.b64decode(b64)
            except Exception:
                self._json(400, {"ok": False, "msg": "bad base64"})
                return
            cap_dir = os.environ.get("LIGUANG_CAP_DIR", "/app/captures")
            os.makedirs(cap_dir, exist_ok=True)
            # 批次清理：单帧/新批次上传时清掉旧截图，同批次连拍保留（梦璃读完后手动删）
            batch = body.get("batch", "") or ""
            if batch:
                keep_prefix = "cap_%s_" % batch
                for old in os.listdir(cap_dir):
                    if old.endswith(".png") and not old.startswith(keep_prefix):
                        try:
                            os.remove(os.path.join(cap_dir, old))
                        except OSError:
                            pass
            else:
                for old in os.listdir(cap_dir):
                    if old.endswith(".png"):
                        try:
                            os.remove(os.path.join(cap_dir, old))
                        except OSError:
                            pass
            fname = "cap_%s_%d.png" % (batch or int(time.time() * 1000), int(time.time() * 1000))
            fpath = os.path.join(cap_dir, fname)
            with open(fpath, "wb") as f:
                f.write(raw)
            self._json(200, {"ok": True, "file": fname, "bytes": len(raw)})
            log.info("收到截屏 %s (%d bytes)", fname, len(raw))
            return
        self.send_error(404)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/status":
            if not is_authed(self):
                self._json(401, {"ok": False, "msg": "unauthorized"})
                return
            self._json(200, {"devices": len(DEVICES)})
            return
        if path == "/devices":
            if not is_authed(self):
                self._json(401, {"ok": False, "msg": "unauthorized"})
                return
            devs = []
            loop = _event_loop
            if loop is not None:
                fut = asyncio.run_coroutine_threadsafe(get_devices_snapshot(), loop)
                devs = fut.result(timeout=3)
            self._json(200, {"devices": devs})
            return
        if path == "/history":
            if not is_authed(self):
                self._json(401, {"ok": False, "msg": "unauthorized"})
                return
            self._json(200, {"history": history_snapshot()})
            return
        if path == "/lastack":
            if not is_authed(self):
                self._json(401, {"ok": False, "msg": "unauthorized"})
                return
            with LAST_ACK_LOCK:
                self._json(200, {"lastack": dict(LAST_ACK)})
            return
        if path == "/version":
            import os
            vj = os.environ.get("LIGUANG_VERSION_FILE", "/app/version.json")
            data = {
                "version": "0.1.5",
                "name": "0.1.5",
                "url": "http://<服务器IP>:8911/apk",
                "notes": "璃光更新",
                "force": False
            }
            try:
                with open(vj, encoding="utf-8") as f:
                    data.update(json.load(f))
            except Exception:
                pass
            self._json(200, data)
            return
        if path == "/apk" or path.startswith("/apk/"):
            import os
            import re
            import hashlib
            apk_dir = os.environ.get("LIGUANG_APK_DIR", "/app/releases")
            # 支持 /apk 和 /apk/<版本号>：版本号走独立 path，中间缓存按 path 命中，
            # 新版本用新 path 天然绕开缓存（query 参数会被代理忽略，不可靠）
            requested = path[len("/apk/"):] if path.startswith("/apk/") else ""
            best = None
            try:
                for fn in os.listdir(apk_dir):
                    m = re.match(r"liguang-v([\d.]+)-release\.apk$", fn)
                    if not m:
                        continue
                    ver = m.group(1)
                    if requested and ver != requested:
                        continue
                    ver_tuple = tuple(int(x) for x in ver.split("."))
                    if best is None or ver_tuple > best[0]:
                        best = (ver_tuple, ver, fn)
            except OSError:
                pass
            if best is not None:
                _, ver_str, filename = best
                apk_path = os.path.join(apk_dir, filename)
                with open(apk_path, "rb") as f:
                    blob = f.read()
                size = len(blob)
                digest = hashlib.sha256(blob).hexdigest()
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(size))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Checksum-Sha256", digest)
                self.send_header("X-APK-Version", ver_str)
                self.send_header("Content-Disposition", 'attachment; filename="%s"' % filename)
                self.end_headers()
                self.wfile.write(blob)
            else:
                self.send_error(404, "apk not found")
            return
        self.send_error(404)

    def log_message(self, *args):
        pass


def run_http():
    server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), CmdHandler)
    log.info("控制接口已启动 http://0.0.0.0:%s/cmd", HTTP_PORT)
    server.serve_forever()


async def main():
    global _event_loop
    _event_loop = asyncio.get_running_loop()

    def watch_reload():
        """监视 server.py 自身：文件变动且语法正确则自动退出，容器 restart:always 会立即拉起新进程"""
        import os
        import py_compile
        src = os.path.abspath(__file__)
        try:
            last = os.path.getmtime(src)
        except OSError:
            return
        while True:
            time.sleep(3)
            try:
                cur = os.path.getmtime(src)
            except OSError:
                continue
            if cur == last:
                continue
            try:
                py_compile.compile(src, doraise=True)
            except Exception:
                log.warning("server.py 语法检查未通过，跳过自动重启")
                last = cur
                continue
            log.info("检测到 server.py 变更，自动重启生效")
            os._exit(0)

    threading.Thread(target=watch_reload, daemon=True).start()

    threading.Thread(target=run_http, daemon=True).start()
    asyncio.create_task(reap_stale_connections())
    async with websockets.serve(handler, WS_HOST, WS_PORT,
                                ping_interval=30, ping_timeout=60,
                                max_size=10 * 1024 * 1024):
        log.info("璃光服务端已启动 ws://%s:%s/ws", WS_HOST, WS_PORT)
        await asyncio.Future()  # 永久运行


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        log.info("服务端已退出")
