package com.liguang.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 璃光无障碍服务骨架。
 * 主要用于手势类系统操作（目前：模拟「最近任务→清除全部」清后台）。
 * 仅在执行清后台指令时读取当前窗口节点以定位「清除」按钮，不存储任何用户数据。
 *
 * 附加「守护」：ColorOS 可能静默关闭无障碍，这里记录曾开启过的状态，
 * 由 ConnectionService 定期检查，一旦被关就弹通知提醒姐姐重新开启。
 */
class AccessService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录当前前台应用包名，供遥测上报（判断姐姐在干嘛/睡了没）
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return  // 忽略自己
        Telemetry.updateForeground(pkg)
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        markEverEnabled(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        // 服务被禁用/关闭时，若曾是开启状态，立即提醒（不等 5 分钟闹钟）
        remindIfSilentlyDisabled(this)
        super.onDestroy()
    }

    private fun _clearBackground(): Boolean {
        return try {
            val ok = performGlobalAction(GLOBAL_ACTION_RECENTS)
            if (ok) {
                Handler(Looper.getMainLooper()).postDelayed({ clickClearAll() }, 1000)
            }
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** 无障碍截图入口（实例方法） */
    private fun _doScreenshot(frames: Int, interval: Long): Boolean {
        return try {
            takeScreenshotAndUpload(frames, interval)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 在最近任务界面查找「清除全部」类按钮并点击（不同 ROM 按钮文本/描述不同，尽力匹配） */
    private fun clickClearAll() {
        val root = rootInActiveWindow ?: return
        val keywords = listOf(
            "清除全部", "清理", "清除", "清空", "一键清理", "全部关闭", "全部清除",
            "close all", "clear all", "clearall", "closeall",
        )
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var found: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && found == null) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (keywords.any { text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true) }) {
                if (node.isClickable) {
                    found = node
                } else {
                    // 按钮常包一层父容器，向上找可点击祖先
                    var p = node.parent
                    while (p != null && found == null) {
                        if (p.isClickable) { found = p; break }
                        p = p.parent
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (found != null) {
            try { found!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
        }
    }

    /** 无障碍截图（API 30+）：直接抓当前屏幕，不走 MediaProjection，无需每次授权弹窗。 */
    private var shotCount = 0
    private var shotTotal = 1
    private var shotInterval = 800L
    private var shotBatch = ""

    private fun takeScreenshotAndUpload(frames: Int, interval: Long) {
        if (Build.VERSION.SDK_INT < 30) {
            notifyResult("当前系统版本不支持无障碍截图")
            return
        }
        shotCount = 0
        shotTotal = frames.coerceIn(1, 10)
        shotInterval = interval.coerceIn(200, 5000)
        shotBatch = System.currentTimeMillis().toString()
        takeOneShot()
    }

    private fun takeOneShot() {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )
                            screenshot.hardwareBuffer.close()
                            if (bitmap != null) {
                                saveAndUpload(bitmap)
                            } else {
                                notifyResult("截图结果为空")
                            }
                        } catch (e: Exception) {
                            notifyResult("截图处理失败")
                        }
                        shotCount++
                        if (shotCount < shotTotal) {
                            Handler(Looper.getMainLooper()).postDelayed(
                                { takeOneShot() }, shotInterval
                            )
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        notifyResult("截图失败（$errorCode）")
                    }
                }
            )
        } catch (e: Exception) {
            notifyResult("截图调用异常")
        }
    }

    private fun saveAndUpload(bitmap: Bitmap) {
        Thread {
            var ok = false
            var msg = ""
            var file: File? = null
            try {
                val dir = File(getExternalFilesDir(null), "captures")
                dir.mkdirs()
                file = File(dir, "acc_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val body = JSONObject().apply {
                    put("image_base64", b64)
                    put("device", Build.MODEL)
                    put("batch", shotBatch)
                }.toString()
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("http://<服务器IP>:8911/upload")
                    .header("X-Auth-Token", Cfg.token(this@AccessService))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    msg = resp.body?.string() ?: ""
                    ok = resp.isSuccessful
                }
            } catch (e: Exception) {
                msg = e.message ?: "网络错误"
            } finally {
                try {
                    file?.delete()
                } catch (_: Exception) {
                }
                notifyResult(if (ok) "截图已传给梦璃" else "上传失败：$msg")
            }
        }.start()
    }

    private fun notifyResult(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                nm.createNotificationChannel(
                    NotificationChannel("liguang", "璃璃通知", NotificationManager.IMPORTANCE_LOW)
                )
            } catch (_: Exception) {
            }
            val notif = NotificationCompat.Builder(this, "liguang")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("璃光截屏")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            nm.notify(2005, notif)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREFS = "liguang_prefs"
        private const val KEY_EVER_ENABLED = "access_ever_enabled"
        private const val KEY_LAST_REMIND = "access_last_remind"
        private const val REMIND_COOLDOWN_MS = 60 * 60 * 1000L  // 1 小时最多提醒一次

        @Volatile
        private var instance: AccessService? = null

        /** 清后台：返回是否已触发无障碍模拟 */
        fun requestClearBackground(): Boolean {
            return instance?._clearBackground() ?: false
        }

        /** 无障碍截图：返回是否已触发（无障碍服务未连接时返回 false） */
        fun requestScreenshot(frames: Int = 1, interval: Long = 800): Boolean {
            return instance?._doScreenshot(frames, interval) ?: false
        }

        fun isEnabled(ctx: Context): Boolean {
            // 用 AccessibilityManager 标准 API 检测，适配 Android 16（不依赖 Settings.Secure 字符串格式）
            return try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                services.any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
            } catch (e: Exception) {
                false
            }
        }

        fun markEverEnabled(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EVER_ENABLED, true).apply()
        }

        /** 曾开启过但现在被关，就弹通知提醒（带 1 小时冷却，避免骚扰） */
        fun remindIfSilentlyDisabled(ctx: Context) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_EVER_ENABLED, false)) return
            if (isEnabled(ctx)) return
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(KEY_LAST_REMIND, 0L) < REMIND_COOLDOWN_MS) return
            prefs.edit().putLong(KEY_LAST_REMIND, now).apply()
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(
                    ctx, 9529, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                try {
                    nm.createNotificationChannel(
                        NotificationChannel("liguang", "璃璃通知", NotificationManager.IMPORTANCE_HIGH)
                    )
                } catch (_: Exception) {
                }
                val notif = NotificationCompat.Builder(ctx, "liguang")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("璃光无障碍服务被关闭了")
                    .setContentText("点此重新开启，保活需要它")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(2010, notif)
            } catch (_: Exception) {
            }
        }
    }
}
