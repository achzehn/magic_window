package com.github.lsposed.magicwindow.hook.steps

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.CloudXmlCodec
import com.github.lsposed.magicwindow.common.model.WindowMode
import com.github.lsposed.magicwindow.hook.Reflect
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
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

    /** 后台执行注入的线程（只启动一次）；与 pending 标志在 [injectLock] 内保护 */
    @Volatile
    private var injectThread: Thread? = null

    /** 线程运行期间到达的新触发不丢弃，消费完才退出 */
    private var injectPending = false

    private val injectLock = Any()

    /** 触发异步注入（拿到实例后立即返回，不阻塞系统启动） */
    private fun injectAsync() {
        synchronized(injectLock) {
            if (injectThread != null) {
                // 线程在 2s 延迟窗口内运行时，另一实例（如 fixed 晚捕获）的触发若直接丢弃，
                // 该实例要等下次配置保存才注入——记 pending 让线程补跑一轮
                injectPending = true
                return
            }
            injectThread = Thread {
                // 延迟 2 秒，等系统服务完全初始化
                Thread.sleep(2000)
                injectNow()
                while (true) {
                    val again = synchronized(injectLock) {
                        if (injectPending) {
                            injectPending = false
                            true
                        } else {
                            injectThread = null
                            false
                        }
                    }
                    if (!again) break
                    injectNow()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    fun apply(xposed: XposedInterface, pluginClassLoader: ClassLoader) {
        // embedding 与 fixed 各挂各的读规则 hook：分别在 loadPackage /
        // loadLocalFixOrientationRuleXML 后从宿主对象取 mEmbeddedRule、mFixOriController 缓存
        hookEmbedding(xposed, pluginClassLoader)
        hookFixed(xposed, pluginClassLoader)
    }

    /** 配置保存后由 RuleStore.onChange 调用：用缓存实例重新注入并热重载。 */
    @Synchronized
    fun injectNow() {
        embeddedRule?.let { runCatching { doEmbedding(it) }.onFailure { e -> XLog.e("embedding 热更新失败", e) } }
        fixedController?.let { runCatching { doFixed(it) }.onFailure { e -> XLog.e("fixed 热更新失败", e) } }
        // 两个规则列表都重载完成后，再统一翻各应用的当前模式（模式支持与否依赖刚重载的规则）
        embeddedRule?.let { er ->
            runCatching { applyAppModes(er) }.onFailure { e -> XLog.e("翻应用模式失败", e) }
        }
    }

    // ── embedding ────────────────────────────────────────────

    private fun hookEmbedding(xposed: XposedInterface, cl: ClassLoader) {
        runCatching {
            val parsingClass = Reflect.findClass(Constants.CLASS_PARSING_EMBEDDED_RULE, cl)
            // 旧版 ROM 的 loadPackage 带 1 个参数，Android 17（OS4.x）已改为无参；
            // 按方法名取参数最少的重载，避免参数个数硬编码导致 hook 失败、embeddedRule 永远为 null
            val loadPackage = Reflect.findMethods(parsingClass, "loadPackage")
                .minByOrNull { it.parameterCount }
                ?: throw NoSuchMethodException("${parsingClass.name}#loadPackage")
            xposed.hook(loadPackage).setId("embedding_loadPackage")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val result = chain.proceed()
                    if (embeddedRule == null) {
                        val er = runCatching {
                            Reflect.getField(chain.thisObject, "mEmbeddedRule")
                        }.getOrNull()
                        if (er != null) {
                            embeddedRule = er
                            // 异步注入，避免阻塞系统启动
                            injectAsync()
                        }
                    }
                    result
                })
            XLog.i("已挂钩 MiuiParsingEmbeddedRule.loadPackage")
        }.onFailure { XLog.e("挂钩 embedding 云控注入失败", it) }
    }

    private fun doEmbedding(embeddedRule: Any) {
        val rules = RuleStore.activeRules()
        // 增/改：平行窗口写完整属性；通用全屏写 fullRule 占位（系统据此才支持全屏）
        val overrides = rules.filter {
            it.mode == WindowMode.EMBEDDING || it.mode == WindowMode.FULL_SCREEN
        }.associate {
            it.packageName to if (it.mode == WindowMode.EMBEDDING)
                CloudXmlCodec.embeddingAttrsOf(it) else CloudXmlCodec.fullScreenAttrsOf(it)
        }
        // 移除：选了固定横屏的应用必须移出平行窗口列表，否则继续按平行窗口跑
        val removals = rules.filter { it.mode == WindowMode.FIXED_ORIENTATION }
            .map { it.packageName }.toSet()
        if (overrides.isEmpty() && removals.isEmpty()) return

        val projection = runCatching {
            Reflect.call(embeddedRule, "isProjection") as Boolean
        }.getOrDefault(false)
        val cloudFile = cloudFile(
            projection,
            Constants.FILES_CLOUD_EMBEDDED_RULES[0],
            Constants.FILES_CLOUD_EMBEDDED_RULES[1]
        )

        val fingerprint = overrides.toSortedMap().toString() + "|-" + removals.sorted()
        if (fingerprint != embFingerprint) {
            val base = readBaseTable(cloudFile, Constants.FILES_EMBEDDED_RULES) ?: return
            val merged = LinkedHashMap(CloudXmlCodec.mergeWith(base, overrides))
            removals.forEach { merged.remove(it) }
            val xml = CloudXmlCodec.serialize(merged, CloudXmlCodec.KIND_EMBEDDING)
            if (writeFile(cloudFile, xml)) {
                embFingerprint = fingerprint
                XLog.i("已落盘 ${cloudFile.name}，合并 ${base.size}+覆盖 ${overrides.size}-移除 ${removals.size}=${merged.size} 条")
            } else return
        }

        runCatching {
            Reflect.call(embeddedRule, "updatePackageConfigFromCloud")
            XLog.i("已触发 embedding 热重载")
        }.onFailure { XLog.e("触发 embedding 热重载失败", it) }
    }

    // ── fixed ────────────────────────────────────────────────

    private fun hookFixed(xposed: XposedInterface, cl: ClassLoader) {
        runCatching {
            val parsingClass = Reflect.findClass(
                "com.android.server.wm.MiuiParsingFixedOrientationRule", cl
            )
            val loadLocal = Reflect.method(parsingClass, "loadLocalFixOrientationRuleXML", 0)
            xposed.hook(loadLocal).setId("fixed_loadLocalRule")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val result = chain.proceed()
                    if (fixedController == null) {
                        val fc = runCatching {
                            Reflect.getField(chain.thisObject, "mFixOriController")
                        }.getOrNull()
                        if (fc != null) {
                            fixedController = fc
                            // 异步注入，避免阻塞系统启动
                            injectAsync()
                        }
                    }
                    result
                })
            XLog.i("已挂钩 MiuiParsingFixedOrientationRule.loadLocalFixOrientationRuleXML")
        }.onFailure { XLog.e("挂钩 fixed 云控注入失败", it) }
    }

    private fun doFixed(controller: Any) {
        val rules = RuleStore.activeRules()
        // 增/改：只有选了固定横屏的应用才写 fixed 规则
        val overrides = rules.filter { it.mode == WindowMode.FIXED_ORIENTATION }
            .associate { it.packageName to CloudXmlCodec.fixedAttrsOf(it) }
        // 移除：选了平行窗口/通用全屏的应用必须移出 fixed 列表（fixed 系统优先级最高，不移会压制用户选择）
        val removals = rules.filter {
            it.mode == WindowMode.EMBEDDING || it.mode == WindowMode.FULL_SCREEN
        }.map { it.packageName }.toSet()
        if (overrides.isEmpty() && removals.isEmpty()) return

        val projection = runCatching {
            Reflect.getBooleanField(controller, "mIsProjection")
        }.getOrElse { false }
        val cloudFile = cloudFile(
            projection,
            Constants.FILES_CLOUD_FIXED_ORI_RULES[0],
            Constants.FILES_CLOUD_FIXED_ORI_RULES[1]
        )

        // dataVersion 用落盘时刻的时间戳：真正重写文件时版本自然递增，内容没变就不动
        val version = System.currentTimeMillis()
        val fingerprint = overrides.toSortedMap().toString() + "|-" + removals.sorted()
        if (fingerprint != fixedFingerprint) {
            val base = readBaseTable(cloudFile, Constants.FILES_FIXED_ORI_RULES) ?: return
            val merged = LinkedHashMap(CloudXmlCodec.mergeWith(base, overrides))
            removals.forEach { merged.remove(it) }
            val xml = CloudXmlCodec.serialize(merged, CloudXmlCodec.KIND_FIXED, version)
            if (writeFile(cloudFile, xml)) {
                fixedFingerprint = fingerprint
                XLog.i("已落盘 ${cloudFile.name}，合并 ${base.size}+覆盖 ${overrides.size}-移除 ${removals.size}=${merged.size} 条")
            } else return
        }

        runCatching {
            Reflect.call(controller, "updateFixedOrientationFromCloud")
            XLog.i("已触发 fixed 热重载")
        }.onFailure { XLog.e("触发 fixed 热重载失败", it) }
    }

    // ── 翻应用当前模式 ──────────────────────────────────────

    /**
     * 通过系统官方入口 onAppUiModeChanged 把每个应用翻成用户选择的模式，
     * 系统自行持久化到 embedded_setting_config.xml 并立即生效。
     * 系统 SettingRule 是单选状态机，mode 取值：0=不处理 1=平行窗口 2=固定横屏 3=通用全屏，
     * 4/5/6 = 固定横屏下的比例档（4:3 / 16:9 / 全屏拉伸）。
     *
     * 注意必须在两个规则列表热重载之后调用：目标模式是否「受支持」取决于刚重载的规则
     * （平行窗口←embedded 列表、固定横屏←fixed 列表、全屏←fullRule），不支持会被系统拒绝。
     */
    private fun applyAppModes(embeddedRule: Any) {
        RuleStore.activeRules().forEach { rule ->
            val hasRatio = rule.ratio43Enable || rule.ratio169Enable || rule.ratioFullScreenEnable
            val target = when (rule.mode) {
                WindowMode.EMBEDDING -> 1
                WindowMode.FIXED_ORIENTATION -> when {
                    rule.ratio43Enable -> 4
                    rule.ratio169Enable -> 5
                    rule.ratioFullScreenEnable -> 6
                    else -> 2
                }
                WindowMode.FULL_SCREEN -> 3
                // 不处理：翻回 0（不处理）。此前直接跳过会让「改为不处理」后
                // embedded_setting 里残留上次注入的模式值，应用继续按旧模式跑
                WindowMode.OFF -> 0
            }
            // 比例档的支持标记来自 createSetting 声明（规则列表的 supportModes 不一定覆盖），
            // 先声明再选中，系统才有对应的支持位
            if (rule.mode == WindowMode.FIXED_ORIENTATION && hasRatio) {
                runCatching {
                    // createSetting(pkg, enable, fixedOrientationEnable, fullScreenEnable,
                    //               ratio_4_3_Enable, ratio_16_9_Enable, ratio_fullScreenEnable, isModified)
                    Reflect.call(
                        embeddedRule, "createSetting",
                        rule.packageName,
                        "",      // enable(平行窗口) 留空：当前是固定横屏模式
                        "true",  // fixedOrientationEnable
                        "",      // fullScreenEnable
                        if (rule.ratio43Enable) "true" else "",
                        if (rule.ratio169Enable) "true" else "",
                        if (rule.ratioFullScreenEnable) "true" else "",
                        "true"   // isModified
                    )
                }.onFailure { XLog.e("createSetting(${rule.packageName}) 失败", it) }
            }
            runCatching {
                Reflect.call(
                    embeddedRule, "onAppUiModeChanged", "", rule.packageName, target
                )
                XLog.i("已翻 ${rule.packageName} 到模式 $target")
            }.onFailure { XLog.e("onAppUiModeChanged(${rule.packageName}, $target) 失败", it) }
        }
    }

    // ── 读写 ─────────────────────────────────────────────────

    private fun cloudFile(projection: Boolean, normal: String, proj: String): File =
        File(Constants.CLOUD_RULE_DIR, if (projection) proj else normal)

    /**
     * 读取合并底：云控文件优先，没有再读 /product/etc 本地名单。
     * 云控文件存在但解析失败（损坏）时返回 null 表示放弃本轮注入——回落本地写回
     * 会把云控里的系统条目整体覆盖丢失。
     */
    private fun readBaseTable(cloudFile: File, localNames: List<String>): Map<String, Map<String, String>>? {
        if (cloudFile.canRead()) {
            val raw = runCatching { cloudFile.readText() }.getOrDefault("")
            val t = CloudXmlCodec.parse(raw)
                ?: run {
                    XLog.e("${cloudFile.name} 解析失败（文件可能损坏），本轮跳过注入以保留云控现有内容")
                    return null
                }
            if (t.isNotEmpty()) return t
        }
        for (name in localNames) {
            for (dir in Constants.SYSTEM_RULE_DIRS) {
                val f = File(dir, name)
                if (f.canRead()) {
                    val t = CloudXmlCodec.parse(runCatching { f.readText() }.getOrDefault(""))
                    if (t != null && t.isNotEmpty()) return t
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
