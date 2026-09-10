package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.CloudXmlCodec
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * embedding / fixed 云控注入（方案 2 终态，资料评估 4.10 / 6.2），模块的主生效路径。
 *
 * 设计取向（用户要求）：最低程度 hook、最低资源占用。因此本类只做「一次性主动操作」，
 * 不挂任何运行时热路径查询 hook：
 *   1. hook 两个解析类的「读规则」方法（loadPackage / loadLocalFixOrientationRuleXML），
 *      仅用于**首次拿到宿主实例**（mEmbeddedRule / mFixOriController）并缓存起来；
 *   2. 首次拿到实例时执行一次注入（落盘云控文件 + 翻用户开关 + 热重载）；
 *   3. 之后配置变更由 [injectNow] 触发（RuleStore.onChange），用缓存的实例重新注入，
 *      全程只调用系统的 public 方法，不再新增 hook。
 *
 * 生效原理：系统读取云控文件「存在即全量生效、内置文件被忽略」，故把本模块规则
 * 合并进系统全量规则后整体落盘；用户开关通过官方入口 onAppSwitchChanged /
 * onAppUiModeChanged 翻转（系统自行持久化到 embedded_setting_config.xml）。
 */
object EmbeddedFixedCloudInjector {

    /** 上次成功落盘的指纹，内容没变就不重写文件 */
    @Volatile
    private var embFingerprint: String? = null

    @Volatile
    private var fixedFingerprint: String? = null

    /** 缓存的宿主实例：用于配置变更后的热重载与翻开关，无需再 hook */
    @Volatile
    private var embeddedRule: Any? = null

    @Volatile
    private var fixedController: Any? = null

    /** 后台执行注入的线程（只启动一次） */
    @Volatile
    private var injectThread: Thread? = null

