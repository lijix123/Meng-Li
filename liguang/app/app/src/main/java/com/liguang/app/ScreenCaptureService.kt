package com.liguang.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 按需截屏服务：授权 → 取帧（单次或连拍）→ 逐帧上传 → 立即释放并停止。
 * 平时不挂虚拟屏，零耗电、不触发系统录屏保护；用完即停，不留任何后台占用。
 * 连拍模式：一次授权按配置间隔连拍 N 帧，同一 batch 上传，服务端保留该批全部帧。
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.liguang.app.action.START_CAPTURE"
        private const val NOTIF_ID = 2002
        /** 取帧前等待：给屏幕渲染 + 姐姐解锁/切画面的反应时间，避免抓到黑帧 */
        private const val CAPTURE_DELAY_MS = 3000L

        fun start(context: Context, resultCode: Int, resultData: Intent?) {
            start(context, resultCode, resultData, burst = false, frames = 1, interval = 0)
        }

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent?,
            burst: Boolean,
            frames: Int,
            interval: Long
        ) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra("resultCode", resultCode)
                .putExtra("burst", burst)
                .putExtra("burstFrames", frames.coerceIn(1, 10))
                .putExtra("burstInterval", interval.coerceIn(200, 5000))
            if (Build.VERSION.SDK_INT >= 33) {
                i.putExtra("resultData", resultData)
            } else {
                @Suppress("DEPRECATION")
                i.putExtra("resultData", resultData)
            }
            ContextCompat.startForegroundService(context, i)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    private var burst = false
    private var burstFrames = 1
    private var burstInterval = 800L
    private var batchId = ""
    private var shotCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (_: Exception) {
        }
        if (intent?.action == ACTION_START) {
            burst = intent.getBooleanExtra("burst", false)
            burstFrames = intent.getIntExtra("burstFrames", 1)
            burstInterval = intent.getLongExtra("burstInterval", 800L)
            batchId = System.currentTimeMillis().toString()
            initAndCapture(intent)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotification(if (burst) "璃璃要连拍几张，请停在画面上别动" else "璃璃要看看你，3秒后自动截取，请停在画面上")
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
        }
    }

    private fun initAndCapture(intent: Intent) {
        val resultCode = intent.getIntExtra("resultCode", 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("resultData")
        }
        if (resultCode == 0 || resultData == null) {
            updateNotification("截屏未授权")
            stopSelf()
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)

            // Android 15+ 强制：回调必须注册在开始捕获之前
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    releaseProjection()
                }
            }, handler)

            val bounds = screenBounds()
            val width = bounds.width()
            val height = bounds.height()
            val density = resources.displayMetrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "liguang-capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            if (burst) {
                handler.postDelayed({ takeBurstFrame() }, CAPTURE_DELAY_MS)
            } else {
                handler.postDelayed({ takeFrameAndUpload() }, CAPTURE_DELAY_MS)
            }
        } catch (e: Exception) {
            releaseProjection()
            updateNotification("截屏初始化失败：${e.message}")
            stopSelf()
        }
    }

    /** 连拍：按间隔取 N 帧，每帧独立上传（相同 batch），全部完成或失败后停止 */
    private fun takeBurstFrame() {
        val file = captureFrame()
        shotCount++
        if (file != null) {
            uploadFrame(file, batchId) { _, _ ->
                if (shotCount >= burstFrames) {
                    updateNotification("连拍完成，已传 ${shotCount} 帧")
                    releaseProjection()
                    stopSelf()
                } else {
                    handler.postDelayed({ takeBurstFrame() }, burstInterval)
                }
            }
        } else {
            updateNotification("连拍中断（第 $shotCount 帧失败）")
            releaseProjection()
            stopSelf()
        }
    }

    /** 单帧：取一帧上传即停 */
    private fun takeFrameAndUpload() {
        val file = captureFrame()
        if (file != null) {
            uploadFrame(file, batchId) { ok, msg ->
                updateNotification(if (ok) "截图已传给梦璃" else "上传失败：$msg")
                releaseProjection()
                stopSelf()
            }
        } else {
            updateNotification("暂时没有画面，稍后再试")
            releaseProjection()
            stopSelf()
        }
    }

    private fun captureFrame(): File? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val dir = File(getExternalFilesDir(null), "captures")
            dir.mkdirs()
            val file = File(dir, "liguang_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                cropped.recycle()
            }
            bitmap.recycle()
            image.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun uploadFrame(file: File, batch: String, done: (Boolean, String) -> Unit) {
        Thread {
            var ok = false
            var msg = ""
            try {
                val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val body = JSONObject().apply {
                    put("image_base64", b64)
                    put("device", Build.MODEL)
                    put("batch", batch)
                }.toString()
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("http://<服务器IP>:8911/upload")
                    .header("X-Auth-Token", Cfg.token(this@ScreenCaptureService))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    msg = resp.body?.string() ?: ""
                    ok = resp.isSuccessful
                }
            } catch (e: Exception) {
                msg = e.message ?: "网络错误"
            } finally {
                file.delete()
                handler.post { done(ok, msg) }
            }
        }.start()
    }

    private fun releaseProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun screenBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return wm.currentWindowMetrics.bounds
        }
        @Suppress("DEPRECATION")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            "liguang_capture", "璃璃截屏", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return android.app.Notification.Builder(this, "liguang_capture")
            .setContentTitle("璃光")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }
}