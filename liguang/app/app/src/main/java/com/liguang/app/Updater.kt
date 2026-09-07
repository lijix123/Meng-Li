package com.liguang.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 软件内实时更新：App 内下载 APK → 显示进度 → PackageInstaller 安装 → 安装完自动删安装包 */
object Updater {
    private const val CHANNEL = "liguang_update"
    private const val NOTIF_ID = 3001

    fun downloadAndInstall(ctx: Context, url: String, version: String, expectSha: String = "") {
        createChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, progressNotif(ctx, 0, version))
        Thread {
            var ok = true
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                // 下载地址用纯净独立路径（path 已随版本变化）。不再追加 ?v= query——
                // 中间代理按 path 缓存、忽略 query，追加反而可能被缓存设备歧义匹配到旧包
                val req = Request.Builder().url(url).build()
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val target = File(dir, "liguang_update.apk")
                if (target.exists()) target.delete() // 清掉上次残留安装包
                var expectSum = ""
                var expectVer = ""
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        showErrorWithBrowser(ctx, nm, "下载失败：HTTP ${resp.code}", url)
                        ok = false
                        return@use
                    }
                    expectSum = resp.header("X-Checksum-Sha256") ?: ""
                    expectVer = resp.header("X-APK-Version") ?: ""
                    val total = resp.body?.contentLength() ?: -1L
                    val input = resp.body!!.byteStream()
                    val output = target.outputStream()
                    val buf = ByteArray(8192)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt().coerceIn(1, 99)
                            nm.notify(NOTIF_ID, progressNotif(ctx, p, version))
                        }
                    }
                    output.close()
                    input.close()
                    // 字节数校验：下载内容必须与声明长度一致，防止中间层截断
                    if (total > 0 && done != total) {
                        target.delete()
                        showErrorWithBrowser(ctx, nm, "下载校验失败：大小不符，请重试", url)
                        ok = false
                        return@use
                    }
                }
                // 校验一：对文件本身算 sha256，与 version.json 下发的期望值比对。
                // 不依赖任何响应头，中间缓存剥头或塞旧包都骗不过去
                if (ok && expectSha.isNotEmpty()) {
                    val actual = sha256Hex(target)
                    if (!actual.equals(expectSha, ignoreCase = true)) {
                        target.delete()
                        showErrorWithBrowser(ctx, nm, "下载校验失败：文件与服务器不一致，请用浏览器下载", url)
                        ok = false
                    }
                }
                // 版本核对：响应头声明的 APK 版本必须等于目标版本，防止中间缓存返回旧包
                if (ok && expectVer.isNotEmpty() && expectVer != version) {
                    target.delete()
                    showErrorWithBrowser(ctx, nm, "下载校验失败：拿到的是 v$expectVer，目标 v$version", url)
                    ok = false
                }
                // sha256 校验：与服务端指纹比对，防止装到被缓存的旧包（响应头兜底）
                if (ok && expectSum.isNotEmpty()) {
                    val actual = sha256Hex(target)
                    if (!actual.equals(expectSum, ignoreCase = true)) {
                        target.delete()
                        showErrorWithBrowser(ctx, nm, "下载校验失败：文件不完整，请重试", url)
                        ok = false
                    }
                }
                if (!ok) return@Thread
                // 装前强校验：直接解析 APK 包内真实版本号，防止中间缓存剥掉响应头、
                // 把旧包（版本号与已装相同）送到安装器导致"版本相同"装不上
                val pi = try {
                    ctx.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                } catch (e: Exception) { null }
                if (pi == null) {
                    target.delete()
                    showErrorWithBrowser(ctx, nm, "装前校验失败：无法解析安装包，请重试", url)
                    ok = false
                } else {
                    pi.applicationInfo.sourceDir = target.absolutePath
                    pi.applicationInfo.publicSourceDir = target.absolutePath
                    val apkVer = pi.versionName ?: ""
                    val apkCode = pi.versionCode
                    if (apkVer != version) {
                        target.delete()
                        showErrorWithBrowser(ctx, nm, "装前校验失败：包内是 v$apkVer，目标 v$version", url)
                        ok = false
                    } else {
                        val localCode = try {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
                        } catch (e: Exception) { 0 }
                        if (apkCode <= localCode) {
                            target.delete()
                            showErrorWithBrowser(ctx, nm, "装前校验失败：包版本未高于当前版本", url)
                            ok = false
                        }
                    }
                }
                if (!ok) return@Thread
                nm.notify(NOTIF_ID, progressNotif(ctx, 100, version))
                Thread.sleep(200)
                installApk(ctx, target)
            } catch (e: Exception) {
                showErrorWithBrowser(ctx, nm, "下载失败：${e.message}", url)
            }
        }.start()
    }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 下载完成后唤起系统安装器（FileProvider + ACTION_VIEW），由系统安装界面接管，Android 16 兼容 */
    private fun installApk(ctx: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            showError(ctx, nm, "无法唤起安装：${e.message}")
        }
    }

    private fun progressNotif(ctx: Context, progress: Int, version: String): android.app.Notification {
        val text = if (progress >= 100) "下载完成，正在唤起安装…" else "正在下载 v$version：$progress%"
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("璃光更新")
            .setContentText(text)
            .setProgress(100, progress, progress < 0)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun showError(ctx: Context, nm: NotificationManager, msg: String) {
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("璃光更新失败")
                .setContentText(msg)
                .build()
        )
    }

    /** 更新失败提示 + 「用浏览器下载」按钮：绕开被中间缓存污染的内置更新通道 */
    private fun showErrorWithBrowser(ctx: Context, nm: NotificationManager, msg: String, url: String) {
        val browser = PendingIntent.getActivity(
            ctx, 1,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("璃光更新失败")
                .setContentText(msg)
                .setContentIntent(browser)
                .addAction(0, "用浏览器下载", browser)
                .build()
        )
    }

    private fun createChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL, "璃光更新", NotificationManager.IMPORTANCE_LOW
        )
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