    /** 触发异步注入（拿到实例后立即返回，不阻塞系统启动） */
    private fun injectAsync() {
        if (injectThread?.isAlive == true) return
        injectThread = Thread {
            // 延迟 2 秒，等系统服务完全初始化
            Thread.sleep(2000)
            injectNow()
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun apply(pluginClassLoader: ClassLoader) {
        val config = RuleStore.global()
        if (!config.embeddedCloudInject && !config.fixedCloudInject) {
            XLog.i("embedding/fixed 云控注入已关闭")
            return
        }

        // 两个解析类都实现自同一个 MiuiSystemEmbeddedRule，先挂 embedding 侧拿 mEmbeddedRule，
        // fixed 侧通过同一实例的 controller 字段即可拿到，避免重复 hook。
        if (config.embeddedCloudInject) hookEmbedding(pluginClassLoader)
        if (config.fixedCloudInject) hookFixed(pluginClassLoader)
    }

    /** 配置保存后由 RuleStore.onChange 调用：用缓存实例重新注入并热重载。 */
    fun injectNow() {
        val config = RuleStore.global()
        if (config.embeddedCloudInject) {
            embeddedRule?.let { runCatching { doEmbedding(it) }.onFailure { e -> XLog.e("embedding 热更新失败", e) } }
        }
        if (config.fixedCloudInject) {
            fixedController?.let { runCatching { doFixed(it) }.onFailure { e -> XLog.e("fixed 热更新失败", e) } }
        }
    }

    // ── embedding ────────────────────────────────────────────

    private fun hookEmbedding(cl: ClassLoader) {
        runCatching {
            val parsingClass =
                XposedHelpers.findClass(Constants.CLASS_PARSING_EMBEDDED_RULE, cl)
            XposedHelpers.findAndHookMethod(
                parsingClass, "loadPackage",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val er = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mEmbeddedRule")
                        }.getOrNull() ?: return
                        if (embeddedRule == null) {
                            embeddedRule = er
                            // 异步注入，避免阻塞系统启动
                            injectAsync()
                        }
                    }
                }
            )
            XLog.i("已挂钩 MiuiParsingEmbeddedRule.loadPackage")
        }.onFailure { XLog.e("挂钩 embedding 云控注入失败", it) }
    }

    private fun doEmbedding(embeddedRule: Any) {
        val rules = RuleStore.activeRules()
            .filter { RuleStore.hasEmbeddingRule(it.packageName) }
        if (rules.isEmpty()) return

        val projection = runCatching {
            XposedHelpers.callMethod(embeddedRule, "isProjection") as Boolean
        }.getOrDefault(false)
        val cloudFile = cloudFile(
            projection,
            Constants.FILES_CLOUD_EMBEDDED_RULES[0],
            Constants.FILES_CLOUD_EMBEDDED_RULES[1]
        )

        val overrides = rules.associate {
            it.packageName to CloudXmlCodec.embeddingAttrsOf(it)
        }
        val fingerprint = overrides.toSortedMap().toString()
        if (fingerprint != embFingerprint) {
            val base = readBaseTable(cloudFile, Constants.FILES_EMBEDDED_RULES)
            val merged = CloudXmlCodec.mergeWith(base, overrides)
            val xml = CloudXmlCodec.serialize(merged, CloudXmlCodec.KIND_EMBEDDING)
            if (writeFile(cloudFile, xml)) {
                embFingerprint = fingerprint
                XLog.i("已落盘 ${cloudFile.name}，合并 ${base.size}+覆盖 ${overrides.size}=${merged.size} 条")
            } else return
        }

        // 翻用户开关：官方入口，系统自行持久化
        rules.forEach { rule ->
            runCatching {
                XposedHelpers.callMethod(embeddedRule, "onAppSwitchChanged", rule.packageName, true)
            }.onFailure { XLog.e("onAppSwitchChanged(${rule.packageName}) 失败", it) }
        }

        runCatching {
            XposedHelpers.callMethod(embeddedRule, "updatePackageConfigFromCloud")
            XLog.i("已触发 embedding 热重载")
        }.onFailure { XLog.e("触发 embedding 热重载失败", it) }
    }

    // ── fixed ────────────────────────────────────────────────

    private fun hookFixed(cl: ClassLoader) {
        runCatching {
            val parsingClass = XposedHelpers.findClass(
                "com.android.server.wm.MiuiParsingFixedOrientationRule", cl
            )
            XposedHelpers.findAndHookMethod(
                parsingClass, "loadLocalFixOrientationRuleXML",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fc = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mFixOriController")
                        }.getOrNull() ?: return
                        if (fixedController == null) {
                            fixedController = fc
                            // 异步注入，避免阻塞系统启动
                            injectAsync()
                        }
                    }
                }
            )
            XLog.i("已挂钩 MiuiParsingFixedOrientationRule.loadLocalFixOrientationRuleXML")
        }.onFailure { XLog.e("挂钩 fixed 云控注入失败", it) }
    }

    private fun doFixed(controller: Any) {
        val rules = RuleStore.activeRules()
            .filter { RuleStore.hasFixedOrientationRule(it.packageName) }
        if (rules.isEmpty()) return

        val projection = runCatching {
            XposedHelpers.getBooleanField(controller, "mIsProjection")
        }.getOrElse { false }
        val cloudFile = cloudFile(
            projection,
            Constants.FILES_CLOUD_FIXED_ORI_RULES[0],
            Constants.FILES_CLOUD_FIXED_ORI_RULES[1]
        )

        val overrides = rules.associate {
            it.packageName to CloudXmlCodec.fixedAttrsOf(it)
        }
        val version = RuleStore.global().cloudDataVersion
        val fingerprint = "$version|" + overrides.toSortedMap().toString()
        if (fingerprint != fixedFingerprint) {
            val base = readBaseTable(cloudFile, Constants.FILES_FIXED_ORI_RULES)
            val merged = CloudXmlCodec.mergeWith(base, overrides)
            val xml = CloudXmlCodec.serialize(merged, CloudXmlCodec.KIND_FIXED, version)
            if (writeFile(cloudFile, xml)) {
                fixedFingerprint = fingerprint
                XLog.i("已落盘 ${cloudFile.name}，合并 ${base.size}+覆盖 ${overrides.size}=${merged.size} 条")
            } else return
        }

        // 翻用户开关：fixed 开关走 onAppUiModeChanged(source, pkg, 2)，mode 2 即固定横屏
        val embeddedRule = embeddedRule
        if (embeddedRule != null) {
            rules.forEach { rule ->
                runCatching {
                    XposedHelpers.callMethod(
                        embeddedRule, "onAppUiModeChanged", "", rule.packageName, 2
                    )
                }.onFailure { XLog.e("onAppUiModeChanged(${rule.packageName}) 失败", it) }
            }
        }

        runCatching {
            XposedHelpers.callMethod(controller, "updateFixedOrientationFromCloud")
            XLog.i("已触发 fixed 热重载")
        }.onFailure { XLog.e("触发 fixed 热重载失败", it) }
    }

    // ── 读写 ─────────────────────────────────────────────────

    private fun cloudFile(projection: Boolean, normal: String, proj: String): File =
        File(Constants.CLOUD_RULE_DIR, if (projection) proj else normal)

    /** 读取合并底：云控文件优先，没有再读 /product/etc 本地名单 */
    private fun readBaseTable(cloudFile: File, localNames: List<String>): Map<String, Map<String, String>> {
        if (cloudFile.canRead()) {
            val t = CloudXmlCodec.parse(runCatching { cloudFile.readText() }.getOrDefault(""))
            if (t.isNotEmpty()) return t
        }
        for (name in localNames) {
            for (dir in Constants.SYSTEM_RULE_DIRS) {
                val f = File(dir, name)
                if (f.canRead()) {
                    val t = CloudXmlCodec.parse(runCatching { f.readText() }.getOrDefault(""))
                    if (t.isNotEmpty()) return t
                }
            }
        }
        return emptyMap()
    }

    private fun writeFile(file: File, content: String): Boolean = runCatching {
        file.writeText(content)
        // 与系统一致：0644
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "644", file.absolutePath)).waitFor()
        }
        true
    }.onFailure { XLog.e("写入 ${file.name} 失败", it) }.getOrDefault(false)
}
