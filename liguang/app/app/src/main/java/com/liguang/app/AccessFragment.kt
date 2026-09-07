package com.liguang.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

/** 第五页：权限与无障碍引导（无障碍服务 / 修改系统设置 / 使用情况访问） */
class AccessFragment : Fragment(), Saveable {

    private lateinit var tvAccessStatus: TextView
    private lateinit var tvWriteStatus: TextView
    private lateinit var tvUsageStatus: TextView
    private lateinit var tvDndStatus: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_access, container, false)
        tvAccessStatus = v.findViewById(R.id.tvAccessStatus)
        tvWriteStatus = v.findViewById(R.id.tvWriteStatus)
        tvUsageStatus = v.findViewById(R.id.tvUsageStatus)
        tvDndStatus = v.findViewById(R.id.tvDndStatus)

        v.findViewById<Button>(R.id.btnAccessGuide).setOnClickListener {
            openSettings(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                "无法打开无障碍设置，请到系统设置里手动开启",
                tvAccessStatus,
            )
        }
        v.findViewById<Button>(R.id.btnWriteGuide).setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .setData(Uri.parse("package:${requireContext().packageName}"))
            openSettings(intent, "无法打开授权页，请到系统设置里搜索「修改系统设置」", tvWriteStatus)
        }
        v.findViewById<Button>(R.id.btnUsageGuide).setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(Uri.parse("package:${requireContext().packageName}"))
            openSettings(intent, "无法打开授权页，请到系统设置里搜索「使用情况访问」", tvUsageStatus)
        }
        v.findViewById<Button>(R.id.btnDndGuide).setOnClickListener {
            openSettings(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                "无法打开授权页，请到系统设置里搜索「勿扰权限」或「通知使用权」",
                tvDndStatus,
            )
        }
        return v
    }

    private fun openSettings(intent: Intent, fallbackTip: String, status: TextView) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            status.text = fallbackTip
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val ctx = requireContext()
        tvAccessStatus.text = if (isAccessibilityEnabled(ctx)) "当前：已开启" else "当前：未开启"
        tvWriteStatus.text = if (Settings.System.canWrite(ctx)) "当前：已授权" else "当前：未授权"
        tvUsageStatus.text = if (isUsageStatsGranted(ctx)) "当前：已授权" else "当前：未授权"
        tvDndStatus.text = if (isDndGranted(ctx)) "当前：已授权" else "当前：未授权"
    }

    /** 检测系统免打扰（勿扰）权限 */
    private fun isDndGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        // 用 AccessibilityManager 标准 API 检测，适配 Android 16（不依赖 Settings.Secure 字符串格式）
        return try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            services.any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
        } catch (e: Exception) {
            false
        }
    }

    /** 检测「使用情况访问」是否已授予（AppOps） */
    private fun isUsageStatsGranted(ctx: Context): Boolean {
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    override fun load(prefs: SharedPreferences) {
        // 权限开关由系统控制，无需加载
    }

    override fun save(prefs: SharedPreferences) {
        // 无需保存
    }
}
