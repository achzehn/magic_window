package com.github.lsposed.magicwindow

import android.app.Application
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource
import com.google.android.material.color.DynamicColors

class MagicWindowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigRepository.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        // 后台预热系统内置/云控规则（含 root 读取），列表与详情打开时基本已就绪
        SystemRuleSource.preload()
    }
}
