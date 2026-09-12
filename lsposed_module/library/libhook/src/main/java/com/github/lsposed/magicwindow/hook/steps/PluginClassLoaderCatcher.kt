package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 第一步：捕获 HyperOS Stub 插件的 ClassLoader（资料评估 6.4-1）。
 *
 * `MiuiSystemEmbeddedRule` / `MiuiParsingEmbeddedRule` / `MiuiSystemAutoUIRule` 等类为包级私有，
 * 由 `/system_ext/framework` 下的插件 jar 动态加载，system_server 默认 ClassLoader 中找不到。
 *
 * 捕获策略（逐级降级）：
 *  1. 直接用 system_server ClassLoader 试探（部分 ROM 已合入 boot classpath）；
 *  2. hook `MiuiEmbeddingWindowServiceImplCollector` 的全部方法，从返回的实现实例取 ClassLoader；
 *  3. hook `MiuiEmbeddingWindowServiceLoader` 的全部方法，同上。
 */
object PluginClassLoaderCatcher {

    @Volatile
    private var pluginClassLoader: ClassLoader? = null

    private val pending = mutableListOf<(ClassLoader) -> Unit>()

    /**
     * 为捕获而挂上的钩子。这两个类的方法在系统里调用很频繁，
     * 一旦拿到 ClassLoader 就必须全部摘掉，否则会一直白白付出 hook 的调用开销，拖慢开机。
     */
    private val catcherHooks =
        java.util.Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    /** 注册一个「拿到插件 ClassLoader 后执行」的回调，可能同步立即执行。 */
    @Synchronized
    fun whenReady(action: (ClassLoader) -> Unit) {
        val cl = pluginClassLoader
        if (cl != null) runCatching { action(cl) }.onFailure { XLog.e("插件回调执行失败", it) }
        else pending += action
    }

    fun start(systemServerClassLoader: ClassLoader) {
        // 策略 1
        if (probe(systemServerClassLoader)) {
            publish(systemServerClassLoader, "system_server ClassLoader 直接可见")
            return
        }
        // 策略 2 / 3
        hookAllMethods(systemServerClassLoader, Constants.CLASS_EWS_IMPL_COLLECTOR)
        hookAllMethods(systemServerClassLoader, Constants.CLASS_EWS_LOADER)
    }

    private fun probe(cl: ClassLoader): Boolean = runCatching {
        XposedHelpers.findClass(Constants.CLASS_SYSTEM_EMBEDDED_RULE, cl)
        true
    }.getOrDefault(false)

    private fun hookAllMethods(systemServerClassLoader: ClassLoader, className: String) {
        val clazz = runCatching {
            XposedHelpers.findClass(className, systemServerClassLoader)
        }.getOrElse { return }

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (pluginClassLoader != null) return
                val candidate = (param.result ?: param.thisObject) ?: return
                val cl = candidate.javaClass.classLoader ?: return
                if (cl === systemServerClassLoader) return
                if (!probe(cl)) return
                publish(cl, "由 ${className.substringAfterLast('.')}.${param.method.name} 捕获")
            }
        }

        var count = 0
        clazz.declaredMethods.forEach { m ->
            runCatching { catcherHooks += XposedBridge.hookMethod(m, hook); count++ }
        }
        clazz.declaredConstructors.forEach { c ->
            runCatching { catcherHooks += XposedBridge.hookMethod(c, hook); count++ }
        }
    }

    @Synchronized
    private fun publish(cl: ClassLoader, from: String) {
        if (pluginClassLoader != null) return
        pluginClassLoader = cl
        XLog.i("插件 ClassLoader 已就绪（$from）")

        // 使命完成，立刻摘钩，不再干扰系统热路径
        val hooks = catcherHooks.toList()
        catcherHooks.clear()
        hooks.forEach { runCatching { it.unhook() } }
        if (hooks.isNotEmpty()) XLog.i("已摘除 ${hooks.size} 个捕获用钩子")

        val actions = pending.toList()
        pending.clear()
        actions.forEach { action ->
            runCatching { action(cl) }.onFailure { XLog.e("插件回调执行失败", it) }
        }
    }
}
