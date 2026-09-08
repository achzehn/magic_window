package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 第四步：用户态开关翻转（资料评估 4.10.5 / 4.10.6 / 6.3）。
 *
 * `/data/system/users/<uid>/embedded_setting_config.xml` 共 8411 条 `<setting>`，
 * 规则侧其实齐备，缺的只是「开关没打开」。
 *
 * 实现方式为**内存态覆盖**而非改文件 —— 6.3 明确指出系统内存中已有一份，
 * 直接改文件会被下次写回覆盖。落点选在含用户开关判定的三个查询上：
 *   `isEmbeddingEnabledForPackage` @L2209
 *   `isEmbeddingEnabledForPackageIncludeAdaptApp` @L2230
 *   `queryEmbeddingEnableFormRulesList` @L2665
 * 并 hook `clearEmbeddedRule` @L1350 阻止懒加载重解析时清掉已生效的包（6.4-3）。
 */
object UserSettingSwitcher {

    private val clearHooked = java.util.concurrent.atomic.AtomicBoolean(false)

    fun apply(pluginClassLoader: ClassLoader) {
        if (!RuleStore.global().flipUserSwitches) {
            XLog.i("用户开关翻转已关闭")
            return
        }

        // 注意：这里不能因为「当前一条规则都没有」就跳过挂钩。
        // 注入只在开机时执行一次，跳过之后用户新增的规则将永远无法生效（除非重启手机）。

        val decide: (String) -> Boolean? = { pkg ->
            when {
                RuleStore.hasEmbeddingRule(pkg) -> true
                // 互斥：切到固定横屏/全屏时，必须把平行窗口开关压下去
                RuleStore.ruleOf(pkg) != null -> false
                else -> null
            }
        }

        listOf(
            Constants.M_IS_EMBEDDING_ENABLED,
            Constants.M_IS_EMBEDDING_ENABLED_INCL_ADAPT,
            Constants.M_QUERY_EMBEDDING_FROM_RULES
        ).forEach { name ->
            EmbeddingQueryOverride.hookAllByName(
                pluginClassLoader, Constants.CLASS_SYSTEM_EMBEDDED_RULE, name, decide
            )
        }

        preventClear(pluginClassLoader)
    }

    /**
     * 阻止已配置的包在懒加载重解析时被清空（6.4-3）。
     *
     * B 档规则表注入也依赖这层保护，所以做成幂等的：谁先调用谁挂，绝不重复挂第二遍。
     */
    internal fun preventClear(cl: ClassLoader) {
        if (!clearHooked.compareAndSet(false, true)) return
        runCatching {
            val clazz = XposedHelpers.findClass(Constants.CLASS_SYSTEM_EMBEDDED_RULE, cl)
            val targets = clazz.declaredMethods.filter {
                it.name == Constants.M_CLEAR_EMBEDDED_RULE &&
                        it.parameterTypes.firstOrNull() == String::class.java
            }
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.getOrNull(0) as? String ?: return
                    if (RuleStore.ruleOf(pkg) != null) {
                        param.result = null
                        XLog.d { "拦截 clearEmbeddedRule($pkg)" }
                    }
                }
            }
            targets.forEach { XposedBridge.hookMethod(it, hook) }
            if (targets.isNotEmpty()) XLog.i("已挂钩 clearEmbeddedRule ×${targets.size}")
        }.onFailure { XLog.e("挂钩 clearEmbeddedRule 失败", it) }
    }
}
