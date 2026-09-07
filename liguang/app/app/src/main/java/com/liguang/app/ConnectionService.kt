package com.liguang.app

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.pm.ApplicationInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 璃光保活前台服务。
 * 常驻后台持有 WebSocket 长连接，退后台/锁屏也不掉线；断线自动指数退避重连。
 * 连接地址/Token/功能开关均从 SharedPreferences 读取，可热重载。
 */
class ConnectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "liguang"
        private const val NOTIF_ID = 1001

        // ---- 主动心跳 + 假死检测 ----
        private const val HEARTBEAT_INTERVAL_MS = 25_000L   // 每 25 秒发一次心跳
        private const val HEARTBEAT_TIMEOUT_MS = 75_000L    // 连续 75 秒没收到 pong 判定假死

        // ---- 保活闹钟（AlarmManager 定时唤醒检查，照爱语思路） ----
        private const val ACTION_KEEPALIVE = "com.liguang.app.ACTION_KEEPALIVE"
        private const val KEEPALIVE_REQUEST_CODE = 9528
        private const val KEEPALIVE_INTERVAL_MS = 5 * 60 * 1000L  // 每 5 分钟叫醒检查一次

        var ws: WebSocket? = null
            private set

        private var instance: ConnectionService? = null

        /** 供 Telemetry 等取应用上下文 */
        var instanceContext: Context? = null
            private set

        /** 状态/日志回调，由 MainActivity 注册，Activity 销毁后清空（服务不受影响） */
        var onStatusChange: ((Boolean) -> Unit)? = null
        var onLog: ((Pair<Long, String>) -> Unit)? = null
        var onLastCmd: ((String) -> Unit)? = null

        // 日志环形缓冲：页面切走时日志不丢，切回来整段重放
        private val LOG_LOCK = Any()
        private val logRing = ArrayDeque<Pair<Long, String>>()
        private const val LOG_RING_SIZE = 150

        fun logHistory(): List<Pair<Long, String>> = synchronized(LOG_LOCK) { logRing.toList() }

        fun send(json: JSONObject) {
            ws?.send(json.toString())
        }

        fun manualStop() {
            ws?.close(1000, "user stop")
            ws = null
        }

        /** 保存设置后调用：断开并按新配置重连 */
        fun reloadConfig() {
            instance?.apply {
                webSocket?.close(1000, "config reload")
                webSocket = null
                reconnectDelay = 3L
                connect()
            }
        }
    }

    /** 统一日志出口：写环形缓冲 + 转发给当前挂载的页面（带真实时间戳，回放不迷路） */
    private fun log(msg: String) {
        val now = System.currentTimeMillis()
        synchronized(LOG_LOCK) {
            logRing.addLast(now to msg)
            while (logRing.size > LOG_RING_SIZE) logRing.removeFirst()
        }
        onLog?.invoke(now to msg)
    }

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var reconnectDelay = 3L
    // 防重入：connect() 可能被重连线程/网络回调/保活闹钟同时触发，保证同时只建一条连接
    private val connectLock = Any()
    @Volatile private var connecting = false
    // 连接代次：每次 connect 自增，旧代次回调（onOpen/onFailure/onClosed）一律忽略，
    // 杜绝并发建连导致的双连接、以及旧连接断开误触发的重复重连
    @Volatile private var gen = 0L

    // ---- 保活三件套：静音播放 / 唤醒锁 / 任务移除自重启 ----
    private var audioTrack: AudioTrack? = null
    private var silentThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ---- 主动心跳：检测假死连接并强制重建 ----
    @Volatile private var heartbeatStop = false
    private var heartbeatThread: Thread? = null
    @Volatile private var lastPongAt = 0L

    // ---- 网络恢复立即抢连 ----
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cur = webSocket
            if (cur == null) {
                log("网络恢复，立即抢连")
                reconnectDelay = 3L
                connect()
            } else {
                // 连接还在但可能假死，重置心跳计数并立刻探活
                lastPongAt = 0L
                try { cur.send(JSONObject().put("type", "ping").toString()) } catch (_: Exception) {}
            }
        }
    }

    // ---- 保活闹钟：后台被冻结时靠闹钟唤醒，检查连接是否存活 ----
    private val keepAliveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_KEEPALIVE) {
                if (webSocket == null) {
                    log("保活闹钟：连接不在，立即重连")
                    reconnectDelay = 3L
                    connect()
                }
                // 无障碍守护：若曾开启但被系统关了，提醒姐姐重新开启
                AccessService.remindIfSilentlyDisabled(applicationContext)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        instanceContext = this
        startForeground(NOTIF_ID, buildNotification())
        ensureKeepAlive()
        registerNetCallback()
        registerKeepAliveReceiver()
        scheduleKeepAliveAlarm()
        Telemetry.start(this)
        if (webSocket == null) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        instanceContext = null
        stopKeepAlive()
        stopHeartbeat()
        unregisterNetCallback()
        unregisterKeepAliveReceiver()
        cancelKeepAliveAlarm()
        Telemetry.stop(this)
        webSocket?.close(1000, "service destroy")
        webSocket = null
        super.onDestroy()
    }

    /** 用户划掉任务时，用闹钟延迟自启保活服务，配合 mediaPlayback 后台启动豁免，ColorOS 也拦不住 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restart = Intent(applicationContext, ConnectionService::class.java)
            val pi = PendingIntent.getService(
                this, 9527, restart,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 2000, pi)
            log("任务被划掉，两秒后自动复活")
        } catch (e: Exception) {
            log("自启动调度失败：${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    /** 保活三件套：静音播放 + 唤醒锁 */
    private fun ensureKeepAlive() {
        startSilentPlayback()
        acquireWakeLock()
    }

    private fun stopKeepAlive() {
        stopSilentPlayback()
        releaseWakeLock()
    }

    /** 循环写静音 PCM，让系统以为在放歌，ColorOS 优先不冻结媒体播放 */
    private fun startSilentPlayback() {
        try {
            if (audioTrack != null) return
            val sampleRate = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            audioTrack = track
            val silent = ShortArray(minBuf)
            val t = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val w = track.write(silent, 0, silent.size)
                        if (w <= 0) break
                        Thread.sleep(80)
                    }
                } catch (e: Exception) {
                    // 静音线程退出不影响主服务
                }
            }
            t.isDaemon = true
            t.name = "liguang-silent-audio"
            t.start()
            silentThread = t
        } catch (e: Exception) {
            audioTrack = null
        }
    }

    private fun stopSilentPlayback() {
        silentThread?.interrupt()
        silentThread = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            // 忽略
        }
        audioTrack = null
    }

    /** 息屏后锁住 CPU，断线重连线程不被系统按死 */
    private fun acquireWakeLock() {
        try {
            if (wakeLock != null) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liguang:keepalive")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (e: Exception) {
            wakeLock = null
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // 忽略
        }
        wakeLock = null
    }

    /** 主动心跳：每 25 秒发应用层 ping，75 秒没收到 pong 判定连接假死，强制断开触发重连 */
    private fun startHeartbeat() {
        heartbeatStop = false
        lastPongAt = System.currentTimeMillis()
        if (heartbeatThread != null) return
        val t = Thread {
            try {
                while (!heartbeatStop) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                    if (heartbeatStop) break
                    val cur = webSocket
                    if (cur == null) continue  // 连接不在，重连线程自己处理
                    val now = System.currentTimeMillis()
                    if (now - lastPongAt > HEARTBEAT_TIMEOUT_MS) {
                        log("心跳超时，连接疑似假死，强制重连")
                        try { cur.cancel() } catch (_: Exception) {}
                        // cancel 触发 onFailure -> scheduleReconnect
                    } else {
                        try { cur.send(JSONObject().put("type", "ping").toString()) } catch (_: Exception) {}
                    }
                }
            } catch (_: InterruptedException) {
                // 正常退出
            }
        }
        t.isDaemon = true
        t.name = "liguang-heartbeat"
        t.start()
        heartbeatThread = t
    }

    private fun stopHeartbeat() {
        heartbeatStop = true
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    /** 网络恢复（切回 WiFi/解锁/切流量）立即抢连或探活 */
    private fun registerNetCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(netCallback)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {
        }
    }

    /** 保活闹钟：每 5 分钟被闹钟叫醒一次，检查连接是否存活，断了立即重建（照爱语 AlarmManager 思路） */
    private fun registerKeepAliveReceiver() {
        try {
            registerReceiver(keepAliveReceiver, IntentFilter(ACTION_KEEPALIVE))
        } catch (_: Exception) {
        }
    }

    private fun unregisterKeepAliveReceiver() {
        try { unregisterReceiver(keepAliveReceiver) } catch (_: Exception) {
        }
    }

    private fun scheduleKeepAliveAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                this, KEEPALIVE_REQUEST_CODE,
                Intent(this, keepAliveReceiver.javaClass).setAction(ACTION_KEEPALIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + KEEPALIVE_INTERVAL_MS,
                KEEPALIVE_INTERVAL_MS,
                pi
            )
        } catch (_: Exception) {
        }
    }

    private fun cancelKeepAliveAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                this, KEEPALIVE_REQUEST_CODE,
                Intent(this, keepAliveReceiver.javaClass).setAction(ACTION_KEEPALIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        } catch (_: Exception) {
        }
    }

    private fun connect() {
        synchronized(connectLock) {
            if (connecting || webSocket != null) return
            connecting = true
        }
        val myGen = ++gen
        try {
            if (client == null) {
                client = OkHttpClient.Builder()
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build()
            }
            val serverUrl = Cfg.server(this)
            val token = Cfg.token(this)
            log("正在连接璃璃…")
            val request = Request.Builder().url(serverUrl)
                .header("X-Auth-Token", token)
                .build()
            webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen != myGen) {
                        // 已被更新的连接取代，立刻弃掉，不留双连接
                        try { webSocket.cancel() } catch (_: Exception) {}
                        return
                    }
                    ws = webSocket // 同步到 companion，send() 才能真正发消息
                    reconnectDelay = 3L
                    onStatusChange?.invoke(true)
                    log("已连上璃璃服务器")
                    sendHello()
                    startHeartbeat()
                    // 连接成功立即上报一次电量/充电/网络，避免 /devices 里遥测字段一直为空
                    instanceContext?.let { Telemetry.reportNow(it) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != myGen) return
                    handleCommand(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != myGen) return
                    ws = null
                    scheduleReconnect("连接失败，${reconnectDelay}秒后重试")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (gen != myGen) return
                    ws = null
                    if (code != 1000) {
                        scheduleReconnect("连接已关闭，${reconnectDelay}秒后重试")
                    }
                }
            })
        } finally {
            synchronized(connectLock) { connecting = false }
        }
    }

    private fun scheduleReconnect(logMsg: String) {
        val myGen = gen
        stopHeartbeat()
        webSocket?.cancel()
        webSocket = null
        ws = null
        onStatusChange?.invoke(false)
        log(logMsg)
        val delay = reconnectDelay
        reconnectDelay = minOf(reconnectDelay * 2, 30L)
        val t = Thread {
            Thread.sleep(delay * 1000)
            // 睡眠期间若别的入口已连上（网络回调/保活闹钟）或代次已变，就不再重复建连
            synchronized(connectLock) {
                if (webSocket == null && gen == myGen && !connecting) connect()
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun sendHello() {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val hello = JSONObject().apply {
            put("type", "hello")
            put("device", Build.MODEL)
            put("android", Build.VERSION.RELEASE)
            put("app", "liguang-$ver")
        }
        webSocket?.send(hello.toString())
    }

    private fun handleCommand(raw: String) {
        try {
            val obj = JSONObject(raw)
            val cmd = obj.optString("cmd", obj.optString("type", ""))
            onLastCmd?.invoke("最近指令：$cmd")
            when (cmd) {
                "ping" -> send(JSONObject().put("type", "pong"))
                "pong" -> lastPongAt = System.currentTimeMillis()
                "notify" -> {
                    if (Cfg.notifyEnabled(this) && !Cfg.inDnd(this)) {
                        val text = obj.optString("text", obj.optString("content", "璃璃想你啦"))
                        pushNotification("璃璃", text)
                    } else if (Cfg.inDnd(this)) {
                        log("勿扰时段，通知已静默")
                    }
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true))
                }
                "vibrate" -> {
                    if (Cfg.vibrateEnabled(this) && !Cfg.inDnd(this)) {
                        val ms = obj.optLong("ms", Cfg.vibrateMs(this))
                        doVibrate(ms)
                    } else if (Cfg.inDnd(this)) {
                        log("勿扰时段，震动已静默")
                    }
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true))
                }
                "screenshot" -> {
                    if (Cfg.screenshotEnabled(this)) {
                        // 优先无障碍截图：直接抓帧、无需授权弹窗、无共享屏幕保护黑屏问题
                        val isBurst = Cfg.screenshotMode(this) == "burst"
                        val accOk = Build.VERSION.SDK_INT >= 30 &&
                            AccessService.isEnabled(this) &&
                            AccessService.requestScreenshot(
                                frames = if (isBurst) Cfg.burstFrames(this) else 1,
                                interval = Cfg.burstInterval(this),
                            )
                        if (accOk) {
                            log(if (isBurst) "已通过无障碍连拍截图" else "已通过无障碍直接截图")
                        } else {
                            log("无障碍截图不可用，回退授权通道")
                            pushScreenshotRequest()
                        }
                    } else {
                        log("截屏功能已关闭")
                    }
                }
                "update" -> {
                    log("收到更新检查指令")
                    checkAndPushUpdate()
                }
                "volume" -> {
                    val r = doVolume(obj)
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true).put("data", r))
                }
                "brightness" -> {
                    val r = doBrightness(obj)
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true).put("data", r))
                }
                "clear_apps" -> {
                    val r = doClearApps()
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", r.first).put("data", r.second))
                }
                "installed_apps" -> {
                    val r = doInstalledApps(obj)
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true).put("data", r))
                }
                "recent_apps" -> {
                    val r = doRecentApps(obj)
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true).put("data", r))
                }
                "get_notifications" -> {
                    val r = NotifyListenerService.loadList(this)
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true).put("data", r))
                }
                "set_config" -> {
                    val entries = obj.optJSONObject("entries")
                    if (entries == null) {
                        send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", false)
                            .put("data", "missing entries"))
                    } else {
                        val ed = Cfg.prefs(this).edit()
                        val written = JSONArray()
                        val it = entries.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            val v = entries.get(k)
                            when (v) {
                                is Boolean -> ed.putBoolean(k, v)
                                is Int -> ed.putInt(k, v)
                                is Long -> ed.putLong(k, v)
                                is Double -> ed.putString(k, entries.optString(k))
                                else -> ed.putString(k, v.toString())
                            }
                            written.put(k)
                        }
                        ed.apply()
                        log("远程改设置: $written")
                        send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true)
                            .put("data", JSONObject().put("written", written)))
                    }
                }
                "phone" -> {
                    val r = doPhoneSetting(obj)
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true).put("data", r))
                }
                "coyote" -> {
                    if (!Cfg.coyoteEnabled(this)) {
                        send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", false)
                            .put("data", JSONObject().put("bridge", "off")))
                    } else {
                        val payload = obj.optString("payload", "")
                        val r = CoyoteBridge.sendCommand(payload)
                        send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", r.first)
                            .put("data", JSONObject()
                                .put("bridge", CoyoteBridge.status())
                                .put("detail", r.second)))
                    }
                }
                "coyote_status" -> {
                    val st = if (Cfg.coyoteEnabled(this)) CoyoteBridge.checkConnection() else "off"
                    send(JSONObject().put("type", "ack").put("cmd", cmd).put("ok", true)
                        .put("data", JSONObject().put("bridge", st)))
                }
                else -> log("未知指令：$cmd")
            }
        } catch (e: Exception) {
            log("指令解析失败：${e.message}")
        }
    }

    private fun pushNotification(title: String, text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, notif)
    }

    /** 主动推送更新：查服务端版本，有新版则弹通知，点击直接跳下载 */
    private fun checkAndPushUpdate() {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    // 带时间戳绕过中间缓存，确保拿到最新下载地址
                    .url("http://<服务器IP>:8911/version?t=${System.currentTimeMillis()}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val remote = json.optString("version", "")
                    val notes = json.optString("notes", "")
                    val url = json.optString("url", "")
                    val sha = json.optString("sha256", "")
                    val local = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                    } catch (e: Exception) {
                        "0"
                    }
                    if (remote.isNotEmpty() && isNewer(remote, local)) {
                        pushUpdateNotification(remote, notes, url, sha)
                    } else {
                        log("已是最新版本 v$local")
                        pushNotification("璃璃", "已是最新版本 v$local")
                    }
                }
            } catch (e: Exception) {
                log("更新检查失败：${e.message}")
            }
        }.start()
    }

    private fun pushUpdateNotification(version: String, notes: String, url: String, sha: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("action", "update_download")
            putExtra("update_url", url)
            putExtra("update_version", version)
            putExtra("update_sha256", sha)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("发现新版本 v$version")
            .setContentText(notes.ifBlank { "点击在软件内直接更新" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2003, notif)
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun doVibrate(ms: Long) {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            vib.vibrate(ms)
        }
    }

    /** 音量调节：stream 可选 music/ring/alarm/call/notification，value 0~100 或 dir up/down */
    private fun doVolume(obj: JSONObject): JSONObject {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamMap = mapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
            "call" to AudioManager.STREAM_VOICE_CALL,
            "notification" to AudioManager.STREAM_NOTIFICATION,
        )
        val stream = streamMap[obj.optString("stream", "music")] ?: AudioManager.STREAM_MUSIC
        val value = obj.optInt("value", -1)
        val dir = obj.optString("dir", "")
        try {
            if (value >= 0) {
                val max = am.getStreamMaxVolume(stream)
                val idx = (value * max / 100).coerceIn(0, max)
                am.setStreamVolume(stream, idx, 0)
            } else if (dir == "up" || dir == "down") {
                am.adjustStreamVolume(
                    stream,
                    if (dir == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
        } catch (e: Exception) {
            log("音量调节失败：${e.message}")
        }
        val cur = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        val pct = if (max > 0) cur * 100 / max else 0
        return JSONObject().put("stream", stream).put("current", pct).put("index", cur).put("max", max)
    }

    /** 系统级控制：免打扰(dnd) / 铃声模式(ringer) / 状态查询(status) */
    private fun doPhoneSetting(obj: JSONObject): JSONObject {
        val action = obj.optString("action", "status")
        val value = obj.optString("value", "")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val out = JSONObject().put("action", action)
        try {
            when (action) {
                "dnd" -> {
                    val granted = Build.VERSION.SDK_INT < 23 || nm.isNotificationPolicyAccessGranted
                    out.put("granted", granted)
                    if (granted) {
                        when (value) {
                            "on" -> {
                                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                                out.put("dnd", "on")
                            }
                            "off" -> {
                                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                                out.put("dnd", "off")
                            }
                        }
                    } else {
                        out.put("msg", "请先在系统设置里授权「勿扰权限」")
                    }
                }
                "ringer" -> {
                    val mode = when (value) {
                        "normal" -> AudioManager.RINGER_MODE_NORMAL
                        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                        "silent" -> AudioManager.RINGER_MODE_SILENT
                        else -> -1
                    }
                    if (mode >= 0) {
                        val ok = try { am.setRingerMode(mode); true } catch (e: Exception) { false }
                        out.put("ok", ok)
                        if (value == "silent") {
                            // 兜底：Android 8+ 静音模式可能被系统忽略，用音量归零兜底
                            try {
                                am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                            } catch (e: Exception) {}
                        }
                    } else {
                        out.put("ok", false).put("msg", "未知铃声模式：$value")
                    }
                    out.put("mode", value)
                    out.put("current_mode", am.ringerMode)
                }
                "status" -> {
                    out.put("dnd_granted", Build.VERSION.SDK_INT < 23 || nm.isNotificationPolicyAccessGranted)
                    if (Build.VERSION.SDK_INT >= 23) {
                        out.put("interruption_filter", nm.currentInterruptionFilter)
                    }
                    out.put("ringer_mode", am.ringerMode)
                    out.put("ring_volume", am.getStreamVolume(AudioManager.STREAM_RING))
                    out.put("max_volume", am.getStreamMaxVolume(AudioManager.STREAM_RING))
                }
            }
        } catch (e: Exception) {
            out.put("error", e.message)
        }
        return out
    }

    /** 屏幕亮度：value 0~100（写系统亮度，需要「修改系统设置」授权） */
    private fun doBrightness(obj: JSONObject): JSONObject {
        val cr = contentResolver
        val value = obj.optInt("value", -1)
        try {
            if (value >= 0) {
                val mode = Settings.System.getInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    Settings.System.putInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    )
                }
                val v = (value * 255 / 100).coerceIn(0, 255)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
            }
        } catch (e: Exception) {
            log("亮度调节失败：${e.message}")
        }
        val cur = try {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) {
            128
        }
        val writable = Settings.System.canWrite(this)
        return JSONObject().put("current", cur * 100 / 255).put("writable", writable)
    }

    /** 清后台：走无障碍模拟「最近任务→清除全部」，尽力版 */
    private fun doClearApps(): Pair<Boolean, String> {
        if (!AccessService.isEnabled(this)) {
            return false to "无障碍服务未开启，清后台需要它"
        }
        val ok = AccessService.requestClearBackground()
        return if (ok) true to "已模拟清后台，请留意手机屏幕"
        else false to "无障碍执行失败"
    }

    /** 已装应用列表：默认只列用户安装的应用，可 includeSystem 带系统应用 */
    private fun doInstalledApps(obj: JSONObject): JSONObject {
        val pm = packageManager
        val includeSystem = obj.optBoolean("includeSystem", false)
        val max = obj.optInt("max", 200).coerceIn(1, 500)
        val arr = JSONArray()
        try {
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { ai ->
                    val label = try {
                        pm.getApplicationLabel(ai).toString()
                    } catch (e: Exception) {
                        ai.packageName
                    }
                    JSONObject().put("label", label).put("package", ai.packageName)
                }
                .sortedBy { it.optString("label") }
                .take(max)
                .forEach { arr.put(it) }
        } catch (e: Exception) {
            log("读取应用列表失败：${e.message}")
        }
        return JSONObject().put("count", arr.length()).put("apps", arr)
    }

    /** 最近使用的应用：靠 UsageStatsManager，需要「使用情况访问」授权 */
    private fun doRecentApps(obj: JSONObject): JSONObject {
        val granted = usageStatsGranted()
        val limit = obj.optInt("limit", 15).coerceIn(1, 50)
        val arr = JSONArray()
        if (granted) {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val start = end - 24L * 3600 * 1000
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                    .filter { it.lastTimeUsed > 0 }
                    .sortedByDescending { it.lastTimeUsed }
                    .take(limit)
                val pm = packageManager
                // 一次拿全量应用建 包名→应用名 映射，避免 Android 11+ 包可见性导致 getApplicationInfo 查不到
                val labelMap = try {
                    pm.getInstalledApplications(0).associate { ai ->
                        val lb = try {
                            pm.getApplicationLabel(ai).toString()
                        } catch (e: Exception) {
                            ai.packageName
                        }
                        ai.packageName to lb
                    }
                } catch (e: Exception) {
                    emptyMap()
                }
                for (s in stats) {
                    val label = labelMap[s.packageName] ?: s.packageName
                    arr.put(
                        JSONObject()
                            .put("label", label)
                            .put("package", s.packageName)
                            .put("last_used", s.lastTimeUsed)
                    )
                }
            } catch (e: Exception) {
                log("读取使用记录失败：${e.message}")
            }
        }
        return JSONObject().put("granted", granted).put("count", arr.length()).put("apps", arr)
    }

    /** 检测「使用情况访问」是否已授予（AppOps） */
    private fun usageStatsGranted(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** 按需截屏：弹一条「点此授权」通知，点击后走 MainActivity 前台授权流程 */
    private fun pushScreenshotRequest() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("action", "screenshot_request")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("璃璃想看看你")
            .setContentText("点此授权，先把想让我看的画面准备好，3秒后自动截取")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1002, notif)
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "璃璃通知", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("璃璃在线守护中")
            .setContentText("梦璃一直在你身边，勿扰模式请允许后台运行")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
