package com.liguang.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvVersion: TextView

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                // 按设置里的截屏模式取帧：单次 / 连拍
                val burst = Cfg.screenshotMode(this) == "burst"
                if (burst) {
                    ScreenCaptureService.start(
                        this, result.resultCode, result.data,
                        burst = true,
                        frames = Cfg.burstFrames(this),
                        interval = Cfg.burstInterval(this)
                    )
                    toast("正在连拍 ${Cfg.burstFrames(this)} 帧，传完即停")
                } else {
                    ScreenCaptureService.start(this, result.resultCode, result.data)
                    toast("正在取一帧，传完即停")
                }
            } else {
                toast("截屏授权未完成")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            toast(if (granted) "通知权限已开启" else "通知权限被拒")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.init(this)
        setContentView(R.layout.activity_main)

        tvVersion = findViewById(R.id.tvVersion)
        tvVersion.text = "Liguang ${getLocalVersion()} · 梦璃的星光"
        val btnAuth = findViewById<Button>(R.id.btnAuthScreen)
        val btnGuide = findViewById<Button>(R.id.btnGuide)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        btnAuth.setOnClickListener { launchScreenCapture() }

        btnGuide.setOnClickListener { openKeepAliveGuide() }

        btnAccessibility.setOnClickListener { openAccessibilityGuide() }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnCheckUpdate.setOnClickListener {
            toast("正在检查更新…")
            checkForUpdate()
        }

        // 启动保活前台服务（自动连接，退后台不掉线；可在设置里关闭）
        if (Cfg.keepaliveEnabled(this)) {
            ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        } else {
            toast("后台保活已关闭，可在功能设置里开启")
        }

        // 软件内更新检测
        checkForUpdate()

        // 主动推送通知点击进来：直接软件内下载（onCreate 与 onNewIntent 都走同一入口）
        handleUpdateDownload(intent)

        // 按需截屏：点击「璃璃想看看你」通知进来 → 前台授权 → 取帧即停
        handleScreenshotRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateDownload(intent)
        handleScreenshotRequest(intent)
    }

    private fun handleUpdateDownload(intent: Intent?) {
        if (intent?.getStringExtra("action") != "update_download") return
        val url = intent.getStringExtra("update_url") ?: "http://<服务器IP>:8911/apk"
        val ver = intent.getStringExtra("update_version") ?: ""
        val sha = intent.getStringExtra("update_sha256") ?: ""
        Updater.downloadAndInstall(this, url, ver, sha)
    }

    /** 按需截屏入口：从「璃璃想看看你」通知点进来，直接弹系统授权框 */
    private fun handleScreenshotRequest(intent: Intent?) {
        if (intent?.getStringExtra("action") == "screenshot_request") {
            launchScreenCapture()
        }
    }

    /** 保活引导：先弹系统「忽略电池优化」对话框，失败则跳应用详情设置页 */
    private fun openKeepAliveGuide() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 &&
                !pm.isIgnoringBatteryOptimizations(packageName)
            ) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                toast("请在弹窗里允许「保持后台运行」")
                return
            }
        } catch (e: Exception) {
            // 部分 ROM 不支持该入口，走应用详情页
        }
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            toast("请在应用详情里开启：自启动、后台运行、电池无限制")
        } catch (e: Exception) {
            toast("无法打开设置页，请手动到系统设置里允许后台运行")
        }
    }

    /** 无障碍引导：跳到系统无障碍设置页，找到「璃光无障碍服务」开启 */
    private fun openAccessibilityGuide() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请开启「璃光无障碍服务」（为以后远程手势铺路）")
        } catch (e: Exception) {
            toast("无法打开无障碍设置，请手动到系统设置里开启")
        }
    }

    /** 软件内更新检测：异步查询服务器版本，有新版则弹提示 */
    private fun checkForUpdate() {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    // 带时间戳绕过中间缓存：/version 若被缓存，App 会拿到旧 url 走老路被劫持
                    .url("http://<服务器IP>:8911/version?t=${System.currentTimeMillis()}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val remote = json.optString("version", "")
                    val notes = json.optString("notes", "")
                    val url = json.optString("url", "")
                    val force = json.optBoolean("force", false)
                    val sha = json.optString("sha256", "")
                    if (remote.isNotEmpty() && isNewer(remote, getLocalVersion())) {
                        // 非强制更新时，跳过用户已忽略的版本；强制更新装不掉
                        if (!force && remote == Cfg.ignoredUpdateVersion(this)) {
                            runOnUiThread { toast("已忽略 v$remote，可在「检查更新」里重新弹窗") }
                            return@use
                        }
                        runOnUiThread { showUpdateDialog(remote, notes, url, force, sha) }
                    } else {
                        runOnUiThread { toast("已是最新版本") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("更新检测失败：${e.message}") }
            }
        }.start()
    }

    private fun showUpdateDialog(version: String, notes: String, url: String, force: Boolean, sha: String) {
        val builder = AlertDialog.Builder(this)
            .setTitle("发现新版本 v$version")
            .setMessage(notes.ifBlank { "有新版本可用，是否立即更新？" })
            .setPositiveButton("立即更新") { _, _ ->
                Cfg.setIgnoredUpdateVersion(this, "")
                Updater.downloadAndInstall(this, url, version, sha)
            }
        if (force) {
            // 强制更新：不记录忽略，关掉后下次检查还会弹
            builder.setCancelable(false)
            builder.setNegativeButton("稍后再说", null)
        } else {
            builder.setNegativeButton("以后再说") { _, _ ->
                Cfg.setIgnoredUpdateVersion(this, version)
                toast("已记住，该版本不再提醒")
            }
        }
        builder.show()
    }

    private fun getLocalVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    override fun onDestroy() {
        // 只清回调引用，服务继续后台保活
        ConnectionService.onStatusChange = null
        ConnectionService.onLog = null
        ConnectionService.onLastCmd = null
        super.onDestroy()
    }

    private fun launchScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
