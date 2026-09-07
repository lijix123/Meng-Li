package com.liguang.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2

class SettingsActivity : AppCompatActivity() {

    private lateinit var adapter: SettingsPagerAdapter
    private lateinit var tvSaveHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val pager = findViewById<ViewPager2>(R.id.pager)
        val tabs = findViewById<TabLayout>(R.id.tabLayout)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        tvSaveHint = findViewById(R.id.tvSaveHint)

        adapter = SettingsPagerAdapter(this)
        pager.adapter = adapter
        // 常驻全部 5 页，保存时不会漏掉未加载的页面（ViewPager2 默认只保留当前页附近几页）
        pager.offscreenPageLimit = adapter.itemCount
        // 每个设置页 view 创建完成后自动 load 已存配置，保证打开就看到真实值，不显示空白默认
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
                ) {
                    super.onFragmentViewCreated(fm, f, v, savedInstanceState)
                    if (f is Saveable) {
                        f.load(getSharedPreferences("liguang_cfg", Context.MODE_PRIVATE))
                    }
                }
            }, true
        )
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = arrayOf("状态", "连接", "通知", "截屏", "无障碍", "通知读取")[position]
        }.attach()

        btnSave.setOnClickListener { saveAll() }
    }

    private fun saveAll() {
        val prefs = getSharedPreferences("liguang_cfg", Context.MODE_PRIVATE)
        // FragmentStateAdapter 的 tag 是 f<viewId>:<id> 格式，不能用 f0/f1 定位，
        // 直接遍历 FragmentManager 里已挂载的 Saveable 页面逐个保存
        var saved = 0
        val errors = mutableListOf<String>()
        for (f in supportFragmentManager.fragments) {
            if (f is Saveable && f.isAdded) {
                try {
                    f.save(prefs)
                    saved++
                } catch (e: Exception) {
                    errors.add("${f::class.java.simpleName}: ${e.message}")
                }
            }
        }
        // 按新配置立即生效：保活开着就启动/重连服务，关了就停掉（服务未跑时 reloadConfig 无效，必须直接启停）
        try {
            if (Cfg.keepaliveEnabled(this)) {
                ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
                ConnectionService.reloadConfig()
            } else {
                ConnectionService.manualStop()
                stopService(Intent(this, ConnectionService::class.java))
            }
        } catch (e: Exception) {
            errors.add("应用配置: ${e.message}")
        }
        // 任何一步出错都明说，绝不静默闪退；设置全部保存成功才报已生效
        if (errors.isNotEmpty()) {
            tvSaveHint.text = "保存异常：${errors.first()}"
            Toast.makeText(this, "保存异常：${errors.first()}", Toast.LENGTH_SHORT).show()
        } else if (saved > 0) {
            tvSaveHint.text = "已保存 $saved 页设置，已生效"
            Toast.makeText(this, "设置已保存，已生效", Toast.LENGTH_SHORT).show()
        } else {
            tvSaveHint.text = "保存失败：未找到设置页"
        }
    }
}
