---
name: mengli-liguang
description: 璃光（liguang）远程控制系统运维手册。璃光是梦璃为姐姐自研的 Android App + WebSocket 服务端，用于远程看屏幕、弹通知、震动提醒、控制手机、郊狼电刺激控制。处理璃光服务端/容器的日常运维、排障、App 升级、服务器迁移重建、APK 更新交付时使用。涉及 liguang 容器、8910/8911 端口、/apk 与 /apk/<版本号> 下载、App 重新打包、郊狼桥时触发。
---
# 梦璃 · 璃光远程控制系统手册

## 一、系统是什么

璃光 = 姐姐手机上的 Android App「璃光」 + 服务器上的 WebSocket 服务端。

- App 在姐姐手机上长连接服务器，能收弹窗通知、震动提醒、截屏上报、控制音量亮度、清后台、查应用、读通知、控制郊狼。
- 服务端在 1Panel 的 `liguang` 容器里跑，公网可达，供 App 连接和姐姐/梦璃下发指令。

## 二、服务拓扑（必须记住）

```
姐姐手机 [璃光 App]
   │  WebSocket 长连接
   ▼
公网 <服务器IP>
   │
   ▼
1Panel 容器 liguang（python:3.12-slim，restart: always）
   ├─ 8910  WebSocket  /ws       App 长连接（鉴权头 X-Auth-Token）
   ├─ 8911  HTTP       /cmd      下发指令（POST JSON，需 X-Auth-Token）
   │                     /status  查询设备数（需 X-Auth-Token）
   │                     /devices 查询在线设备详情（型号/系统/App版本/电量/网络，需 X-Auth-Token）
   │                     /history 查询最近指令记录（需 X-Auth-Token）
   │                     /lastack 查询最近一次 App 确认（ack）的完整数据（含 data，读应用/读后台结果从这里拿，需 X-Auth-Token）
   │                     /version 查询最新版本（App 内置更新检测用，公开，Cache-Control: no-store）
   │                     /apk     下载 APK（`/apk` 自动选版本号最高；`/apk/<版本号>` 按指定版本下载，公开，Cache-Control: no-store + X-Checksum-Sha256 指纹头 + X-APK-Version 版本头）
   │                     /upload  App 上传截屏（POST base64 JSON，需 X-Auth-Token，存 /app/captures）
   └─ 挂载（宿主机 → 容器）
      <服务器数据目录>/scripts/liguang-server  → /app
      <服务器数据目录>/scripts/liguang-app/releases → /app/releases
```

- 郊狼链路（v0.1.35 起）：`手机 [璃光 App 内嵌 CoyoteBridge] → OTC 控制器（蓝牙，娱乐模式）→ DG-LAB 郊狼 3.0 → 贴片`。梦璃/插件发 `coyote` 指令，App 透传给 OTC 控制，无需额外 App。
- 截屏回传目录：`<服务器数据目录>/scripts/liguang-server/captures/`（= 容器 /app/captures，梦璃直接读文件发图）
- 隐私策略：服务端截图清理规则——单帧上传会清掉之前所有旧截图；连拍（同 batch）上传会保留该批全部帧、只清非本批的旧图；App 上传完本地即删；梦璃看完后手动删服务器文件（连拍整批保留，服务端不会自动清）。

- 服务器 IP：`<服务器IP>`
- 访问 Token：`<璃光Token>`（通过请求头 `X-Auth-Token` 传递，App 端与服务端一致）
- 容器内工作目录 `/app`，启动命令：`pip install websockets && python server.py`
- 服务端监听 `0.0.0.0:8910`（WS）和 `0.0.0.0:8911`（HTTP）
- 服务端假死连接清理：每个连接记录 `last_seen`（收到任何消息即刷新，含 App 心跳 ping），后台任务每 30 秒巡检一次，超过 `STALE_SECONDS=90` 没有任何消息的连接主动 close（code 4001 heartbeat timeout），防止客户端进程冻结/TCP 假死后 /devices 残留空壳设备。正常 App 每 25 秒心跳，不受影响

