package com.github.lsposed.magicwindow.hook

import com.github.lsposed.magicwindow.common.Constants
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed 入口。作用域见 res/values/arrays.xml：
 *   - `android`（system_server）：全部注入逻辑
 *   - 模块自身：仅用于回显「已激活」状态
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            Constants.SYSTEM_PACKAGE -> {
                if (lpparam.processName != Constants.SYSTEM_PACKAGE) return
                XLog.i("注入 system_server")
                SystemServerHooks.install(lpparam.classLoader)
            }

            Constants.MODULE_PACKAGE -> {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        Constants.CLASS_MODULE_STATUS, lpparam.classLoader,
                        Constants.M_IS_MODULE_ACTIVE,
                        XC_MethodReplacement.returnConstant(true)
                    )
                }
            }
        }
    }
}
