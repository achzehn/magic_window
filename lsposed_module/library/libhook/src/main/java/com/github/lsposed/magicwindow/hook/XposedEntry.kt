package com.github.lsposed.magicwindow.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed 现代 API 入口（API 102）。
 *
 * 入口声明：`META-INF/xposed/java_init.list`；作用域：`META-INF/xposed/scope.list`
 * （`system` = system_server，承载全部注入逻辑）。
 *
 * 注意：现代 API 下模块自身 App **不会被 hook**，激活状态改由 App 侧经 libxposed 服务判断
 * （见 `ModuleStatus`），因此这里不再处理模块自身包名。
 */
class XposedEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XLog.attach(this)
        // libxposed 的 log 必须 tag=null，自定义 tag 会被框架过滤、管理器看不到输出
        log(Log.INFO, null, "模块已加载 | 进程=${param.processName} | 框架=$frameworkName " +
            "$frameworkVersion | API=$apiVersion")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (frameworkProperties and XposedInterface.PROP_CAP_SYSTEM == 0L) {
            XLog.e("框架不具备 system 能力（PROP_CAP_SYSTEM=0），跳过注入")
            return
        }
        XLog.i("注入 system_server")
        runCatching { SystemServerHooks.install(this, param.classLoader) }
            .onFailure { XLog.e("system_server 注入失败", it) }
    }
}