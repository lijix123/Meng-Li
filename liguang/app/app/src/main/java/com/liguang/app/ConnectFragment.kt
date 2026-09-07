package com.liguang.app

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

/** 第二页：连接配置 */
class ConnectFragment : Fragment(), Saveable {

    private lateinit var etServer: EditText
    private lateinit var etToken: EditText
    private lateinit var swKeepalive: SwitchCompat
    private lateinit var swBoot: SwitchCompat

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_connect, container, false)
        etServer = v.findViewById(R.id.etServer)
        etToken = v.findViewById(R.id.etToken)
        swKeepalive = v.findViewById(R.id.swKeepalive)
        swBoot = v.findViewById(R.id.swBoot)
        return v
    }

    override fun load(prefs: SharedPreferences) {
        etServer.setText(prefs.getString("server_url", Cfg.DEFAULT_SERVER))
        etToken.setText(prefs.getString("token", Cfg.DEFAULT_TOKEN))
        swKeepalive.isChecked = prefs.getBoolean("keepalive_enabled", true)
        swBoot.isChecked = prefs.getBoolean("boot_enabled", true)
    }

    override fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("server_url", etServer.text.toString().trim().ifEmpty { Cfg.DEFAULT_SERVER })
            .putString("token", etToken.text.toString().trim().ifEmpty { Cfg.DEFAULT_TOKEN })
            .putBoolean("keepalive_enabled", swKeepalive.isChecked)
            .putBoolean("boot_enabled", swBoot.isChecked)
            .apply()
    }
}
