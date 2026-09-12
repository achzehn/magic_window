package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers

/**
 * 第 0 步：三个 SystemProperty 总开关的校验与兜底。
 *
 * piano 出厂即为 true，正常路径只做一次性日志确认；
 * 为 false 时才对 ro. 属性做 hook 兜底（ro. 属性无法 setprop）。
 *
 * ⚠️ 不改写 IS_TABLET / ro.build.characteristics —— 会让系统走未测试的平板分支。
 */
object PropertyGate {

    fun apply(classLoader: ClassLoader) {
        val ae = getBool(classLoader, Constants.PROP_ACTIVITY_EMBEDDING)
        val autoUi = getBool(classLoader, Constants.PROP_AUTO_UI)
        val characteristics = getString(classLoader, Constants.PROP_CHARACTERISTICS)
        XLog.i("总开关校验：AE=$ae autoUI=$autoUi characteristics=$characteristics")

        if (!ae) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    Constants.CLASS_AE_PROP, classLoader,
                    Constants.M_IS_AE_ENABLED,
                    XC_MethodReplacement.returnConstant(true)
                )
                XLog.i("已兜底：ActivityEmbeddingProp.isActivityEmbeddingEnabled -> true")
            }.onFailure { XLog.e("兜底 isActivityEmbeddingEnabled 失败", it) }
        }

        if (!autoUi) {
            runCatching {
                val stub = XposedHelpers.findClass(Constants.CLASS_AUTO_UI_MANAGER_STUB, classLoader)
                XposedHelpers.setStaticBooleanField(stub, Constants.F_IS_AUTO_UI_ENABLED, true)
                XLog.i("已兜底：MIUIAutoUIManagerStub.IS_AUTO_UI_ENABLED -> true")
            }.onFailure {
                XLog.e("兜底 IS_AUTO_UI_ENABLED 失败（可 setprop persist.miui.auto_ui_enable true）", it)
            }
        }
    }

    private fun systemProperties(cl: ClassLoader): Class<*> =
        XposedHelpers.findClass("android.os.SystemProperties", cl)

    private fun getBool(cl: ClassLoader, key: String): Boolean = runCatching {
        XposedHelpers.callStaticMethod(
            systemProperties(cl), "getBoolean", key, false
        ) as Boolean
    }.getOrDefault(false)

    private fun getString(cl: ClassLoader, key: String): String = runCatching {
        XposedHelpers.callStaticMethod(systemProperties(cl), "get", key, "") as String
    }.getOrDefault("")
}
