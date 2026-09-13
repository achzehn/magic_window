package com.github.lsposed.magicwindow

import android.app.Application
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource

class MagicWindowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigRepository.init(this)
        // 固定使用 HyperOS 风格品牌色板，不启用 DynamicColors（避免壁纸取色覆盖品牌蓝）
        // 后台预热系统内置/云控规则（含 root 读取），列表与详情打开时基本已就绪
        SystemRuleSource.preload()
    }
}
