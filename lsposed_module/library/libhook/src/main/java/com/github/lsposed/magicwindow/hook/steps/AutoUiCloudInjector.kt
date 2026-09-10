package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 第二步：autoui 云控注入（方案 C，资料评估 4.6.3 / 4.6.4 / 4.9.1 / 4.9.3 / 4.9.4 / 4.10.3）。
 *
 * 全链路 public API：
 *   1. 构造 `Map<String, PackageRule>`（7 参构造，注意 super 参数错位）
 *   2. `MiuiParsingAutoUI.updateAutoUICloudConfigFile("cloudFeature_autoui_list.xml", map)` 落盘
 *   3. `setStaticLongField(mLastCloudConfigVersion, >360816)` —— 该字段每次开机归零，必须每次重设
 *   4. `MiuiSystemAutoUIRule.updateAutoUIConfigFromCloudFile()` 热重载
 *
 * 时机：hook `MiuiParsingAutoUI.loadPackage(MiuiSystemAutoUIRule)` 的 before，
 * 先落盘再抬版本，系统紧接着的读取就会走 `/data/system/cloudFeature_autoui_list.xml`，
 * 完全旁路只读的 `/product/etc/autoui_list.xml`。
 */
object AutoUiCloudInjector {

    private const val EXTRA_INJECTED = "magic_window_autoui_injected"

    /** 「无规则可注入」只提示一次，避免刷满日志 */
    @Volatile
    private var emptyLogged = false

    /** 上次成功落盘的内容指纹。内容没变就不重复写 XML，省下开机时的磁盘 IO */
    @Volatile
    private var lastFingerprint: String? = null

    /** 缓存的解析类与 system rule 实例：配置变更后由 [injectNow] 重新落盘 + 热重载，无需再 hook */
    @Volatile
    private var parsingClassRef: Class<*>? = null

    @Volatile
    private var systemRuleRef: Any? = null

    @Volatile
    private var systemServerCl: ClassLoader? = null

    fun apply(pluginClassLoader: ClassLoader, systemServerClassLoader: ClassLoader) {
        systemServerCl = systemServerClassLoader
        val config = RuleStore.global()
        if (!config.autoUiCloudInject) {
            XLog.i("autoui 云控注入已关闭")
            return
        }

        val parsingClass = runCatching {
            XposedHelpers.findClass(Constants.CLASS_PARSING_AUTO_UI, pluginClassLoader)
        }.getOrElse {
            XLog.e("未找到 MiuiParsingAutoUI，跳过 autoui 注入", it)
            return
        }
        parsingClassRef = parsingClass

        runCatching {
            XposedHelpers.findAndHookMethod(
                parsingClass, Constants.M_LOAD_PACKAGE,
                XposedHelpers.findClass(Constants.CLASS_SYSTEM_AUTO_UI_RULE, pluginClassLoader),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args.getOrNull(0)?.let { systemRuleRef = it }
                        if (inject(parsingClass, systemServerClassLoader)) {
                            param.setObjectExtra(EXTRA_INJECTED, true)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 只有本次确实落盘成功才热重载，避免每次 loadPackage 都刷日志
                        if (param.getObjectExtra(EXTRA_INJECTED) != true) return
                        val rule = param.args.getOrNull(0) ?: return
                        runCatching {
                            XposedHelpers.callMethod(rule, Constants.M_UPDATE_FROM_CLOUD_FILE)
                            XLog.i("已触发 updateAutoUIConfigFromCloudFile")
                        }.onFailure { XLog.e("触发热重载失败", it) }
                    }
                }
            )
            XLog.i("已挂钩 MiuiParsingAutoUI.loadPackage")
        }.onFailure { XLog.e("挂钩 loadPackage 失败", it) }