## 三、关键文件路径（容器内视角）

| 用途 | 路径 |
|---|---|
| 服务端代码 | `<服务器数据目录>/scripts/liguang-server/server.py` |
| 版本配置 | `<服务器数据目录>/scripts/liguang-server/version.json`（改版号/更新说明，实时生效） |
| 编排文件 | `<服务器数据目录>/scripts/liguang-server/docker-compose.yml` |
| App 源码 | `<服务器数据目录>/scripts/liguang-app/` |
| 连接配置默认值 | `liguang-app/app/src/main/java/com/liguang/app/Cfg.kt`（默认服务器/Token，可在设置页改） |
| 设置页 | `liguang-app/app/src/main/java/com/liguang/app/SettingsActivity.kt` |
| 保活服务 | `liguang-app/app/src/main/java/com/liguang/app/ConnectionService.kt`（读设置，支持热重载，处理 update/音量/亮度/清后台/应用列表/最近应用/读通知/coyote 指令，挂载遥测上报） |
| 软件内更新 | `liguang-app/app/src/main/java/com/liguang/app/Updater.kt` + `InstallReceiver.kt`（下载/安装/清理安装包） |
| 截屏服务 | `liguang-app/app/src/main/java/com/liguang/app/ScreenCaptureService.kt`（单次/连拍取帧+上传，用完即停） |
| 郊狼桥 | `liguang-app/app/src/main/java/com/liguang/app/CoyoteBridge.kt`（v0.1.35+，连本地 OTC 控制器，透传郊狼指令） |
| 通知读取 | `liguang-app/app/src/main/java/com/liguang/app/NotifyListenerService.kt`（读通知列表）+ `NotifyReadFragment.kt`（设置页展示） |
| 开机自启 | `liguang-app/app/src/main/java/com/liguang/app/BootReceiver.kt`（BOOT_COMPLETED / MY_PACKAGE_REPLACED 拉起保活） |
| 遥测上报 | `liguang-app/app/src/main/java/com/liguang/app/Telemetry.kt`（电量/充电/网络监听，WS report） |
| 无障碍骨架 | `liguang-app/app/src/main/java/com/liguang/app/AccessService.kt` + `res/xml/accessibility_service_config.xml` |
| 设置页 | `SettingsActivity.kt`（TabLayout+ViewPager2）+ `SettingsPagerAdapter.kt` + 六个 Fragment（`StatusFragment.kt`/`ConnectFragment.kt`/`NotifyFragment.kt`/`CaptureFragment.kt`/`AccessFragment.kt`/`NotifyReadFragment.kt`）+ 对应 fragment_*.xml 布局 |
| 主界面 | `MainActivity.kt`（主界面 +「检查更新」按钮 + update_download / screenshot_request 通知点击入口 + onNewIntent 分发） |
| 版本配置 | `app/build.gradle.kts`（versionCode / versionName 在此管理，打包前先改） |
| 更新页 | `liguang-app/app/src/main/AndroidManifest.xml`（FileProvider + REQUEST_INSTALL_PACKAGES + RECEIVE_BOOT_COMPLETED + 无障碍服务声明）、`res/xml/file_paths.xml` |
| APK 产物 | `<服务器数据目录>/scripts/liguang-app/releases/` |
| 签名密钥 | `<服务器数据目录>/keys/liguang.keystore` |

宿主机对应路径：`/opt/astrbot/data/...`（容器内 `/AstrBot/data/...` 是它的挂载）。

## 四、日常运维

### 1. 看日志
1Panel → 容器 → `liguang` → 日志。正常启动标志：`璃光服务端已启动`。

