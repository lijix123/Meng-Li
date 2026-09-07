package com.liguang.app

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * 璃光通知读取服务。
 * 监听系统通知，把最近的通知（包名/应用名/标题/内容/时间）保存在本机，
 * 供设置页展示和服务器查询。内容只存本机 SharedPreferences，可随时清除。
 */
class NotifyListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.isOngoing) return // 跳过常驻通知（音乐/倒计时等）
        val pkg = sbn.packageName
        if (pkg == packageName) return // 忽略自己的通知，避免刷屏
        try {
            val extras = sbn.notification?.extras
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                ?: extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ""
            if (title.isEmpty() && text.isEmpty()) return
            val appName = try {
                val pm = packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }
            addEntry(
                this,
                JSONObject()
                    .put("pkg", pkg)
                    .put("app", appName)
                    .put("title", title)
                    .put("text", text)
                    .put("time", System.currentTimeMillis())
            )
        } catch (e: Exception) {
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 预留：需要时可以记录移除事件
    }

    companion object {
        private const val PREFS = "liguang_notify_read"
        private const val KEY_LIST = "notify_list"
        private const val KEY_EVER_ENABLED = "nl_ever_enabled"
        const val MAX_ENTRIES = 50

        fun addEntry(ctx: Context, entry: JSONObject) {
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val list = loadList(ctx)
                list.put(entry)
                while (list.length() > MAX_ENTRIES) list.remove(0)
                prefs.edit().putString(KEY_LIST, list.toString()).apply()
            } catch (e: Exception) {
            }
        }

        fun loadList(ctx: Context): JSONArray {
            return try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
                JSONArray(raw)
            } catch (e: Exception) {
                JSONArray()
            }
        }

        fun clear(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LIST).apply()
        }

        fun markEverEnabled(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EVER_ENABLED, true).apply()
        }

        /** 通知读取服务是否已由用户在系统里开启 */
        fun isEnabled(ctx: Context): Boolean {
            return try {
                val listeners = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx)
                listeners.any { it == ctx.packageName }
            } catch (e: Exception) {
                false
            }
        }
    }
}
