package com.liguang.app

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

/** 第三页：通知与勿扰 */
class NotifyFragment : Fragment(), Saveable {

    private lateinit var swNotify: SwitchCompat
    private lateinit var swVibrate: SwitchCompat
    private lateinit var etVibrateMs: EditText
    private lateinit var swDnd: SwitchCompat
    private lateinit var etDndStart: EditText
    private lateinit var etDndEnd: EditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_notify, container, false)
        swNotify = v.findViewById(R.id.swNotify)
        swVibrate = v.findViewById(R.id.swVibrate)
        etVibrateMs = v.findViewById(R.id.etVibrateMs)
        swDnd = v.findViewById(R.id.swDnd)
        etDndStart = v.findViewById(R.id.etDndStart)
        etDndEnd = v.findViewById(R.id.etDndEnd)
        return v
    }

    override fun load(prefs: SharedPreferences) {
        swNotify.isChecked = prefs.getBoolean("notify_enabled", true)
        swVibrate.isChecked = prefs.getBoolean("vibrate_enabled", true)
        etVibrateMs.setText(prefs.getLong("vibrate_ms", 500).toString())
        swDnd.isChecked = prefs.getBoolean("dnd_enabled", false)
        etDndStart.setText(prefs.getString("dnd_start", "22:00"))
        etDndEnd.setText(prefs.getString("dnd_end", "07:30"))
    }

    override fun save(prefs: SharedPreferences) {
        val ms = etVibrateMs.text.toString().toLongOrNull()?.coerceIn(100, 10000) ?: 500
        val dStart = etDndStart.text.toString().trim().ifEmpty { "22:00" }
        val dEnd = etDndEnd.text.toString().trim().ifEmpty { "07:30" }
        prefs.edit()
            .putBoolean("notify_enabled", swNotify.isChecked)
            .putBoolean("vibrate_enabled", swVibrate.isChecked)
            .putLong("vibrate_ms", ms)
            .putBoolean("dnd_enabled", swDnd.isChecked)
            .putString("dnd_start", dStart)
            .putString("dnd_end", dEnd)
            .apply()
    }
}