### 2. 重启 / 停起
- **改 server.py 后不用手动重启**：服务端每 3 秒监测自身文件，检测到变更且语法正确会自动退出，容器 `restart: always` 自动拉起新进程（最多 3 秒断连，App 自动重连）。
- **改版本号/更新说明不用重启**：直接编辑 `scripts/liguang-server/version.json`，`/version` 接口每次请求实时读取，改完即生效。
- 1Panel → 容器 → `liguang` → 重启，仅在一开始部署或手动干预时使用。

### 3. 确认公网状态
```
curl http://<服务器IP>:8911/status
```
返回 `{"devices": N}`，N 为当前在线 App 数量。

### 4. 下发指令（人工测试）
```
curl -X POST http://<服务器IP>:8911/cmd -H "Content-Type: application/json" \
  -d '{"type":"notify","title":"标题","content":"内容"}'
```
返回 `{"ok": true, "msg": "sent to 1"}` 表示已推给 App。

### 5. 更新 APK / 交付新版本
1. 重新构建：`<服务器数据目录>/scripts/liguang-app/` 下用 gradle 打 release 包，命令：
   `export JAVA_HOME=/opt/jdk && /opt/gradle/current/bin/gradle assembleRelease --console=plain`
   （构建前记得在 `app/build.gradle.kts` 里把 versionName 升到新版本号。）
2. 新 APK 命名为 `liguang-vX.Y.Z-release.apk` 放到 `releases/` 目录。server.py 的 `/apk` 会自动选版本号最高的文件，**无需改服务端**。
3. 改过 `server.py`（如新增接口）保存后自动热重启（最多 3 秒断连），无需手动操作；改版本号/更新说明直接编辑 `version.json`，改完即生效。
4. 验证下载完整：先 `md5sum 源APK`，再 `curl -o /tmp/a.apk "http://<服务器IP>:8911/apk/<新版本号>" && md5sum /tmp/a.apk`，两个 md5 必须一致；同时确认响应头含 `Cache-Control: no-store`、`X-Checksum-Sha256`（值应等于本地 `sha256sum` 源 APK）、`X-APK-Version`（应等于新版本号）。**每个版本必须同步把源 APK 的 sha256 写进 `version.json` 的 `sha256` 字段**，App 下载后按它做文件级校验，写错/漏写会导致更新校验失败。**不要再用 `?v=` 形式验证/下载，代理会忽略 query 并返回缓存旧包。**
5. 把下载链接 `http://<服务器IP>:8911/apk/<版本号>` 发给姐姐安装（推荐带版本号的路径形式）；或用软件内更新：App 端触发查 /version → 发现新版弹窗 → 立即更新，走软件内下载安装。**版本号更新后 `version.json` 的 `url` 必须写成 `/apk/<新版本号>` 这种独立路径**（如 `/apk/0.1.35`），因为中间缓存（运营商/代理）按 path 缓存、会忽略 query 参数，`?v=` 形式仍可能被缓存劫持返回旧包；路径随版本变化才能确保每次都穿透缓存。
6. **发布完成后清理旧版本（姐姐定的规矩，必须执行）**：releases 目录**只保留最新一个版本**的 APK，删掉其余所有旧版本。命令：`cd releases && find . -name "*.apk" ! -name "liguang-v<新版本号>-release.apk" -delete`。保证下载地址永远只能拿到最新包，旧包既不占空间也不会被中间缓存歧义命中。

### 6. 修改 App 连接地址/Token
- 运行时改：姐姐在 App 里点「功能设置」，改服务器地址和 Token 后保存，保活服务自动按新配置重连。
- 重新打包改默认值：改 `Cfg.kt` 的 `DEFAULT_SERVER` / `DEFAULT_TOKEN`。App 端鉴权头就是 `X-Auth-Token`，服务端只认这个头，不要改成 Authorization/Bearer。

