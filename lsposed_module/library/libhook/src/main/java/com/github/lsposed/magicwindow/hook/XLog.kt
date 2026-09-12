package com.github.lsposed.magicwindow.hook

import de.robv.android.xposed.XposedBridge

/**
 * 统一日志出口，写入 LSPosed 模块日志（XposedBridge.log）。
 * 仅保留 info / error 两级，热路径不打日志。
 */
object XLog {

    fun i(msg: String) = XposedBridge.log("[MagicWindow] $msg")

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("[MagicWindow][E] $msg")
        if (t != null) XposedBridge.log(t)
    }
}
