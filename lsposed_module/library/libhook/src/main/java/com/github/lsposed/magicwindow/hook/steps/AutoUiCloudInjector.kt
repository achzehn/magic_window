package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.CloudXmlCodec
import com.github.lsposed.magicwindow.hook.Reflect
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker

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

    fun apply(xposed: XposedInterface, pluginClassLoader: ClassLoader, systemServerClassLoader: ClassLoader) {
        systemServerCl = systemServerClassLoader

        val parsingClass = runCatching {
            Reflect.findClass(Constants.CLASS_PARSING_AUTO_UI, pluginClassLoader)
        }.getOrElse {
            XLog.e("未找到 MiuiParsingAutoUI，跳过 autoui 注入", it)
            return
        }
        parsingClassRef = parsingClass

        runCatching {
            val systemRuleClass = Reflect.findClass(Constants.CLASS_SYSTEM_AUTO_UI_RULE, pluginClassLoader)
            val loadPackage = Reflect.method(parsingClass, Constants.M_LOAD_PACKAGE, 1)
            if (loadPackage.parameterTypes[0] != systemRuleClass) {
                XLog.e("loadPackage 参数类型不是 MiuiSystemAutoUIRule（实际 ${loadPackage.parameterTypes[0]}），跳过挂钩")
                return
            }
            xposed.hook(loadPackage).setId("autoui_loadPackage")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val rule = chain.getArg(0)
                    if (rule != null) systemRuleRef = rule
                    // before：先落盘再抬高版本，系统紧接着的读取就会走云控文件
                    val injected = inject(parsingClass, systemServerClassLoader)
                    val result = chain.proceed()
                    // after：只有本次确实落盘成功才热重载，避免每次 loadPackage 都刷日志
                    if (injected && rule != null) {
                        runCatching {
                            Reflect.call(rule, Constants.M_UPDATE_FROM_CLOUD_FILE)
                            XLog.i("已触发 updateAutoUIConfigFromCloudFile")
                        }.onFailure { XLog.e("触发热重载失败", it) }
                    }
                    result
                })
            XLog.i("已挂钩 MiuiParsingAutoUI.loadPackage")
        }.onFailure { XLog.e("挂钩 loadPackage 失败", it) }
    }

    /** 配置保存后由 RuleStore.onChange 调用：用缓存的解析类与 system rule 重新落盘并热重载。 */
    fun injectNow() {
        val parsingClass = parsingClassRef ?: return
        val cl = systemServerCl ?: return
        runCatching {
            if (inject(parsingClass, cl)) {
                systemRuleRef?.let { rule ->
                    Reflect.call(rule, Constants.M_UPDATE_FROM_CLOUD_FILE)
                    XLog.i("配置变更：已触发 autoui 热重载")
                }
            }
        }.onFailure { XLog.e("autoui 热更新失败", it) }
    }

    /** @return 本次是否真的把云控文件落盘成功 */
    private fun inject(parsingClass: Class<*>, systemServerClassLoader: ClassLoader): Boolean {
        val rules = RuleStore.autoUiRules()
        val version = Constants.AUTO_UI_CLOUD_DATA_VERSION

        val cloudFile = java.io.File(Constants.CLOUD_RULE_DIR, Constants.CLOUD_AUTO_UI_FILE)

        if (rules.isEmpty()) {
            // 本开机注入过、现规则全部关闭：删掉模块写下的云控文件让系统回落本地名单。
            // 否则旧文件 + 之前已抬高的版本号会让「关闭 autoui」在本开机周期内完全无效
            if (lastFingerprint != null) {
                runCatching {
                    cloudFile.delete()
                    lastFingerprint = null
                    XLog.i("autoui 规则已全部关闭，已移除云控文件，系统回落本地名单")
                    return true
                }.onFailure { XLog.e("清理 autoui 云控文件失败", it) }
            }
            if (!emptyLogged) {
                emptyLogged = true
                XLog.i("无 autoui 规则，跳过落盘（后续不再重复提示）")
            }
            return false
        }
        emptyLogged = false

        val packageRuleClass = runCatching {
            Reflect.findClass(Constants.CLASS_AUTO_UI_PACKAGE_RULE, systemServerClassLoader)
        }.getOrElse {
            XLog.e("未找到 PackageRule", it)
            return false
        }

        // 读取合并底：云控文件「存在即全量生效、固件 autoui_list.xml 被忽略」
        // （见 MiuiParsingAutoUI.loadPackage 两条路径互斥 return），而
        // updateAutoUICloudConfigFile 是 new FileOutputStream 全新截断写入、内部不做任何合并。
        // 因此必须先把云控文件里已有的条目读回来，再叠加模块条目，否则会把
        // 以往云控写入的条整体覆盖丢失。
        val base = readBaseEntries(cloudFile, packageRuleClass)

        // 内容与上次落盘一致就不再写文件。
        // 但版本号字段每次开机归零，所以仍要确保它是抬高过的。
        val fingerprint = fingerprintOf(version, base)
        if (fingerprint == lastFingerprint) {
            raiseVersion(parsingClass, version, log = false)
            return false
        }

        val map = HashMap<String, Any>(base)
        rules.forEach { rule ->
            runCatching {
                // 7 参：pkg, enable, activityRule, skippedActivityRule,
                //       versionCode, optimizeWebView, skippedAppConfigChange
                map[rule.packageName] = newPackageRule(
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
            Reflect.callStatic(
                parsingClass, Constants.M_UPDATE_CLOUD_CONFIG_FILE,
                Constants.CLOUD_AUTO_UI_FILE, map
            )
            XLog.i("已落盘 ${Constants.CLOUD_AUTO_UI_FILE}，合并底 ${base.size} + 模块 ${rules.size} = ${map.size} 条")
        }.onFailure {
            XLog.e("落盘 autoui 云控文件失败", it)
            return false
        }
        lastFingerprint = fingerprint

        raiseVersion(parsingClass, version, log = true)
        return true
    }

    /**
     * 构造系统 `PackageRule`（7 参构造，部分重载全部为 String）。
     * 直接反射构造：成员并非 public，`setAccessible(true)` 即可。
     */
    private fun newPackageRule(clazz: Class<*>, vararg args: Any?): Any =
        Reflect.constructor(clazz, args.size).newInstance(*args)

    /**
     * 读取合并底：把云控文件里已存在的扁平 `<package .../>` 条目重建为 PackageRule。
     * 云控文件不存在/为空返回空表；解析失败返回空表并告警——autoui 没有「固件等价物」可回落
     * （云控格式无法表达固件里的 <activity>/<view> 子树），故此处不中止注入，只记录。
     */
    private fun readBaseEntries(
        cloudFile: java.io.File,
        packageRuleClass: Class<*>
    ): Map<String, Any> {
        if (!cloudFile.canRead()) return emptyMap()
        val raw = runCatching { cloudFile.readText() }.getOrDefault("")
        val table = CloudXmlCodec.parse(raw)
        if (table == null) {
            XLog.e("${cloudFile.name} 解析失败（文件可能损坏），本轮以空底表注入")
            return emptyMap()
        }
        if (table.isEmpty()) return emptyMap()
        val result = HashMap<String, Any>(table.size)
        table.forEach { (pkg, attrs) ->
            runCatching {
                result[pkg] = newPackageRule(
                    packageRuleClass,
                    pkg,
                    attrs["enable"],
                    attrs["activityRule"],
                    attrs["skippedActivityRule"],
                    attrs["versionCode"],
                    attrs["optimizeWebView"],
                    attrs["skippedAppConfigChange"]
                )
            }.onFailure { XLog.e("重建云控条目失败：$pkg", it) }
        }
        return result
    }

    /** 规则内容指纹：只要参与落盘的字段没变，就认为不需要重写文件 */
    private fun fingerprintOf(version: Long, base: Map<String, Any>): String = buildString {
        append(version)
        // base 参与指纹：云控文件被外部改写（条目增减）时应重写，不能沿用旧指纹跳过
        base.keys.sorted().forEach { append('\n').append("b:").append(it) }
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
            Reflect.setStaticLongField(
                parsingClass, Constants.F_LAST_CLOUD_CONFIG_VERSION, version
            )
            if (log) XLog.i("已抬高 mLastCloudConfigVersion=$version")
        }.onFailure { XLog.e("抬高云控版本失败", it) }
    }
}
