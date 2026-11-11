package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.Reflect
import com.github.lsposed.magicwindow.hook.XLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable

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
        java.util.Collections.synchronizedList(mutableListOf<HookHandle>())

    /** 注册一个「拿到插件 ClassLoader 后执行」的回调，可能同步立即执行。 */
    @Synchronized
    fun whenReady(action: (ClassLoader) -> Unit) {
        val cl = pluginClassLoader
        if (cl != null) runCatching { action(cl) }.onFailure { XLog.e("插件回调执行失败", it) }
        else pending += action
    }

    fun start(xposed: XposedInterface, systemServerClassLoader: ClassLoader) {
        // 策略 1
        if (probe(systemServerClassLoader)) {
            publish(systemServerClassLoader, "system_server ClassLoader 直接可见")
            return
        }
        // 策略 2 / 3
        hookAllMethods(xposed, systemServerClassLoader, Constants.CLASS_EWS_IMPL_COLLECTOR)
        hookAllMethods(xposed, systemServerClassLoader, Constants.CLASS_EWS_LOADER)
    }

    /**
     * 同一 ClassLoader 只探测一次：ClassNotFoundException 的构造成本远高于普通调用，
     * 捕获前的每次拦截都重复探测会明显拖慢系统热路径。
     */
    private val probeCache =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<ClassLoader, Boolean>())

    private fun probe(cl: ClassLoader): Boolean {
        probeCache[cl]?.let { return it }
        val ok = runCatching {
            Reflect.findClass(Constants.CLASS_SYSTEM_EMBEDDED_RULE, cl)
            true
        }.getOrDefault(false)
        probeCache[cl] = ok
        return ok
    }

    private fun hookAllMethods(
        xposed: XposedInterface,
        systemServerClassLoader: ClassLoader,
        className: String
    ) {
        val clazz = runCatching {
            Reflect.findClass(className, systemServerClassLoader)
        }.getOrElse { return }

        var count = 0
        // 不挂钩构造方法：API 102 下构造方法的 thisObject 为 null、返回值被框架忽略，
        // 拿不到实例，只会白白付出拦截开销
        val executables: List<Executable> = clazz.declaredMethods.toList()
        executables.forEachIndexed { index, e ->
            runCatching {
                e.isAccessible = true
                // 拦截链模型没有 after 回调：先放行原方法，再从返回值 / this 上取 ClassLoader
                val handle = xposed.hook(e).setId("$className#$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val result = chain.proceed()
                        if (pluginClassLoader == null) {
                            val candidate = result ?: chain.thisObject
                            val cl = candidate?.javaClass?.classLoader
                            if (cl != null && cl !== systemServerClassLoader && probe(cl)) {
                                publish(cl, "由 ${className.substringAfterLast('.')} 捕获")
                            }
                        }
                        result
                    })
                catcherHooks += handle
                count++
            }
        }
        if (count == 0) XLog.e("未挂上 $className 的捕获钩子")
    }

    @Synchronized
    private fun publish(cl: ClassLoader, from: String) {
        if (pluginClassLoader != null) return
        pluginClassLoader = cl
        XLog.i("插件 ClassLoader 已就绪（$from）")

        // 使命完成，立刻摘钩，不再干扰系统热路径
        val hooks = synchronized(catcherHooks) { catcherHooks.toList().also { catcherHooks.clear() } }
        hooks.forEach { runCatching { it.unhook() } }
        if (hooks.isNotEmpty()) XLog.i("已摘除 ${hooks.size} 个捕获用钩子")

        val actions = pending.toList()
        pending.clear()
        actions.forEach { action ->
            runCatching { action(cl) }.onFailure { XLog.e("插件回调执行失败", it) }
        }
    }
}