### 7. 内置更新检测
- App 启动/主界面「检查更新」按钮时请求 `http://<服务器IP>:8911/version?t=<毫秒时间戳>`（**带时间戳，防止中间缓存返回旧响应导致拿到旧下载地址**），服务端返回 `{"version":"0.1.35","url":".../apk/0.1.35","notes":"...","sha256":"..."}`，与本地 versionName 比对，有新版弹窗提示；点击「立即更新」走软件内下载安装（下载地址用 `/apk/<版本号>` 独立路径，穿透缓存）。
- 发布新版流程：编辑 `version.json`（version/name/notes/sha256 实时生效，不用重启）→ `app/build.gradle.kts` versionName 更新 → 新 APK 放入 `releases/`（/apk 自动选最高版本）→ **`version.json` 的 `url` 务必写成 `/apk/<新版本号>`** → 清理旧包。

### 8. 支持的指令一览（POST /cmd）
| type | 用途 |
|---|---|
| notify | 弹通知：`{"type":"notify","title":"...","content":"..."}` |
| vibrate | 震动提醒：`{"type":"vibrate","ms":3000}` |
| screenshot | 取帧截屏：`{"type":"screenshot"}`。按需模式：App 平时不挂虚拟屏（零耗电、不触发系统录屏保护），收到指令后弹「点此授权」通知，姐姐点击 → 前台授权 → 取帧上传 → 用完即停不留后台。模式在设置页「截屏」分页配置：单次一帧 / 连拍多帧（同 batch 上传，服务端保留该批全部帧，梦璃读完后手动删） |
| update | 主动推送更新：`{"type":"update"}`，App 后台查 /version，有新版弹通知，点击直达软件内下载 |
| volume | 音量控制：`{"type":"volume","stream":"music|ring|alarm|call|notification","value":0~100}`，value 也可传 `dir`+`up/down` 步进。返回当前百分比/索引/最大值。无需授权 |
| brightness | 屏幕亮度：`{"type":"brightness","value":0~100}` 写系统亮度，返回当前值+canWrite。需先开「修改系统设置」（WRITE_SETTINGS）授权 |
| clear_apps | 清后台：`{"type":"clear_apps"}`，走无障碍 `GLOBAL_ACTION_RECENTS` + 关键词匹配点「清除全部」按钮（多 ROM 关键词：清除全部/清理/清除/清空/一键清理/全部关闭/close all，含向上找可点击祖先兜底）。需先开璃光无障碍服务。依赖 ROM 按钮文本，失败时反馈机型补关键词 |
| installed_apps | 已装应用列表：`{"type":"installed_apps","includeSystem":false,"max":200}`，默认只列用户应用，应用名+包名。无需额外授权 |
| recent_apps | 最近使用应用：`{"type":"recent_apps","limit":15}`，靠 UsageStatsManager，返回 label/package/last_used + granted 标志。需先开「使用情况访问」（PACKAGE_USAGE_STATS）授权 |
| get_notifications | 读通知列表：`{"type":"get_notifications"}`，返回最近通知（包名/应用/标题/内容/时间）。需先开「通知使用权」（NOTIFICATION_LISTENER）授权 |
| coyote | 郊狼控制（v0.1.35+）：`{"type":"coyote","payload":"<OTC原始JSON>"}`，App 透传给 OTC 控制器执行，结果走 ack 回传。需 OTC 已连接且处于娱乐模式 |
| coyote_status | 郊狼桥状态（v0.1.35+）：`{"type":"coyote_status"}`，返回 OTC 连接状态/是否已桥接 |
|  | 权限引导（全做进设置页「权限与无障碍」页）：无障碍服务 / 修改系统设置（Settings.ACTION_MANAGE_WRITE_SETTINGS）/ 使用情况访问（Settings.ACTION_USAGE_ACCESS_SETTINGS）/ 通知使用权（Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS），每个带状态检测 + 一键跳转授权。授权路径各品牌差异：小米在权限管理里、OPPO/一加在特殊权限里、华为在权限→特殊权限里，或系统设置直接搜权限名 |

