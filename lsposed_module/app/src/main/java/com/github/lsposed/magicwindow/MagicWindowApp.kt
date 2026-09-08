package com.github.lsposed.magicwindow

import android.app.Application
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.google.android.material.color.DynamicColors

class MagicWindowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigRepository.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
