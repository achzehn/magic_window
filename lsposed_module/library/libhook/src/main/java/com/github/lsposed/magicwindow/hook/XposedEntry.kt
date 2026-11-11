package com.github.lsposed.magicwindow.hook

import android.util.Log
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
        log(Log.INFO, TAG, "模块已加载 | 进程=${param.processName} | 框架=$frameworkName " +
            "$frameworkVersion | API=$apiVersion")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        XLog.i("注入 system_server")
        runCatching { SystemServerHooks.install(this, param.classLoader) }
            .onFailure { XLog.e("system_server 注入失败", it) }
    }

    /**
     * 热重载未启用（`module.prop` 不声明 `autoHotReload`）：本模块的钩子只在 system_server 启动时
     * 安装一次，重新执行会与已缓存的插件实例/云控状态错位，因此显式拒绝。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        XLog.i("收到热重载请求，本模块不支持，已忽略")
        return false
    }

    private companion object {
        const val TAG = "MagicWindow"
    }
}