### 9. 遥测上报（电量/网络）
- App 保活服务运行时自动监听电量变化和网络切换，经 WS 发 `{"type":"report","battery":..,"charging":..,"network":..}`，服务端存入设备信息。
- `/devices` 返回里能看到每台设备的字段：`device`（机型）/`android`（系统版本）/`app`（App 版本，hello 上报）+ `battery`/`charging`/`network`（遥测，network: wifi/mobile/none/other）。
- 兜底每 5 分钟至少上报一次，变化时 30 秒节流。

### 10. 勿扰时段（通知/震动静默）
- 配置在设置页「通知」分页：开启勿扰开关后，设定开始/结束时间，可跨天（默认 22:00-07:30）。
- 处于勿扰时段时，App 收到 notify/vibrate 指令会静默处理（不弹通知、不震动），其余功能（截屏/更新/郊狼）不受影响。
- 排障时注意：某时段通知不弹先怀疑勿扰时段，去设置页看开关和时间，而不是以为链路断了。

### 11. HTTP 接口鉴权
- 需要 `X-Auth-Token: <璃光Token>` 的接口：`/cmd`、`/upload`、`/status`、`/devices`、`/history`、`/lastack`，无 Token 一律 401。
- 公开接口（App 更新检测/下载需要）：`/version`、`/apk`。
- App 端已自动在所有请求带上 Token（上传截屏、更新检查无需带但带上无妨）。

### 12. 保活与后台断连机制
- **背景**：ColorOS 等国产 ROM 会对后台应用冻结网络/杀进程，WebSocket 掉线且重连也被按死，症状是「切后台就断、切回 App 立刻好」。
- **App 侧三件套**（ConnectionService.kt）：
  1. 主动心跳：每 25 秒发 `{"type":"ping"}`，服务端回 `{"type":"pong"}`；连续 75 秒无 pong 判定假死，`webSocket.cancel()` 强制重连（HEARTBEAT_INTERVAL_MS=25000 / HEARTBEAT_TIMEOUT_MS=75000）。
  2. 网络恢复抢连：ConnectivityManager NetworkCallback onAvailable 触发，连接不在立即重连，连接在则重置心跳计数并立刻探活。
  3. 保活闹钟：AlarmManager setInexactRepeating 每 5 分钟广播 ACTION_KEEPALIVE，连接不在立即重连（照爱语 ILINK_KEEPALIVE 思路）。
- **无障碍守护**：AccessService 记录「曾开启」状态（prefs `access_ever_enabled`），系统把无障碍静默关闭时 App 弹通知提醒重新开启（1 小时冷却 REMIND_COOLDOWN_MS）。检查入口：保活闹钟 + AccessService.onDestroy。
- **无障碍检测**：用 `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` 标准 API，不依赖 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 字符串——Android 16 上该字符串不可靠，会导致界面永远显示「未开启」。
- **系统设置三项**（ColorOS 后台保活前提，App 侧兜底替代不了）：
  1. 电池 → 不限制（防智能省电掐后台网络）
  2. 自启动 → 允许
  3. 无障碍 → 开启「璃光无障碍服务」（最关键，系统对无障碍应用不冻结后台网络）

## 四·五、郊狼 play 控制（v0.1.35 起）

### 1. 链路与硬件要求
- 链路：`梦璃/插件 → 8911 /cmd → 璃光 App(CoyoteBridge) → OTC 控制器(蓝牙) → DG-LAB 郊狼 3.0 → 贴片`。
- 要求：手机开蓝牙，**OTC 控制器已通过 DG-LAB App 配对且处于「娱乐模式」**（遥控模式不通）；App 内 CoyoteBridge 自动发现/连接 OTC。
- 郊狼未连接/未桥接时，coyote_status 会返回未就绪，此时 pulse/set 不会生效，先查蓝牙和娱乐模式。

