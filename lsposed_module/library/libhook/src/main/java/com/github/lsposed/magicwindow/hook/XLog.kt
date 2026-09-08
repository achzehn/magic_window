package com.github.lsposed.magicwindow.hook

import com.github.lsposed.magicwindow.common.Constants
import de.robv.android.xposed.XposedBridge

/** 统一日志出口，verbose 由全局配置控制。 */
object XLog {

    @Volatile
    var verbose: Boolean = false

    fun i(msg: String) = XposedBridge.log("[${Constants.LOG_TAG}] $msg")

    fun d(msg: String) {
        if (verbose) XposedBridge.log("[${Constants.LOG_TAG}] $msg")
    }

    /**
     * 惰性版本，供热路径使用：verbose 关闭时连字符串都不会拼，
     * 避免在 WindowManagerGlobalLock 内产生大量短命对象和 GC 压力。
     */
    inline fun d(msg: () -> String) {
        if (verbose) XposedBridge.log("[${Constants.LOG_TAG}] ${msg()}")
    }

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("[${Constants.LOG_TAG}][E] $msg")
        if (t != null) XposedBridge.log(t)
    }
}
