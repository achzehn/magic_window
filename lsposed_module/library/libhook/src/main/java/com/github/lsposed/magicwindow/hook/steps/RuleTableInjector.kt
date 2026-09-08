package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 第三步（B 档）：规则表直改（资料评估 4.5.3 B 档 / 6.3）。
 *
 * 两个落点：
 *   1. `MiuiFixedOrientationController.getFixOrientationRules()` @L207 返回的是**内部表引用**，
 *      在 after 里直接 `put()` 就能把自定义包名塞进固定横屏规则表；
 *      规则对象结构随机型变化，故采用「克隆表内已有条目再改包名」的做法，不硬编码构造签名。
 *   2. `MiuiSystemEmbeddedRule.clearEmbeddedRule(String)` @L1350 会在懒加载/包变更时
 *      把规则从 `mPackageRules` 移除，对我们注入的包直接拦掉，避免被系统清理（4.11 注意点 3）。
 *
 * 依赖机型内部结构，属于加分项，由 `ruleTableInject` 开关控制。
 */
object RuleTableInjector {

    /** 还没塞进规则表的包名。空集合意味着无事可做，热路径可以立刻返回 */
    @Volatile
    private var pendingPkgs: Set<String> = emptySet()

    /** getFixOrientationRules 的钩子，注入齐了就摘掉 */
    private val fixOriHooks =
        java.util.Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    fun apply(pluginClassLoader: ClassLoader) {
        val config = RuleStore.global()
        if (!config.ruleTableInject) {
            XLog.i("B 档规则表注入已关闭")
            return
        }

        pendingPkgs = RuleStore.activeRules()
            .map { it.packageName }
            .filter { RuleStore.hasFixedOrientationRule(it) }
            .toSet()

        // 没有固定横屏规则就别挂钩：getFixOrientationRules 是个高频 getter，
        // 空挂一个钩子等于给整个窗口管理链路加常量开销。
        if (pendingPkgs.isEmpty()) {
            XLog.i("无固定横屏规则，跳过 B 档规则表注入")
        } else {
            hookFixOrientationRules(pluginClassLoader)
        }

        // clearEmbeddedRule 的拦截由 UserSettingSwitcher 统一负责，那边是幂等的，
        // 这里只管调用，避免同一个方法被挂两遍钩子。
        runCatching { UserSettingSwitcher.preventClear(pluginClassLoader) }
            .onFailure { XLog.e("挂钩 ${Constants.M_CLEAR_EMBEDDED_RULE} 失败", it) }
    }

    private fun hookFixOrientationRules(cl: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(Constants.CLASS_FIXED_ORI_CONTROLLER, cl)
            val targets = clazz.declaredMethods.filter {
                it.name == Constants.M_GET_FIX_ORI_RULES && it.parameterTypes.isEmpty()
            }
            if (targets.isEmpty()) {
                XLog.d("未找到 ${Constants.M_GET_FIX_ORI_RULES}")
                return
            }
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (pendingPkgs.isEmpty()) return // 绝大多数调用在这里就走完了
                    @Suppress("UNCHECKED_CAST")
                    val map = param.result as? MutableMap<String, Any> ?: return
                    inject(map)
                }
            }
            targets.forEach { fixOriHooks += XposedBridge.hookMethod(it, hook) }
            XLog.i("已挂钩 MiuiFixedOrientationController.${Constants.M_GET_FIX_ORI_RULES}")
        }.onFailure { XLog.e("挂钩 ${Constants.M_GET_FIX_ORI_RULES} 失败", it) }
    }

    /** 把需要固定横屏的包名补进内部规则表；表里没有任何模板条目时静默跳过 */
    private fun inject(map: MutableMap<String, Any>) {
        val wanted = pendingPkgs.filterNot { map.containsKey(it) }
        if (wanted.isEmpty()) {
            finish()
            return
        }

        val template = map.values.firstOrNull() ?: return
        val templatePkg = map.entries.first().key
        val done = HashSet<String>()
        wanted.forEach { pkg ->
            val cloned = clone(template, templatePkg, pkg)
            if (cloned != null) {
                map[pkg] = cloned
                done += pkg
                XLog.i("已注入固定横屏规则表：$pkg")
            }
        }
        if (done.isNotEmpty()) pendingPkgs = pendingPkgs - done
        if (pendingPkgs.isEmpty()) finish()
    }

    /** 全部注入完毕，摘掉这个高频钩子 */
    private fun finish() {
        pendingPkgs = emptySet()
        val hooks = fixOriHooks.toList()
        if (hooks.isEmpty()) return
        fixOriHooks.clear()
        hooks.forEach { runCatching { it.unhook() } }
        XLog.i("固定横屏规则表已注入完毕，已摘钩")
    }

    /**
     * 克隆规则对象：按模板类新建实例后逐字段拷贝，
     * 再把值等于模板包名的字符串字段换成目标包名。
     */
    private fun clone(template: Any, templatePkg: String, pkg: String): Any? = runCatching {
        val clazz = template.javaClass
        val ctor = clazz.declaredConstructors
            .sortedBy { it.parameterTypes.size }
            .firstOrNull() ?: return null
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { type ->
            when {
                type == String::class.java -> pkg
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }.toTypedArray()
        val instance = ctor.newInstance(*args)

        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredFields.forEach { f ->
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) return@forEach
                f.isAccessible = true
                runCatching {
                    val v = f.get(template)
                    f.set(instance, if (v == templatePkg) pkg else v)
                }
            }
            c = c.superclass
        }
        instance
    }.onFailure { XLog.e("克隆固定横屏规则失败：$pkg", it) }.getOrNull()
}