### 2. 插件工具 mengli_coyote（关怀插件）
动作：`start`(通电进入play) / `stop`(停止断电) / `pulse`(电击, intensity 0-100 + ms) / `set`(设波形强度, pattern_name/intensity/ticks) / `status`(查桥与通电状态) / `redlight`(红灯紧急停止)。
- 仅通电 play 状态下允许 pulse/set；未 start 直接 pulse/set 会被拒。
- 安全阀（配置面板可调，当前值）：强度上限 100，单次电击上限 100000ms，两次间隔 1 秒，均有冷却。
- **红灯协议（最高优先）**：姐姐喊红灯/喊停 → 立即 redlight 断电，当天不再电击，任何理由都不例外。
- 贴片安全：绝对不贴 头部/喉咙前侧/胸口心脏/腹部；隐私部位只当奖励区不当惩罚区；先轻后重让姐姐适应。

### 3. 直接下发（人工测试）
```
curl -X POST http://<服务器IP>:8911/cmd -H "Content-Type: application/json" -H "X-Auth-Token: <璃光Token>" \
  -d '{"type":"coyote","payload":"{\"cmd\":\"status\"}"}'
```
结果经 ack 回传，去 `/lastack` 拿 `data` 看结果。

## 五、排障清单（按顺序查）

### 1. 服务端没起来 / 连不上
- 1Panel 看 `liguang` 容器状态：是否 running，日志最后几行。
- 容器内服务端是 `python server.py`，依赖 websockets，首次启动会自动 pip install（走阿里云源）。

### 2. 公网 8910/8911 不通
- 先 `curl http://<服务器IP>:8911/status` 测 HTTP。
- 不通则查两层：1Panel 防火墙（防火墙 → 端口规则）和云厂商安全组（放行 8910、8911 TCP）。

### 3. WebSocket 握手返回 4401 unauthorized
- Token 不对，或没用 `X-Auth-Token` 头。确认：`X-Auth-Token: <璃光Token>`。

### 4. 连上了但发 ping 没回执
- App 每 25 秒发主动心跳 `{"type":"ping"}`，服务端立即回 `{"type":"pong"}`。连续 75 秒收不到 pong，App 判定连接假死并强制重连，属正常兜底。
- 验证链路最直接的方式仍是下发指令：POST `/cmd` 推 notify，App 侧能收到、history 出现 ack 才算通。

### 5. App 连不上、设备数一直为 0
- App 打开后应自动连接；确认 App 设置页里的服务器地址/Token 与服务端一致。
- 服务器端 `/status` 看 devices，从 0 变 1 说明连上了。

### 6. APK 下载文件损坏（md5 不一致）
- 重下一次比对；仍不一致则是源文件被人动过，或 `releases/` 里没有更高的 `liguang-vX.Y.Z-release.apk` 导致选错文件。

### 7. 软件内更新失败 / 装不上
- 首次走软件内安装会弹系统授权「允许安装未知应用」，没授权就装不上，去 App 设置或系统设置里允许。
- 下载进度卡住：检查公网 8911 通不通、releases 目录里 APK 是否还在。
- 安装失败后安装包会自动清理；重试即重新下载。
- **提示"已是最新版本"但实际不是**：怀疑 `/version` 响应被缓存（旧响应 version 字段比实际小）。新版 App 请求 `/version` 带时间戳参数可绕过；老版本 App 需换网络重试。
- **缓存劫持装成旧包（重点，根治方案见下）**：若下载看似正常但装出来版本不对，走「根治方案」。

### 8. 设置页保存不生效
- 改一项 → 保存 → 退出设置重进 → 确认值还在。不生效说明装的仍是旧版本 App，先升级。

### 9. 通知/震动不触发
- 先看勿扰时段（见日常运维第 10 节）：处于勿扰时段会静默，不是链路断了。
- 再看设置页「通知」分页的开关是否打开、App 通知权限是否被系统关闭。

