package com.liguang.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

/**
 * 遥测上报：电量百分比、充电状态、网络类型、屏幕亮灭、前台应用。
 * 电量/网络变化时经 WebSocket 发 report 消息给服务端（服务端存入设备信息，/devices 可查）。
 * 网络类型带缓存：切换网络瞬间 activeNetwork 可能为空，此时用上次有效网络顶替，
 * 只有系统明确 onLost 断网才写 none，避免「切个流量就显示无网络」。
 * 屏幕亮灭变化即时上报，前台应用由 AccessService 写入，随心跳/定期上报带上。
 */
object Telemetry {

    private var registered = false
    private var lastReport = 0L
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var cachedNetwork = "none"

    @Volatile var screenOn: Boolean? = null
    @Volatile var lastScreenChangeAt: Long = 0L
    @Volatile var lastForegroundPackage: String? = null
    @Volatile var lastForegroundAt: Long = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            maybeReport(context)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            screenOn = on
            lastScreenChangeAt = System.currentTimeMillis()
            // 屏幕状态是最重要的"睡了没"信号，变化即时上报
            reportNow(context)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateCache(network)
            maybeReportOnce()
        }

        override fun onLost(network: Network) {
            cachedNetwork = "none"
            maybeReportOnce()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            updateCache(network)
            maybeReportOnce()
        }
    }

    fun start(context: Context) {
        if (registered) return
        registered = true
        try {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
        }
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            context.registerReceiver(screenReceiver, filter)
        } catch (_: Exception) {
        }
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOn = pm.isInteractive
            lastScreenChangeAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        reportNow(context)
        // 兜底：每 5 分钟确保至少上报一次，即使中途无变化
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ reportNow(context) }, 5 * 60 * 1000L)
    }

    fun stop(context: Context) {
        if (!registered) return
        registered = false
        handler.removeCallbacksAndMessages(null)
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private fun maybeReport(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 30_000) return  // 节流 30 秒
        reportNow(context)
    }

    private fun maybeReportOnce() {
        // 网络回调从非主线程进来，直接经 handler 节流上报
        handler.post {
            val ctx = ConnectionService.instanceContext ?: return@post
            maybeReport(ctx)
        }
    }

    private fun updateCache(network: Network) {
        val ctx = ConnectionService.instanceContext ?: return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(network) ?: return
            cachedNetwork = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (_: Exception) {
        }
    }

    fun reportNow(context: Context) {
        lastReport = System.currentTimeMillis()
        val payload = JSONObject()
        payload.put("type", "report")
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            payload.put("battery", level)
            payload.put("charging", charging)
        } catch (_: Exception) {
        }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = if (active != null) cm.getNetworkCapabilities(active) else null
            val net = if (caps != null) {
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            } else {
                cachedNetwork
            }
            cachedNetwork = net
            payload.put("network", net)
        } catch (_: Exception) {
        }
        if (screenOn != null) {
            payload.put("screen_on", screenOn!!)
            payload.put("last_screen_time", lastScreenChangeAt)
        }
        val fg = lastForegroundPackage
        if (fg != null) {
            payload.put("foreground_app", fg)
            payload.put("foreground_at", lastForegroundAt)
        }
        ConnectionService.send(payload)
    }

    /** 供无障碍服务写入当前前台应用包名（零额外授权，无障碍开启即可感知） */
    fun updateForeground(pkg: String) {
        lastForegroundPackage = pkg
        lastForegroundAt = System.currentTimeMillis()
    }

    /** 供设置页状态卡显示当前网络（中文），取不到活跃网络时用缓存，真断网才显示无网络 */
    fun currentNetwork(context: Context): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = if (active != null) cm.getNetworkCapabilities(active) else null
            if (caps != null) {
                return when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "流量"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    else -> "其他"
                }
            }
        } catch (_: Exception) {
        }
        return when (cachedNetwork) {
            "wifi" -> "WiFi"
            "mobile" -> "流量"
            "ethernet" -> "以太网"
            else -> "无网络"
        }
    }
}
