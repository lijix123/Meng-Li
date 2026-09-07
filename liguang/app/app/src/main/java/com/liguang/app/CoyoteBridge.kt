package com.liguang.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 郊狼桥：连手机本地的 OTC 控制器（默认 ws://127.0.0.1:60536/1），
 * 把服务器下发的郊狼指令（OTC 原始 JSON）透传给郊狼。
 *
 * 特性：懒连接（收到第一条指令才建连）、断线指数退避自动重连、
 * 30 秒保活（OTC 会关闭长时间无消息的连接）、pending 补发（连接建立后补发第一条指令）。
 */
object CoyoteBridge {

    /**
     * 候选本地 OTC 地址：DG-LAB 控制接口可能绑定在 127.0.0.1 或 USB 网络的 192.168.140.x 上，
     * 依次尝试，直到某个地址建连成功。
     */
    private val CANDIDATE_URLS = listOf(
        "ws://192.168.140.40:60536/1",
        "ws://127.0.0.1:60536/1"
    )

    private var urlIndex = 0

    private var webSocket: WebSocket? = null
    private var connecting = false
    private var connected = false
    private var enabled = true
    private var lastError: String? = null
    private var reconnectDelay = 1L
    private var pendingPayload: String? = null

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private val keepAlive = Handler(Looper.getMainLooper())

    private val keepAliveTask = object : Runnable {
        override fun run() {
            try {
                webSocket?.send("ping")
            } catch (_: Exception) {
            }
            keepAlive.postDelayed(this, 30_000L)
        }
    }

    private val reconnectTask = object : Runnable {
        override fun run() {
            synchronized(this@CoyoteBridge) {
                if (enabled && !connected && !connecting) {
                    tryConnectLocked()
                }
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            synchronized(this@CoyoteBridge) {
                connected = true
                connecting = false
                reconnectDelay = 1L
                lastError = null
                webSocket = ws
                keepAlive.removeCallbacks(keepAliveTask)
                keepAlive.postDelayed(keepAliveTask, 30_000L)
                // 补发等待中的指令
                val p = pendingPayload
                if (p != null) {
                    pendingPayload = null
                    try {
                        ws.send(p)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // OTC 返回消息，桥只透传，暂不处理
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(code, reason)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@CoyoteBridge) {
                connected = false
                // 修复：失败必须重置 connecting，否则卡死在 connecting 永远不重试
                connecting = false
                lastError = t.message
                // 当前地址连不上，换下一个候选地址重试
                urlIndex = (urlIndex + 1) % CANDIDATE_URLS.size
                keepAlive.removeCallbacks(keepAliveTask)
                scheduleReconnectLocked()
            }
        }
    }

    /** 启用/停用桥 */
    @Synchronized
    fun setEnabled(flag: Boolean) {
        enabled = flag
        if (!flag) shutdown()
    }

    @Synchronized
    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun isConnected(): Boolean = connected

    /** 状态字串：off / connected / disconnected / connecting */
    @Synchronized
    fun status(): String {
        if (!enabled) return "off"
        return when {
            connected -> "connected"
            connecting -> "connecting"
            else -> "disconnected"
        }
    }

    /** 主动查一次桥连接（尝试补连），返回状态 */
    @Synchronized
    fun checkConnection(): String {
        if (!enabled) return "off"
        if (webSocket == null && !connecting) tryConnectLocked()
        return status()
    }

    /**
     * 发送一条 OTC 指令（原始 JSON 字符串）。
     * 未连接时触发建连并暂存待补发，返回 sent/connecting/error。
     */
    @Synchronized
    fun sendCommand(payload: String): Pair<Boolean, String> {
        if (!enabled) return false to "bridge_off"
        if (payload.isBlank()) return false to "empty_payload"
        val ws = webSocket
        if (ws != null && connected) {
            return try {
                ws.send(payload)
                true to "sent"
            } catch (e: Exception) {
                false to "send_error:${e.message}"
            }
        }
        if (connecting) {
            // 正在建连：暂存，连接建立后补发
            pendingPayload = payload
            return true to "connecting_queued"
        }
        pendingPayload = payload
        tryConnectLocked()
        return if (connecting) true to "connecting_queued"
        else false to "connect_failed:${lastError ?: "unknown"}"
    }

    private fun tryConnectLocked() {
        if (connecting) return
        connecting = true
        lastError = null
        try {
            val req = Request.Builder().url(CANDIDATE_URLS[urlIndex]).build()
            webSocket = okHttp.newWebSocket(req, listener)
        } catch (e: Exception) {
            lastError = e.message
            connecting = false
            scheduleReconnectLocked()
        }
    }

    private fun scheduleReconnectLocked() {
        keepAlive.removeCallbacks(reconnectTask)
        keepAlive.postDelayed(reconnectTask, reconnectDelay * 1000)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30L)
    }

    /** 停掉桥，释放连接 */
    @Synchronized
    fun shutdown() {
        keepAlive.removeCallbacks(keepAliveTask)
        keepAlive.removeCallbacks(reconnectTask)
        try {
            webSocket?.close(1000, "bridge shutdown")
        } catch (_: Exception) {
        }
        webSocket = null
        connected = false
        connecting = false
        pendingPayload = null
        lastError = null
        urlIndex = 0
    }
}