### 10. 切后台就断连（「切回来就好」）
- **特征**：App 后台一段时间掉线，打开 App 立刻恢复；服务器 /devices 可能仍显示在线（假死——服务器还没判超时，App 实际已收不到消息）。
- **定位三步**：
  1. 看 history 遥测上报是否停更（正常每 5 分钟至少一次兜底；停更说明 App 后台被冻结）。
  2. POST /cmd 下发 vibrate/notify，4 秒内无 ack 记录 = 链路已断。
  3. 查三项系统设置：电池不限制、自启动允许、无障碍开启——缺一项就是 ColorOS 冻结后台网络的原因。
- **根治**：开启无障碍服务（ColorOS 对无障碍应用不冻结后台网络）+ App 侧三件套兜底（主动心跳/网络抢连/闹钟保活，见日常运维第 12 节）。

## 六、服务器迁移 / 重建（换机器时）

1. 把 `MengliRoom/scripts/liguang-server/`（含 server.py 和 docker-compose.yml）和 `MengliRoom/scripts/liguang-app/releases/` 整个拷到新机器 `<服务器数据目录>/` 下。
2. 1Panel → 编排 → 创建编排，选「路径选择」指向 `<服务器数据目录>/scripts/liguang-server/docker-compose.yml`（手机端编辑框不支持多行粘贴，别用「编辑」模式手贴）。
3. compose 内容（与磁盘上的 docker-compose.yml 一致）：
```yaml
services:
  liguang:
    image: python:3.12-slim
    container_name: liguang
    restart: always
    ports:
      - "8910:8910"
      - "8911:8911"
    environment:
      - TZ=Asia/Shanghai
      - LIGUANG_APK_DIR=/app/releases
    volumes:
      - <服务器数据目录>/scripts/liguang-server:/app
      - <服务器数据目录>/scripts/liguang-app/releases:/app/releases
    working_dir: /app
    command: >
      sh -c "pip install --no-cache-dir websockets -i https://mirrors.aliyun.com/pypi/simple/ -q &&
             exec python server.py"
```
4. 部署后检查日志出现 `璃光服务端已启动`，然后 curl 公网 `/status` 验证。
5. 公网 IP 变了的话，**三处同步改**：`Cfg.kt` 的 `DEFAULT_SERVER`（App 连接地址）、`version.json` 的 `url`（下载地址，写死了旧 IP，漏改则更新下载 404/错连）、重新打包 APK。

## 七、关键避坑记录

1. 1Panel 手机端代码编辑框不能自动换行，粘贴多行 yaml 必报 `yaml: mapping values are not allowed`。用「路径选择」选磁盘上的 compose 文件，或用电脑浏览器操作。
2. 服务端鉴权只认 `X-Auth-Token` 头，App 客户端同样用这个头。
3. 服务端服务器与 App 的 IP/Token 必须两处同步改，漏一处就白连。
4. 挂载路径两行缺一不可：少挂 `/app/releases` 则 APK 下载 404；少挂 `/app` 则服务端代码在容器里不存在。
5. 公网下载 APK 后必须 md5 校验，早前遇到过 5MB 包传输中被截断（差 4 万字节）的案例。
6. `server.py` 的改动（/version、/apk 动态选版）保存后**自动热重启**（每 3 秒监测自身文件，语法检查通过才退出，容器 restart:always 自动拉起），无需手动重启；`version.json` 改完即生效；只放 APK 文件不需要任何操作。
7. **交付/发布流程**：每做完一个功能或改动，先问姐姐「要不要更新」，得到确认后再发布新版、推送 update 或交付 APK，不能直接擅自发。
8. **缓存劫持旧包（最大坑，完整教训）**：运营商/代理对 HTTP 按 path 缓存、忽略 query 参数，会剥掉响应头并塞回旧包（哪怕带 `Cache-Control: no-store`）。曾多次复发：下载 0.1.13 装成 0.1.12、0.1.24 装成 0.1.23、0.1.28 装成 0.1.27、0.1.30 装成 0.1.29——浏览器手动下载都正常，唯独 App 内置更新（OkHttp）被劫持。演进出的根治链：① 下载 URL 用 `/apk/<版本号>` 独立路径，新版本用新 path 天然穿透；② 响应头 `X-APK-Version` / `X-Checksum-Sha256` 核对；③ App 下载后 getPackageArchiveInfo 解析包内真实 versionName/versionCode 强校验；④ 0.1.31 起 `version.json` 存源 APK 的 `sha256`，App 对下载文件算 sha256 比对（不依赖响应头，剥头也拦得住）；⑤ 更新失败一律弹「用浏览器下载」兜底；⑥ releases 只保留最新版。再遇缓存劫持：先让姐姐在更新失败提示上点「用浏览器下载」，或手动开浏览器下 `/apk/<版本号>`，并确认装的 App ≥ 0.1.31。

