package com.github.lsposed.magicwindow.ui

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.common.model.WindowMode

/** 模式在 UI 上的文案与配色（颜色深浅体现优先级）。 */
object ModeUi {

    @StringRes
    fun labelRes(mode: WindowMode): Int = when (mode) {
        WindowMode.OFF -> R.string.mode_off
        WindowMode.FULL_SCREEN -> R.string.mode_full
        WindowMode.EMBEDDING -> R.string.mode_embedding
        WindowMode.FIXED_ORIENTATION -> R.string.mode_fixed
    }

    @ColorRes
    fun colorRes(mode: WindowMode): Int = when (mode) {
        WindowMode.OFF -> R.color.mode_full
        WindowMode.FULL_SCREEN -> R.color.mode_full
        WindowMode.EMBEDDING -> R.color.mode_embedding
        WindowMode.FIXED_ORIENTATION -> R.color.mode_fixed
    }

    fun label(context: Context, mode: WindowMode): String = context.getString(labelRes(mode))
}
