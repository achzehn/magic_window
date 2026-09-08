package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.WindowMode
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 第三步（A 档）：白名单查询覆盖（资料评估 4.5.3 / 6.3）。
 *
 * `isEmbeddingListedForPackage` @L4662 与 `isFixedOrientationListedForPackage` @L4726
 * 是三套机制的必经出口，天然绕过 4.5.4 的「按包懒加载」问题；
 * 同时用于覆盖 `fixed_orientation_list.xml` 中被小米标记 `disable="true"` 的 197 条规则。
 *
 * ⚠️ 依据 4.5.5 的互斥优先级，两个查询必须互斥返回，否则固定横屏会吞掉平行窗口。
 */
object EmbeddingQueryOverride {

    fun apply(pluginClassLoader: ClassLoader) {
        val config = RuleStore.global()
        if (!config.queryHookFallback) {
            XLog.i("A 档查询 hook 已关闭")
            return
        }

        // 注意：这里不能因为「当前一条规则都没有」就跳过挂钩。
        // 注入只在开机时执行一次，跳过之后用户新增的规则将永远无法生效（除非重启手机）。
        // 钩子内部第一步就是一次 HashMap 查询后放行，未配置的包开销可忽略。

        hook(pluginClassLoader, Constants.CLASS_EWS, Constants.M_IS_EMBEDDING_LISTED) { pkg ->
            if (RuleStore.hasEmbeddingRule(pkg)) true else null
        }
        hook(pluginClassLoader, Constants.CLASS_EWS, Constants.M_IS_FIXED_ORI_LISTED) { pkg ->
            when {
                RuleStore.hasFixedOrientationRule(pkg) -> true
                // 互斥：命中平行窗口时必须显式压掉固定横屏，否则被高优先级吞掉
                RuleStore.hasEmbeddingRule(pkg) -> false
                else -> null
            }
        }

        // 实际实现侧，覆盖系统 disable="true"
        if (config.overrideSystemDisable) {
            hook(
                pluginClassLoader,
                Constants.CLASS_FIXED_ORI_CONTROLLER,
                Constants.M_IS_FIXED_ORI_LISTED
            ) { pkg ->
                val rule = RuleStore.ruleOf(pkg)
                when {
                    rule != null && RuleStore.hasFixedOrientationRule(pkg) && rule.foOverrideDisable -> true
                    RuleStore.hasEmbeddingRule(pkg) -> false
                    else -> null
                }
            }
        }

        // B 档兜底：连「当前是否已开启」的各处查询也一并谎报，覆盖懒加载与自适配分支
        if (config.enableQueryHook) {
            val embDecide: (String) -> Boolean? = { pkg ->
                when {
                    RuleStore.hasEmbeddingRule(pkg) -> true
                    RuleStore.hasFixedOrientationRule(pkg) -> false
                    else -> null
                }
            }
            listOf(
                Constants.CLASS_EWS to Constants.M_IS_EMBEDDING_ENABLED,
                Constants.CLASS_EWS to Constants.M_IS_EMBEDDING_ENABLED_INCL_ADAPT,
                Constants.CLASS_SYSTEM_EMBEDDED_RULE to Constants.M_IS_EMBEDDING_ENABLED,
                Constants.CLASS_SYSTEM_EMBEDDED_RULE to Constants.M_IS_EMBEDDING_ENABLED_INCL_ADAPT,
                Constants.CLASS_SYSTEM_EMBEDDED_RULE to Constants.M_QUERY_EMBEDDING_FROM_RULES,
                Constants.CLASS_SYSTEM_EMBEDDED_RULE to Constants.M_HAS_PACKAGE_RULE
            ).forEach { (clazz, method) ->
                hookAllByName(pluginClassLoader, clazz, method, embDecide)
            }
        }
    }

    /** decide 返回 null 表示不干预，保持系统原返回值 */
    private fun hook(
        cl: ClassLoader,
        className: String,
        methodName: String,
        decide: (String) -> Boolean?
    ) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, cl)
            XposedHelpers.findAndHookMethod(
                clazz, methodName, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pkg = param.args.getOrNull(0) as? String ?: return
                        // 绝大多数调用都是没配置过的包，一次 HashMap 查询就放行
                        if (RuleStore.modeOf(pkg) == WindowMode.OFF) return
                        val decision = decide(pkg) ?: return
                        if (param.result != decision) {
                            param.result = decision
                            XLog.d { "$methodName($pkg): ${!decision} -> $decision" }
                        }
                    }
                }
            )
            XLog.i("已挂钩 ${className.substringAfterLast('.')}.$methodName")
        }.onFailure { XLog.e("挂钩 $className.$methodName 失败", it) }
    }

    /** 通用工具：hook 指定类中所有同名且首参为 String 的方法 */
    internal fun hookAllByName(
        cl: ClassLoader,
        className: String,
        methodName: String,
        decide: (String) -> Boolean?
    ) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, cl)
            val targets = clazz.declaredMethods.filter {
                it.name == methodName &&
                        it.parameterTypes.firstOrNull() == String::class.java &&
                        (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == java.lang.Boolean::class.java)
            }
            if (targets.isEmpty()) {
                XLog.d("$className 中未找到 $methodName")
                return
            }
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.getOrNull(0) as? String ?: return
                    if (RuleStore.modeOf(pkg) == WindowMode.OFF) return
                    val decision = decide(pkg) ?: return
                    if (param.result != decision) param.result = decision
                }
            }
            targets.forEach { XposedBridge.hookMethod(it, hook) }
            XLog.i("已挂钩 ${className.substringAfterLast('.')}.$methodName ×${targets.size}")
        }.onFailure { XLog.e("挂钩 $className.$methodName 失败", it) }
    }
}