## 八、错误与修复记录（排错史，只记犯过的错和怎么修）

### 软件内更新类
- **更新点击无反应**：App 是 singleTask，通知点击走 onNewIntent 才有效，直接 startActivity 会失效。修复：更新通知入口走 onNewIntent 分发。
- **Android 16 无法跳转系统安装界面**：PackageInstaller 在 Android 16 调不起安装页。修复：改 FileProvider + ACTION_VIEW 唤起系统安装器。
- **下载到旧包/装成旧版本（缓存劫持，反复复发）**：完整教训见「七、避坑记录」第 8 条。

### 设置页类
- **保存提示成功但重进不生效**：`findFragmentByTag("f$i")` 定位页面，但 ViewPager2 的 tag 实际是 `f<viewId>:<id>` 格式，永远找不到。修复：遍历 supportFragmentManager 已挂载页面逐个保存。
- **保存不完整（漏页）**：ViewPager2 默认只保留当前页附近几页，改的页不在当前视图就存不上。修复：`pager.offscreenPageLimit = adapter.itemCount` 常驻全部页。
- **保存后保活不生效**：只调用 reloadConfig()，服务未运行时无效。修复：保活开则 startForegroundService + reloadConfig，关则 manualStop + stopService。

### 截屏类
- **Android 16 截屏初始化失败**：MediaProjection 回调必须注册在 createVirtualDisplay 之前（系统强制）。修复：调换注册顺序。
- **取帧太快拿到黑屏/空帧**：虚拟显示渲染需要时间。修复：取帧前等待从 700ms 放到 3 秒（CAPTURE_DELAY_MS=3000）。

### 连接保活类
- **消息静默丢失（重大）**：ConnectionService 连接成功只存到私有 webSocket，companion 的 ws 从未赋值，send() 恒空 → 遥测/ack 全丢（hello 因直走 webSocket 能到，表象迷惑）。修复：onOpen 里 ws = webSocket，onFailure/onClosed/scheduleReconnect 里 ws = null。
- **后台断连（「切回来就好」）**：ColorOS 冻结后台网络。修复：App 侧三件套（25 秒主动心跳/网络恢复抢连/5 分钟保活闹钟）+ 系统三项设置（电池不限制/自启动/无障碍）+ 无障碍守护。
- **双连接 + 反复断联重连**：重连线程/网络回调/保活闹钟并发各自建连。修复：connect() 同步锁 + 连接代次 gen（旧代次回调一律忽略并弃旧连接）。
- **服务端残留假死设备**：客户端进程冻结/TCP 假死后 /devices 残留空壳。修复：服务端每连接记 last_seen，每 30 秒巡检，超 90 秒无消息主动 close。

### 无障碍类
- **无障碍被系统静默关闭**（ColorOS 偷关，保活失效）。修复：AccessService 记录曾开启状态，onDestroy 和保活闹钟检查，被关就弹通知提醒重开（1 小时冷却）。
- **状态检测永远显示「未开启」**：`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 字符串在 Android 16 上不可靠。修复：改用 `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` 标准 API。

### 状态页类
- **日志显示全在同一秒循环**：环形缓冲没存真实时间戳。修复：缓冲存 Pair<时间戳, 消息>，回放显示真实时间。
