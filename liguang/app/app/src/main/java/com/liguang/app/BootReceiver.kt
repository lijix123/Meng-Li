package com.liguang.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/** 开机/升级后自动拉起保活服务，让姐姐重启手机也不用管 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Cfg.keepaliveEnabled(context) && Cfg.bootEnabled(context)) {
                val i = Intent(context, ConnectionService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    ContextCompat.startForegroundService(context, i)
                } else {
                    context.startService(i)
                }
            }
        }
    }
}