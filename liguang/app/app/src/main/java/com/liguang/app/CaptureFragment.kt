package com.liguang.app

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

/** 第四页：截屏设置（单次/连拍） */
class CaptureFragment : Fragment(), Saveable {

    private lateinit var swScreenshot: SwitchCompat
    private lateinit var rgMode: RadioGroup
    private lateinit var rbSingle: RadioButton
    private lateinit var rbBurst: RadioButton
    private lateinit var etBurstFrames: EditText
    private lateinit var etBurstInterval: EditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_capture, container, false)
        swScreenshot = v.findViewById(R.id.swScreenshot)
        rgMode = v.findViewById(R.id.rgMode)
        rbSingle = v.findViewById(R.id.rbSingle)
        rbBurst = v.findViewById(R.id.rbBurst)
        etBurstFrames = v.findViewById(R.id.etBurstFrames)
        etBurstInterval = v.findViewById(R.id.etBurstInterval)
        return v
    }

    override fun load(prefs: SharedPreferences) {
        swScreenshot.isChecked = prefs.getBoolean("screenshot_enabled", true)
        val mode = prefs.getString("screenshot_mode", "single") ?: "single"
        rgMode.check(if (mode == "burst") R.id.rbBurst else R.id.rbSingle)
        etBurstFrames.setText(prefs.getInt("burst_frames", 4).toString())
        etBurstInterval.setText(prefs.getLong("burst_interval", 800).toString())
    }

    override fun save(prefs: SharedPreferences) {
        val mode = if (rbBurst.isChecked) "burst" else "single"
        val frames = etBurstFrames.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 4
        val interval = etBurstInterval.text.toString().toLongOrNull()?.coerceIn(200, 5000) ?: 800
        prefs.edit()
            .putBoolean("screenshot_enabled", swScreenshot.isChecked)
            .putString("screenshot_mode", mode)
            .putInt("burst_frames", frames)
            .putLong("burst_interval", interval)
            .apply()
    }
}
