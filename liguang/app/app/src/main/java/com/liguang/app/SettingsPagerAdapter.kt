package com.liguang.app

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** 功能设置分页：状态 / 连接 / 通知 / 截屏 / 无障碍 / 通知读取 */
class SettingsPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {

    override fun getItemCount(): Int = 6

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> StatusFragment()
            1 -> ConnectFragment()
            2 -> NotifyFragment()
            3 -> CaptureFragment()
            4 -> AccessFragment()
            5 -> NotifyReadFragment()
            else -> StatusFragment()
        }
    }
}
