import os
from astrbot.api import logger
from astrbot.api.provider import ProviderRequest
from astrbot.api.event import AstrMessageEvent, MessageChain, filter
from astrbot.api.star import Context, Star, register
from astrbot.core.agent.message import TextPart

PLUGIN_VERSION = "v2.7.0"

@register("astrbot_plugin_memory_loader", "梦璃", "自动读取记忆文件并注入上下文", PLUGIN_VERSION)
class MemoryLoaderPlugin(Star):
    def __init__(self, context: Context, config: dict = None):
        super().__init__(context)
        self._config = config or {}
        self._injected_sessions = set()

        raw_mode = self._config.get("load_mode", "on_new_session")
        valid_modes = ("on_new_session", "on_reset")
        self._load_mode = raw_mode if raw_mode in valid_modes else "on_new_session"

        # 记录已提示过"记忆缺失"的会话，避免每次请求重复刷屏
        self._notified_no_memory: set[str] = set()
        # 记录已注入成功的会话：注入成功后该会话不再运行本插件
        self._done_sessions: set[str] = set()
        # 记录各会话注入成功时的上下文长度，用于判断对话是否被重置
        self._injected_ctx_len: dict[str, int] = {}

        # 私聊白名单（支持嵌套与扁平旧配置）
        _private_wl = self._config.get("private_whitelist", {}) or {}
        self._private_wl_enabled = _private_wl.get(
            "private_whitelist_enabled",
            self._config.get("whitelist_enabled", False),
        )
        self._private_wl_users = _private_wl.get(
            "private_whitelist_users",
            self._config.get("whitelist_users", []),
        )
        if isinstance(self._private_wl_users, str):
            self._private_wl_users = [self._private_wl_users]

        # 群聊规则：群号 -> 该群允许读取的文件（支持新增/删除，各群隔离）
        self._group_rules = self._config.get("group_rules", [])
        if not isinstance(self._group_rules, list):
            self._group_rules = []
        # 兼容旧配置：将旧的 group_whitelist / group_files 合并为规则条目
        if not self._group_rules:
            _group_wl = self._config.get("group_whitelist", {}) or {}
            _old_groups = _group_wl.get(
                "group_whitelist_groups",
                self._config.get("whitelist_groups", []),
            )
            _group_files = self._config.get("group_files", {}) or {}
            _old_paths = _group_files.get(
                "group_memory_file_paths",
                self._config.get("group_memory_file_paths", []),
            )
            if isinstance(_old_groups, str):
                _old_groups = [_old_groups]
            if _old_groups:
                self._group_rules = [
                    {"group_id": gid, "group_memory_file_paths": list(_old_paths)}
                    for gid in _old_groups
                ]

        # 私聊专属文件（支持嵌套与扁平旧配置）
        _private_files = self._config.get("private_files", {}) or {}
        _raw_private = _private_files.get("private_memory_file_paths", None)
        if _raw_private is None:
            _raw_private = self._config.get("private_memory_file_paths", None)
        self._private_paths = (
            _raw_private if isinstance(_raw_private, list) else [_raw_private]
        ) if _raw_private else None

        mode_map = {
            "on_new_session": "仅新对话加载",
            "on_reset": "重置对话时重新读取"
        }
        logger.info(
            "梦璃记忆加载器已启动 | "
            f"模式: {mode_map.get(self._load_mode, self._load_mode)} | "
            f"私聊白名单: {'启用' if self._private_wl_enabled else '关闭'} | "
            f"群聊规则数: {len(self._group_rules)}"
        )

    def _get_group_rule(self, group_id):
        """查找群号对应的规则条目"""
        for rule in self._group_rules:
            if str(rule.get("group_id", "")) == str(group_id):
                return rule
        return None

    def _is_whitelisted(self, event: AstrMessageEvent) -> bool:
        group_id = event.get_group_id()
        if group_id:
            # 群聊场景：必须在群规则白名单内
            return self._get_group_rule(group_id) is not None
        # 私聊场景：按私聊白名单判断
        if not self._private_wl_enabled:
            return True
        return event.get_sender_id() in self._private_wl_users

    def _resolve_paths(self, event: AstrMessageEvent):
        """根据会话类型解析应读取的记忆文件路径"""
        group_id = event.get_group_id()
        if group_id:
            rule = self._get_group_rule(group_id)
            if rule is None:
                return []
            paths = rule.get("group_memory_file_paths", [])
            if isinstance(paths, str):
                paths = [paths]
            return paths if paths else []
        return self._private_paths if self._private_paths else []

    def _read_memory(self, paths: list) -> str:
        if not paths:
            return ""
        parts = []
        for i, path in enumerate(paths):
            if not os.path.exists(path):
                logger.warning(f"记忆文件不存在: {path}")
                continue
            try:
                with open(path, "r", encoding="utf-8") as f:
                    text = f.read().strip()
                    if text:
                        parts.append(text)
                        logger.info(f"已读取记忆文件 [{i+1}/{len(paths)}]: {path}")
            except Exception as e:
                logger.error(f"读取记忆文件失败 {path}: {e}")
        return "\n\n".join(parts) if parts else ""

    async def _inject_memory(self, req: ProviderRequest, session_id: str, user_id: str, source: str, event: AstrMessageEvent):
        """读取记忆文件并注入到 LLM 请求上下文"""
        paths = self._resolve_paths(event)
        memory = self._read_memory(paths)
        if not memory:
            logger.warning(f"{source}: 未读到记忆文件 | 会话: {session_id}")
            if session_id not in self._notified_no_memory:
                self._notified_no_memory.add(session_id)
                msg = f"[梦璃记忆] {source}: 未读取到记忆文件，本次未注入记忆"
                try:
                    await event.send(MessageChain().message(msg))
                except Exception as e:
                    logger.error(f"发送记忆读取失败提示失败: {e}")
            return False

        self._notified_no_memory.discard(session_id)
        req.extra_user_content_parts.append(
            TextPart(
                text=(
                    "\n\n【记忆档案 - 请在回答中自然地利用以下信息】\n"
                    f"{memory}\n"
                    "【记忆档案结束】\n"
                )
            )
        )

        self._injected_ctx_len[session_id] = len(req.contexts or [])
        logger.info(
            f"{source} | 会话: {session_id} | 用户: {user_id}"
        )
        return True

    def _was_reset(self, session_id: str, req: ProviderRequest) -> bool:
        """判断会话是否被重置：上下文长度较上次注入时大幅缩水，视为 /reset 清空了对话"""
        last_len = self._injected_ctx_len.get(session_id, 0)
        cur_len = len(req.contexts or [])
        return cur_len < max(2, last_len // 2)

    @filter.on_llm_request()
    async def on_llm_request(self, event: AstrMessageEvent, req: ProviderRequest):
        group_id = event.get_group_id()
        scene = f"群[{group_id}]" if group_id else f"私聊[{event.get_sender_id()}]"
        if not self._is_whitelisted(event):
            logger.info(f"[记忆加载器] {scene} 未通过白名单，跳过")
            return

        session_id = event.get_session_id()
        ctx_len = len(req.contexts or [])

        # 注入成功后该会话不再运行本插件；
        # on_reset 模式下若检测到对话被重置（上下文清空），则擦除标记允许重新注入
        if session_id in self._done_sessions:
            if self._load_mode == "on_reset" and self._was_reset(session_id, req):
                self._done_sessions.discard(session_id)
                self._injected_ctx_len.pop(session_id, None)
                logger.info(f"[记忆加载器] {scene} 检测到对话重置，允许重新注入记忆")
            else:
                logger.debug(f"[记忆加载器] {scene} 已注入成功，跳过 ctx_len={ctx_len}")
                return

        if self._load_mode == "on_new_session":
            if session_id in self._injected_sessions:
                logger.debug(f"[记忆加载器] {scene} 会话已注入过，跳过")
                return
            self._injected_sessions.add(session_id)
            logger.info(f"[记忆加载器] {scene} 新会话触发注入")
            ok = await self._inject_memory(
                req, session_id, event.get_sender_id(), "自动触发", event
            )
            if ok:
                self._done_sessions.add(session_id)
                logger.info(f"[记忆加载器] {scene} 注入成功，后续不再运行")
            return

        if self._load_mode == "on_reset":
            # 注入成功前每次请求都尝试（失败仅提示一次，不刷屏）；成功后永久跳过
            ok = await self._inject_memory(
                req, session_id, event.get_sender_id(), "自动触发", event
            )
            if ok:
                self._done_sessions.add(session_id)
                logger.info(f"[记忆加载器] {scene} 注入成功，后续不再运行")
            return

        logger.info(f"[记忆加载器] {scene} 未知模式，跳过")