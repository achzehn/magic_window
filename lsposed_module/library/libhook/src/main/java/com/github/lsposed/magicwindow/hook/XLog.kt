package com.github.lsposed.magicwindow.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 统一日志出口，写入 LSPosed 模块日志（libxposed `XposedInterface.log`）。
 * 仅保留 info / error 两级，热路径不打日志。
 */
object XLog {

    /** 由入口 [XposedEntry] 在 onModuleLoaded 时注入 */
    @Volatile
    private var xposed: XposedInterface? = null

    fun attach(module: XposedInterface) {
        xposed = module
    }

    // libxposed 102 的 log 需 tag 传 null，否则框架会按自定义 tag 过滤、管理器看不到输出
    fun i(msg: String) = xposed?.log(Log.INFO, null, msg)

    fun e(msg: String, t: Throwable? = null) {
        xposed?.log(Log.ERROR, null, msg, t)
    }
}