        // SettingsCloudData 位于 miui-framework（framework 侧），要用 system_server ClassLoader 查找
        if (config.hookCloudDataString) hookCloudDataString(systemServerClassLoader)
    }

    /** 配置保存后由 RuleStore.onChange 调用：用缓存的解析类与 system rule 重新落盘并热重载。 */
    fun injectNow() {
        if (!RuleStore.global().autoUiCloudInject) return
        val parsingClass = parsingClassRef ?: return
        val cl = systemServerCl ?: return
        runCatching {
            if (inject(parsingClass, cl)) {
                systemRuleRef?.let { rule ->
                    XposedHelpers.callMethod(rule, Constants.M_UPDATE_FROM_CLOUD_FILE)
                    XLog.i("配置变更：已触发 autoui 热重载")
                }
            }
        }.onFailure { XLog.e("autoui 热更新失败", it) }
    }

    /** @return 本次是否真的把云控文件落盘成功 */
    private fun inject(parsingClass: Class<*>, systemServerClassLoader: ClassLoader): Boolean {
        val rules = RuleStore.autoUiRules()
        val version = RuleStore.global().cloudDataVersion

        if (rules.isEmpty()) {
            if (!emptyLogged) {
                emptyLogged = true
                XLog.i("无 autoui 规则，跳过落盘（后续不再重复提示）")
            }
            return false
        }
        emptyLogged = false
        if (version <= Constants.LOCAL_AUTO_UI_DATA_VERSION) {
            XLog.e("cloudDataVersion=$version 未超过本地 ${Constants.LOCAL_AUTO_UI_DATA_VERSION}，注入不会生效")
            return false
        }

        val packageRuleClass = runCatching {
            XposedHelpers.findClass(Constants.CLASS_AUTO_UI_PACKAGE_RULE, systemServerClassLoader)
        }.getOrElse {
            XLog.e("未找到 PackageRule", it)
            return false
        }

        // 内容与上次落盘一致就不再写文件。
        // 但版本号字段每次开机归零，所以仍要确保它是抬高过的。
        val fingerprint = fingerprintOf(version)
        if (fingerprint == lastFingerprint) {
            raiseVersion(parsingClass, version, log = false)
            return false
        }

        val map = HashMap<String, Any>()
        rules.forEach { rule ->
            runCatching {
                // 7 参：pkg, enable, activityRule, skippedActivityRule,
                //       versionCode, optimizeWebView, skippedAppConfigChange
                map[rule.packageName] = XposedHelpers.newInstance(
                    packageRuleClass,
                    rule.packageName,
                    "true",
                    rule.autoUiActivityRule.ifEmpty { null },
                    rule.autoUiSkippedActivityRule.ifEmpty { null },
                    rule.autoUiVersionCode.ifEmpty { "1" },
                    rule.autoUiOptimizeWebView.toString(),
                    rule.autoUiSkippedAppConfigChange.ifEmpty { null }
                )
            }.onFailure { XLog.e("构造 PackageRule 失败：${rule.packageName}", it) }
        }
        if (map.isEmpty()) return false

        runCatching {
            XposedHelpers.callStaticMethod(
                parsingClass, Constants.M_UPDATE_CLOUD_CONFIG_FILE,
                Constants.CLOUD_AUTO_UI_FILE, map
            )
            XLog.i("已落盘 ${Constants.CLOUD_AUTO_UI_FILE}，共 ${map.size} 条")
        }.onFailure {
            XLog.e("落盘 autoui 云控文件失败", it)
            return false
        }
        lastFingerprint = fingerprint

        raiseVersion(parsingClass, version, log = true)
        return true
    }

    /** 规则内容指纹：只要参与落盘的字段没变，就认为不需要重写文件 */
    private fun fingerprintOf(version: Long): String = buildString {
        append(version)
        RuleStore.autoUiRules()
            .sortedBy { it.packageName }
            .forEach { r ->
                append('\n').append(r.packageName)
                    .append('\u0001').append(r.autoUiActivityRule)
                    .append('\u0001').append(r.autoUiSkippedActivityRule)
                    .append('\u0001').append(r.autoUiVersionCode)
                    .append('\u0001').append(r.autoUiOptimizeWebView)
                    .append('\u0001').append(r.autoUiSkippedAppConfigChange)
            }
    }

    /** 抬高云控版本号。该字段每次开机归零，所以每轮都要确保它是高的 */
    private fun raiseVersion(parsingClass: Class<*>, version: Long, log: Boolean) {
        runCatching {
            XposedHelpers.setStaticLongField(
                parsingClass, Constants.F_LAST_CLOUD_CONFIG_VERSION, version
            )
            if (log) XLog.i("已抬高 mLastCloudConfigVersion=$version")
        }.onFailure { XLog.e("抬高云控版本失败", it) }
    }

    /**
     * 更贴近原生的替代做法（4.9.5）：让系统自己把版本号写进去，
     * 避免直接改 protected static 字段。默认关闭。
     */
    private fun hookCloudDataString(cl: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(Constants.CLASS_SETTINGS_CLOUD_DATA, cl)
            clazz.declaredMethods
                .filter { it.name == Constants.M_GET_CLOUD_DATA_STRING }
                .forEach { m ->
                    de.robv.android.xposed.XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val module = param.args.getOrNull(1) as? String ?: return
                            val key = param.args.getOrNull(2) as? String ?: return
                            if (module == Constants.AUTO_UI_CONTROL_MODULE &&
                                key == Constants.CLOUD_KEY_DATA_VERSION
                            ) {
                                param.result = RuleStore.global().cloudDataVersion.toString()
                            }
                        }
                    })
                }
            XLog.i("已挂钩 SettingsCloudData.getCloudDataString")
        }.onFailure { XLog.e("挂钩 getCloudDataString 失败", it) }
    }
}
