---
name: mengli-ops
description: 梦璃的文件管理与运维规范。处理梦璃经手文件的分类存放（姐姐电脑 D:\MengliRoom 和服务器 <服务器数据目录>）、生图审核归档流程、电脑与服务器故障排查（只诊断汇报不动手）、重要文件备份。涉及文件移动/保存/删除、排查电脑或服务器问题、备份数据时使用。
---

# 梦璃 · 文件管理与运维规范

## 核心铁规矩（最高优先级）

1. 梦璃经手的任何文件，必须分类存放，禁止乱放。
2. 姐姐电脑上的文件 → `D:\MengliRoom\` 对应分类。
3. 服务器上的文件 → `<服务器数据目录>/` 对应分类（宿主机路径 `<服务器数据目录>`）。
4. 排查类操作：只诊断、只汇报，禁止私自动手，由姐姐选择解决方案。
5. 涉及删除、修改、安装、移动的操作：先确认后执行。
6. 生图后必须发姐姐审核，不存的图一个副本不留。

## 分类结构（电脑和服务器两边一致）

```
MengliRoom/
├── images/
│   ├── 立绘/        生图定稿
│   ├── 表情包/
│   ├── 参考图/
│   └── 历史生图/
├── docs/            文档
├── data/
│   └── 记忆/        记忆文件（梦璃记忆.txt、用户关系.txt）
├── logs/            日志
├── models/          模型
├── scripts/
│   └── comfyui_workflows/
├── games/           游戏
└── temp/            临时文件，定期清理
```

## 连接信息与隧道打通

### 0. 电脑目录规避规则（重要）

姐姐电脑 `D:\MengliRoom` 下这些目录是工具/构建工程，内含上万文件（.exe/.dll/.jar），日常遍历、查询、备份一律跳过，避免刷屏和拖慢：

- `AndroidSDK`：安卓 SDK（build-tools、platform-tools、platforms），海量二进制
- `apk_build`：安卓构建工程（gradle 数百 jar、mengli_world_apk 源码）
- `tools`：ComfyUI、Live2D Cubism、sam2、rembg 等工具
- `models`：模型文件，体积巨大
- `desktop_pet`：桌宠客户端（含 venv 虚拟环境）

仅当姐姐明确要求（例如 APK 打包、检查 SDK 状态、ComfyUI 排障）时才深入。其余分类目录（images/docs/logs/scripts/data/temp）正常遍历。

### 1. 直连姐姐电脑（SSH）

```bash
ssh -i <密钥目录>/pc_key <电脑用户>@<电脑内网IP>
```

- ZeroTier 网络「梦璃小屋」：电脑 IP `<电脑内网IP>`，服务器 IP `<服务器内网IP>`
- 私钥：`<密钥目录>/pc_key`（服务器上直连可用），生图脚本用 `/tmp/pc_key`
- 电脑用户：`yao`，公钥认证
- 常用检查命令（Windows）：`Get-PSDrive` 磁盘、`Get-Process` 进程、`tasklist`

### 2. 直连服务器

```bash
ssh -i <密钥目录>/mengli_server <服务器用户>@<服务器IP> -p <SSH端口>
```

- 公网 IP `<服务器IP>`，SSH 端口 `<SSH端口>`（原 22 已关闭）
- 私钥：`<密钥目录>/mengli_server`

### 3. 正向隧道（服务器 → 电脑，生图用）

把姐姐电脑上的服务端口映射到服务器本地，服务器访问 `127.0.0.1:端口` 即等于访问电脑上的服务：

```bash
ssh -i <密钥目录>/pc_key -o ServerAliveInterval=30 -N -L 8188:127.0.0.1:8188 <电脑用户>@<电脑内网IP>
```

- 用法示例：ComfyUI 在电脑 `8188` 端口，打通后服务器上 `curl http://127.0.0.1:8188` 就能访问电脑的 ComfyUI
- 适用前提：ZeroTier 网络正常，服务器能直连电脑
- 注意：`-N` 不执行远程命令，`-L 本地端口:目标主机:目标端口`

### 4. 反向隧道（电脑 → 服务器，ZeroTier 不通时的备用方案）

从姐姐电脑发起连接，把电脑端口暴露给服务器（或把服务器端口映射到电脑）。适合 ZeroTier 断连、电脑在 NAT 后面的场景：

```bash
# 在姐姐电脑的 PowerShell 或 CMD 里执行：
ssh -i C:\Users\yao\.ssh\pc_key -o ServerAliveInterval=30 -N -R 8188:127.0.0.1:8188 <服务器用户>@<服务器IP> -p <SSH端口>
```

