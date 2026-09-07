package com.liguang.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import java.io.File

/** 接收 PackageInstaller 安装结果，装完自动删除安装包 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        // 安装包无论成败都清理，避免残留
        val file = File(context.getExternalFilesDir(null), "liguang_update.apk")
        if (file.exists()) file.delete()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("liguang_update", "璃光更新", NotificationManager.IMPORTANCE_LOW)
        )
        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "安装完成，安装包已自动清理"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "请在安装界面点确认完成安装"
            else -> "安装未完成（状态 $status），安装包已清理"
        }
        nm.notify(
            3001,
            NotificationCompat.Builder(context, "liguang_update")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("璃光更新")
                .setContentText(text)
                .build()
        )
    }
}
