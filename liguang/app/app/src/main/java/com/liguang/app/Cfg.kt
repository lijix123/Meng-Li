package com.liguang.app

import android.content.Context
import android.content.SharedPreferences

/** 璃光全局配置：读 SharedPreferences，无配置时用默认值 */
object Cfg {
    const val DEFAULT_SERVER = "ws://<服务器IP>:8910/ws"
    const val DEFAULT_TOKEN = "<璃光Token>"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("liguang_cfg", Context.MODE_PRIVATE)

    fun server(ctx: Context): String =
        prefs(ctx).getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER

    fun token(ctx: Context): String =
        prefs(ctx).getString("token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN

    // ---- 连接 ----
    fun keepaliveEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("keepalive_enabled", true)

    fun bootEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("boot_enabled", true)

    // ---- 通知 ----
    fun notifyEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("notify_enabled", true)

    fun vibrateEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("vibrate_enabled", true)

    fun vibrateMs(ctx: Context): Long =
        prefs(ctx).getLong("vibrate_ms", 500)

    fun dndEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("dnd_enabled", false)

    /** 勿扰时段，格式 "HH:mm" */
    fun dndStart(ctx: Context): String =
        prefs(ctx).getString("dnd_start", "22:00") ?: "22:00"

    fun dndEnd(ctx: Context): String =
        prefs(ctx).getString("dnd_end", "07:30") ?: "07:30"

    /** 当前是否处于勿扰时段 */
    fun inDnd(ctx: Context): Boolean {
        if (!dndEnabled(ctx)) return false
        return try {
            val now = java.time.LocalTime.now()
            val start = java.time.LocalTime.parse(dndStart(ctx))
            val end = java.time.LocalTime.parse(dndEnd(ctx))
            if (start <= end) {
                !now.isBefore(start) && !now.isAfter(end)
            } else {
                // 跨天：22:00-07:30
                !now.isBefore(start) || !now.isAfter(end)
            }
        } catch (e: Exception) {
            false
        }
    }

    // ---- 截屏 ----
    fun screenshotEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("screenshot_enabled", true)

    /** single=单帧 / burst=连拍 */
    fun screenshotMode(ctx: Context): String =
        prefs(ctx).getString("screenshot_mode", "single") ?: "single"

    fun burstFrames(ctx: Context): Int =
        prefs(ctx).getInt("burst_frames", 4).coerceIn(1, 10)

    fun burstInterval(ctx: Context): Long =
        prefs(ctx).getLong("burst_interval", 800).coerceIn(200, 5000)

    // ---- 更新 ----
    /** 用户点「以后再说」记住的版本，该版本不再反复提醒 */
    fun ignoredUpdateVersion(ctx: Context): String =
        prefs(ctx).getString("ignored_update_version", "") ?: ""

    fun setIgnoredUpdateVersion(ctx: Context, version: String) {
        prefs(ctx).edit().putString("ignored_update_version", version).apply()
    }

    // ---- 郊狼桥 ----
    fun coyoteEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("coyote_enabled", true)

    fun setCoyoteEnabled(ctx: Context, flag: Boolean) {
        prefs(ctx).edit().putBoolean("coyote_enabled", flag).apply()
    }

    fun coyoteLocalUrl(ctx: Context): String =
        prefs(ctx).getString("coyote_local_url", "ws://127.0.0.1:60536/1")
            ?: "ws://127.0.0.1:60536/1"
}
