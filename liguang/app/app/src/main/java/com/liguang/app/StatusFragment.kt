package com.liguang.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Bundle
import android.text.format.DateFormat
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment

/** 可被设置页统一保存的 Fragment */
interface Saveable {
    fun load(prefs: SharedPreferences)
    fun save(prefs: SharedPreferences)
}

/** 第一页：状态卡 + 实时日志 */
class StatusFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvServer: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvLastCmd: TextView
    private lateinit var tvLog: TextView
    private lateinit var swAutoScroll: Switch
    private var autoScroll = true
    private val logBuf = StringBuilder()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            view?.post { updateBattery(context, intent) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_status, container, false)
        tvStatus = v.findViewById(R.id.tvStatus)
        tvServer = v.findViewById(R.id.tvServer)
        tvToken = v.findViewById(R.id.tvToken)
        tvBattery = v.findViewById(R.id.tvBattery)
        tvLastCmd = v.findViewById(R.id.tvLastCmd)
        tvLog = v.findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()
        swAutoScroll = v.findViewById(R.id.swAutoScroll)
        swAutoScroll.isChecked = true
        swAutoScroll.setOnCheckedChangeListener { _, checked -> autoScroll = checked }
        return v
    }

    override fun onResume() {
        super.onResume()
        // 每次回到页面都重新读配置，保存后立刻能看到新值
        refreshInfo()
        ConnectionService.onStatusChange = { online ->
            view?.post { tvStatus.text = if (online) "在线" else "离线" }
        }
        ConnectionService.onLastCmd = { cmd ->
            view?.post { tvLastCmd.text = "最近指令：$cmd" }
        }
        ConnectionService.onStatusChange?.invoke(ConnectionService.ws != null)
        // 整段重放：先清空旧显示，再铺上最新历史，来回切页面不重复
        logBuf.setLength(0)
        ConnectionService.logHistory().forEach { appendLog(it.first, it.second) }
        ConnectionService.onLog = { entry ->
            view?.post { appendLog(entry.first, entry.second) }
        }
        // 注册电池广播，电量/充电状态实时刷新
        try {
            requireContext().registerReceiver(
                batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        // 只清日志类回调，避免与主界面冲突；保活服务不受影响
        ConnectionService.onLog = null
        try {
            requireContext().unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }

    /** 刷新服务器/Token/电量显示 */
    private fun refreshInfo() {
        tvServer.text = "服务器：${Cfg.server(requireContext())}"
        val t = Cfg.token(requireContext())
        tvToken.text = "Token：${maskToken(t)}"
        updateBatteryFallback(requireContext())
    }

    private fun updateBattery(ctx: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0) level * 100 / maxOf(scale, 1) else -1
        val levelText = if (pct >= 0) "$pct%" else "?"
        tvBattery.text =
            "电量：$levelText（${if (charging) "充电中" else "未充电"}）· ${Telemetry.currentNetwork(ctx)}"
    }

    private fun updateBatteryFallback(ctx: Context) {
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            tvBattery.text =
                "电量：$level%（${if (charging) "充电中" else "未充电"}）· ${Telemetry.currentNetwork(ctx)}"
        } catch (_: Exception) {
            tvBattery.text = "电量：？（未充电）· ${Telemetry.currentNetwork(ctx)}"
        }
    }

    private fun maskToken(t: String): String {
        if (t.length <= 4) return "****"
        return t.take(3) + "****" + t.takeLast(2)
    }

    private fun appendLog(ts: Long, msg: String) {
        // 用日志自己的真实时间戳，回放时不会误看成同一秒
        val time = DateFormat.format("HH:mm:ss", ts)
        logBuf.append("[$time] ").append(msg).append("\n")
        // 只保留最近 150 行，翻看历史不丢
        val lines = logBuf.toString().lines()
        if (lines.size > 150) {
            logBuf.setLength(0)
            lines.takeLast(150).forEach { logBuf.append(it).append("\n") }
        }
        tvLog.text = logBuf.toString()
        // 自动翻滚：开关开着才自动滚到底部，关着保留手动翻看位置
        if (autoScroll) {
            tvLog.post {
                val max = tvLog.layout?.let { it.height - tvLog.height } ?: 0
                tvLog.scrollTo(0, maxOf(max, 0))
            }
        }
    }
}