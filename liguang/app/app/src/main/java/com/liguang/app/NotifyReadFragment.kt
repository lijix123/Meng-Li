package com.liguang.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 第六页：通知读取（系统授权引导 + 最近通知列表） */
class NotifyReadFragment : Fragment(), Saveable {

    private lateinit var swNotifyRead: SwitchCompat
    private lateinit var tvNlStatus: TextView
    private lateinit var tvNotifyList: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_notify_read, container, false)
        swNotifyRead = v.findViewById(R.id.swNotifyRead)
        tvNlStatus = v.findViewById(R.id.tvNlStatus)
        tvNotifyList = v.findViewById(R.id.tvNotifyList)
        tvNotifyList.movementMethod = ScrollingMovementMethod()

        v.findViewById<Button>(R.id.btnNlGuide).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                tvNlStatus.text = "无法打开系统设置，请到「设置-通知使用权」里手动开启"
            }
        }
        v.findViewById<Button>(R.id.btnNlClear).setOnClickListener {
            NotifyListenerService.clear(requireContext())
            refreshList()
        }
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshList()
    }

    private fun refreshStatus() {
        val enabled = NotifyListenerService.isEnabled(requireContext())
        tvNlStatus.text = if (enabled) "系统授权：已开启" else "系统授权：未开启"
    }

    private fun refreshList() {
        val arr = NotifyListenerService.loadList(requireContext())
        if (arr.length() == 0) {
            tvNotifyList.text = "（暂无记录）"
            return
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (i in arr.length() - 1 downTo 0) {
            try {
                val o = arr.getJSONObject(i)
                val app = o.optString("app", o.optString("pkg", "?"))
                val title = o.optString("title", "")
                val text = o.optString("text", "")
                val time = fmt.format(Date(o.optLong("time", 0L)))
                sb.append(time).append(" [").append(app).append("] ").append(title)
                if (text.isNotEmpty()) sb.append("\n").append(text)
                sb.append("\n\n")
            } catch (e: Exception) {
            }
        }
        tvNotifyList.text = sb.toString().trim()
    }

    override fun load(prefs: SharedPreferences) {
        swNotifyRead.isChecked = prefs.getBoolean("notify_read_enabled", true)
    }

    override fun save(prefs: SharedPreferences) {
        prefs.edit().putBoolean("notify_read_enabled", swNotifyRead.isChecked).apply()
    }
}