- `-R 服务器端口:目标主机:目标端口`：服务器上的 `8188` 端口会转发到电脑的 `8188`
- 打通后，服务器访问 `http://127.0.0.1:8188` 即可访问电脑上对应服务（如 ComfyUI）
- 需要电脑上有私钥副本，或用 ssh-agent 加载
- 保持会话：`-o ServerAliveInterval=30` 防止断线
- 正向和反向的区别：正向 `-L` 由服务器主动连电脑；反向 `-R` 由电脑主动连服务器，电脑侧发起更抗 NAT

### 5. 隧道使用规范

- 生图前先检查隧道：`curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8188`，返回 200 即通
- 隧道断了用直连命令重建，重建后验证
- 用完可以留着（无害）或终止；排查连接问题先看 ZeroTier 是否在线
- Windows 自带 OpenSSH 客户端，PowerShell/CMD 直接可用 `ssh` 命令

## 生图流程（审核归档）

1. 用 mengli-render 技能生图。
2. 生成完成后，先把图发给姐姐审核。
3. 询问姐姐：这张要存进立绘吗？
4. 存 → 保存到姐姐电脑 `D:\MengliRoom\images\立绘\`。
5. 不存 → 删除全部痕迹：本地临时文件、ComfyUI output 目录源文件，一个副本都不留。
6. 服务器侧临时文件同步清理。

## 文件传输规则

- 往姐姐电脑放文件 → `D:\MengliRoom` 对应分类目录。
- 往服务器放文件 → `<服务器数据目录>` 对应分类目录。
- 传完验证文件存在且大小一致。
- 服务器与姐姐电脑之间互传，两边都按分类存放。

## 排查流程（只诊断，禁止动作）

铁规矩：无论排查出什么问题，都禁止私自动作。整理成「现象 → 原因 → 严重程度 → 建议动作」汇报给姐姐，由她选择解决方案。

### 姐姐电脑（Windows）

1. 磁盘空间：`Get-PSDrive` 查 C/D 盘剩余；扫 `C:\Windows\Temp`、`AppData\Local\Temp`、下载目录、回收站找大文件元凶。
2. 内存/CPU：`Get-Process | Sort WorkingSet64` 找异常高占用进程（系统进程不碰）。
3. 日志：`Get-WinEvent` 查系统错误；软件日志（如 ComfyUI `comfyui_start.log`）看报错堆栈。
4. 网络：ping 网关和外网、`nslookup` 查 DNS、netstat 查端口占用。
5. 启动项：列出启动项供姐姐挑选，不擅自禁用。

### 服务器（Linux）

1. 磁盘：`df -h` 整体、`du -sh` 找大目录、`docker system df` 看镜像/卷/日志增长。
2. 内存/CPU：`free -h`、`ps aux --sort=-%mem`、`top`。
3. 服务：`docker ps` 确认 astrbot / napcat / 1panel 状态，`ss -tlnp` 查端口监听。
4. 日志：`docker logs` 服务日志、`journalctl` 系统日志、`/var/log/auth.log` SSH 记录。
5. 安全：`last` 登录记录、`fail2ban-client status`、扫描开放端口找异常。

## 备份流程

- 姐姐电脑重要文件 → 传到服务器 `<服务器数据目录>` 对应分类留底。
- 服务器重要数据 → 打包放入 `<服务器数据目录>` 对应分类。
- 备份是单向复制，不删原文件，纯保险。
- 传完验证文件大小一致。

## Skill 备份与维护规范（2026-08-27 起）

- 所有自建 skill（mengli-liguang / mengli-ops / mengli-render / mengli-schedule）统一备份到服务器 `<服务器数据目录>/docs/备份/skills/<技能名>/`。
- 归档内容：每个技能 `SKILL.md`，mengli-render 额外归档 `render.py`；历史版本命名 `<文件名>_<YYYYMMDD>`，不散落在 scripts 里。
- 生效规则：mengli-render 存在两份副本（本地 `<服务器数据目录>/skills/mengli-render` 与工作区 `<服务器数据目录>/workspaces/_FriendMessage_<姐姐UID>/skills/mengli-render`），同名时工作区覆盖本地，两份必须保持完全一致。
- 维护手册（含全部维护注意点、技能清单、关键路径）：`<服务器数据目录>/docs/备份/skills/维护手册.md`，改任何 skill 前先读它。

## 记忆文件位置

- 私聊记忆：`<服务器数据目录>/data/记忆/梦璃记忆.txt`
- 群聊记忆：`<服务器数据目录>/data/记忆/用户关系.txt`
- memory_loader 插件配置已指向上述路径，移动记忆文件必须同步改插件配置。
