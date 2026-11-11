package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.Reflect
import com.github.lsposed.magicwindow.hook.XLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker

/**
 * 第 0 步：三个 SystemProperty 总开关的校验与兜底。
 *
 * piano 出厂即为 true，正常路径只做一次性日志确认；
 * 为 false 时才对 ro. 属性做 hook 兜底（ro. 属性无法 setprop）。
 *
 * ⚠️ 不改写 IS_TABLET / ro.build.characteristics —— 会让系统走未测试的平板分支。
 */
object PropertyGate {

    fun apply(xposed: XposedInterface, classLoader: ClassLoader) {
        val ae = getBool(classLoader, Constants.PROP_ACTIVITY_EMBEDDING)
        val autoUi = getBool(classLoader, Constants.PROP_AUTO_UI)
        val characteristics = getString(classLoader, Constants.PROP_CHARACTERISTICS)
        XLog.i("总开关校验：AE=$ae autoUI=$autoUi characteristics=$characteristics")

        if (!ae) {
            runCatching {
                val clazz = Reflect.findClass(Constants.CLASS_AE_PROP, classLoader)
                Reflect.findMethods(clazz, Constants.M_IS_AE_ENABLED).forEachIndexed { i, m ->
                    xposed.hook(m).setId("ae_prop_$i")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { true })
                }
                XLog.i("已兜底：ActivityEmbeddingProp.isActivityEmbeddingEnabled -> true")
            }.onFailure { XLog.e("兜底 isActivityEmbeddingEnabled 失败", it) }
        }

        if (!autoUi) {
            runCatching {
                val stub = Reflect.findClass(Constants.CLASS_AUTO_UI_MANAGER_STUB, classLoader)
                Reflect.setStaticBooleanField(stub, Constants.F_IS_AUTO_UI_ENABLED, true)
                XLog.i("已兜底：MIUIAutoUIManagerStub.IS_AUTO_UI_ENABLED -> true")
            }.onFailure {
                XLog.e("兜底 IS_AUTO_UI_ENABLED 失败（可 setprop persist.miui.auto_ui_enable true）", it)
            }
        }
    }

    private fun systemProperties(cl: ClassLoader): Class<*> =
        Reflect.findClass("android.os.SystemProperties", cl)

    private fun getBool(cl: ClassLoader, key: String): Boolean = runCatching {
        Reflect.callStatic(systemProperties(cl), "getBoolean", key, false) as Boolean
    }.getOrDefault(false)

    private fun getString(cl: ClassLoader, key: String): String = runCatching {
        Reflect.callStatic(systemProperties(cl), "get", key, "") as String
    }.getOrDefault("")
}
