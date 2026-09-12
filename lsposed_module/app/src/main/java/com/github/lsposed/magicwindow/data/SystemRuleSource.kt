package com.github.lsposed.magicwindow.data

import android.os.Handler
import android.os.Looper
import android.util.Xml
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * 读取系统当前实际生效的三份规则文件，作为「系统已内置」标记与详情页初值来源。
 *
 * 文件选择与系统自身逻辑一致（见 MiuiParsingFixedOrientationRule.chooseConfigFile）：
 *   - 云控：/data/system/cloudFeature_*.xml（system_server 写出，普通应用无权读，需 root）
 *   - 本地：/product/etc 等目录下的 *_list.xml（可直接读）
 *   - 两份都在时，比较根标签 dataVersion，云控版本 >= 本地时用云控，否则用本地；
 *     只有一份时直接用那一份。
 *
 * 条目标签统一为 `package`，name 属性为包名，其余属性全部保留为属性表。
 * projection 机型只提供 `*_projection.xml`，故使用候选列表。
 */
object SystemRuleSource {

    enum class Kind { EMBEDDING, FIXED, AUTO_UI }

    /** 解析结果：包名 → 属性表 */
    private val tables = mutableMapOf<Kind, Map<String, Map<String, String>>>()

    /** 读取过程中出现的问题（例如无 root 权限），非空时 UI 上给出提示 */
    @Volatile
    var errorMessage: String? = null
        private set

    @Volatile
    var loaded = false
        private set

    /** 云控是否通过 root 读取成功，失败时静默降级到本地名单 */
    @Volatile
    var rootAvailable = false
        private set

    private const val SCOPE_FO = "fo"
    private const val SCOPE_FULL = "full"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadLock = Object()
    @Volatile
    private var loadStarted = false

    /** 加载完成前挂起的 UI 回调，doLoad 结束后在主线程统一派发 */
    private val pendingCallbacks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 后台预热：App 启动时调用一次，列表/详情打开时基本已经加载完 */
    fun preload() {
        if (loaded || loadStarted) return
        synchronized(loadLock) {
            if (loaded || loadStarted) return
            loadStarted = true
            Thread({ doLoad() }, "system-rule-loader").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
    }

    /** 加载完成后在主线程执行；已加载完则立即调度 */
    fun whenLoadedOnMain(action: () -> Unit) {
        if (loaded) {
            mainHandler.post(action)
            return
        }
        pendingCallbacks += action
        preload()
    }

    // ── 加载与选文件 ─────────────────────────────────────────

    private fun doLoad() {
        val messages = mutableListOf<String>()
        try {
            // 一次 su 调用把三份云控文件都 cat 出来，避免反复弹授权
            val cloudPaths = buildList {
                Constants.FILES_CLOUD_EMBEDDED_RULES.forEach { add(Constants.CLOUD_RULE_DIR + it) }
                Constants.FILES_CLOUD_FIXED_ORI_RULES.forEach { add(Constants.CLOUD_RULE_DIR + it) }
                Constants.FILES_CLOUD_AUTO_UI_RULES.forEach { add(Constants.CLOUD_RULE_DIR + it) }
            }
            val cloud = readViaRoot(cloudPaths)
            rootAvailable = cloud.isNotEmpty()
            if (!rootAvailable) messages += "未取得 root 权限，云控名单不可读，已改用 /product/etc 内置名单"

            tables[Kind.EMBEDDING] = chooseTable(
                Constants.FILES_CLOUD_EMBEDDED_RULES.map(Constants.CLOUD_RULE_DIR::plus), cloud,
                Constants.FILES_EMBEDDED_RULES, messages
            )
            tables[Kind.FIXED] = chooseTable(
                Constants.FILES_CLOUD_FIXED_ORI_RULES.map(Constants.CLOUD_RULE_DIR::plus), cloud,
                Constants.FILES_FIXED_ORI_RULES, messages
            )
            tables[Kind.AUTO_UI] = chooseTable(
                Constants.FILES_CLOUD_AUTO_UI_RULES.map(Constants.CLOUD_RULE_DIR::plus), cloud,
                Constants.FILES_AUTO_UI_RULES, messages
            )
        } catch (t: Throwable) {
            messages += "读取系统规则出错：${t.message ?: t.javaClass.simpleName}"
        }
        errorMessage = messages.firstOrNull()
        synchronized(loadLock) { loaded = true }
        mainHandler.post {
            pendingCallbacks.forEach { runCatching { it() } }
            pendingCallbacks.clear()
        }
    }

    /**
     * 云控与本地各取候选列表中第一个能读到的文件，比较 dataVersion 后返回胜出的属性表。
     * 云控版本 >= 本地（含双方都没有版本号的情况）时用云控，与系统选择逻辑一致。
     */
    private fun chooseTable(
        cloudPaths: List<String>,
        cloudContents: Map<String, String>,
        localNames: List<String>,
        missing: MutableList<String>
    ): Map<String, Map<String, String>> {
        val cloud = firstParsed(cloudPaths.mapNotNull { cloudContents[it]?.let(::parseContent) })
        val local = firstParsed(
            localNames.flatMap { name ->
                Constants.SYSTEM_RULE_DIRS.map { File(it, name) }
                    .filter { it.canRead() }
                    .mapNotNull { runCatching { parseContent(it.readText()) }.getOrNull() }
            }
        )
        val picked = when {
            cloud == null -> local
            local == null -> cloud
            cloud.dataVersion >= local.dataVersion -> cloud
            else -> local
        }
        if (picked == null) {
            missing += localNames.first()
            return emptyMap()
        }
        return picked.table
    }

    private fun firstParsed(list: List<ParsedFile>): ParsedFile? =
        list.firstOrNull { it.table.isNotEmpty() || it.dataVersion > 0L }

    private data class ParsedFile(val dataVersion: Long, val table: Map<String, Map<String, String>>)

    /**
     * 通过 root（KernelSU / Magisk 兼容的 su）读取文件。
     * 用分隔标记拼接多条 cat，一次进程调用取回全部文件；读不到（文件不存在/拒绝）返回空 Map。
     */
    private fun readViaRoot(paths: List<String>): Map<String, String> {
        val marker = "\u0001MW\u0001"
        val cmd = paths.joinToString(separator = " ") { path ->
            "echo '$marker$path'; cat '$path' 2>/dev/null;"
        }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            return emptyMap()
        }
        if (process.exitValue() != 0) return emptyMap()
        val text = process.inputStream.bufferedReader().readText()
        if (marker !in text) return emptyMap()

        val result = HashMap<String, String>()
        // 段首为 marker+路径，其后到下一个 marker 之间是文件内容
        text.split(marker).forEach { segment ->
            if (segment.isEmpty()) return@forEach
            val lineEnd = segment.indexOf('\n')
            if (lineEnd < 0) return@forEach
            val path = segment.substring(0, lineEnd).trim()
            val content = segment.substring(lineEnd + 1)
            if (path in paths && content.contains("<")) result[path] = content
        }
        return result
    }

    // ── XML 解析 ─────────────────────────────────────────────

    private fun parseContent(text: String): ParsedFile? {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))
            var dataVersion = 0L
            val result = HashMap<String, Map<String, String>>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (dataVersion == 0L) {
                        parser.getAttributeValue(null, Constants.CLOUD_KEY_DATA_VERSION)
                            ?.toLongOrNull()
                            ?.let { dataVersion = it }
                    }
                    if (parser.name == "package") {
                        val attrs = HashMap<String, String>(parser.attributeCount)
                        var name: String? = null
                        for (i in 0 until parser.attributeCount) {
                            val key = parser.getAttributeName(i)
                            val value = parser.getAttributeValue(i)
                            if (key == "name") name = value else attrs[key] = value
                        }
                        if (!name.isNullOrEmpty()) result[name] = attrs
                    }
                }
                event = parser.next()
            }
            ParsedFile(dataVersion, result)
        } catch (t: Throwable) {
            null
        }
    }

    // ── 查询 ─────────────────────────────────────────────────

    fun has(kind: Kind, pkg: String): Boolean = tables[kind]?.containsKey(pkg) == true

    fun attrsOf(kind: Kind, pkg: String): Map<String, String> =
        tables[kind]?.get(pkg) ?: emptyMap()

    /** 固定横屏名单里被 ROM 标记为 disable="true" 的条目（实测数百条），需覆盖才能生效 */
    fun isFixedDisabled(pkg: String): Boolean =
        attrsOf(Kind.FIXED, pkg)["disable"].toBoolOrNull() == true

    /** 某个应用被哪些内置规则覆盖，用于列表徽标，例如「平行窗口 · 固定横屏」 */
    fun kindsOf(pkg: String): List<Kind> = Kind.entries.filter { has(it, pkg) }

    fun anyOf(pkg: String): Boolean = kindsOf(pkg).isNotEmpty()

    // ── 内置值 → AppRule ─────────────────────────────────────

    /**
     * 用系统当前生效的值填充一条尚未被用户保存过的规则，让「先看到系统当前行为，再改」。
     * 只写规则文件里真实存在的属性，其余保持 [AppRule] 默认值。
     */
    fun applyDefaults(rule: AppRule) {
        val emb = attrsOf(Kind.EMBEDDING, rule.packageName)
        val fixed = attrsOf(Kind.FIXED, rule.packageName)
        val auto = attrsOf(Kind.AUTO_UI, rule.packageName)
        if (emb.isEmpty() && fixed.isEmpty() && auto.isEmpty()) return

        emb.forEach { (k, v) ->
            when (k) {
                "supportFullSize" -> rule.supportFullSize = v.toBool()
                "isShowDivider" -> rule.isShowDivider = v.toBool()
                "skipSelfAdaptive" -> rule.skipSelfAdaptive = v.toBool()
                "relaunch" -> rule.relaunch = v.toBool()
                "clearTop" -> rule.clearTop = v.toBool()
                "finishSecondaryWithPrimary" -> rule.finishSecondaryWithPrimary = v.toBool()
                "finishPrimaryWithSecondary" -> rule.finishPrimaryWithSecondary = v.toBool()
                "disableSensor" -> rule.disableSensor = v.toBool()
                "allowRepeatPage" -> rule.allowRepeatPage = v.toBool()
                "isShowDialog" -> rule.isShowDialog = v.toBool()
                "useMiuiSplit" -> rule.useMiuiSplit = v.toBool()
                "miuiMagicWinEnabled" -> rule.miuiMagicWinEnabled = v.toBool()
                "forceKillWhenSwitch" -> rule.embForceKillWhenSwitch = v.toBool()
                "splitRatio" -> rule.splitRatio = v
                "activityRule" -> rule.activityRule = v
                "splitPairRule" -> rule.splitPairRule = v
                "placeholder" -> rule.placeholder = v
                "fullRule" -> rule.fullRule = v
                "scaleMode" -> rule.scaleMode = v
                "middleRule" -> rule.middleRule = v
                "transitionRules" -> rule.transitionRules = v
                "splitLineColor" -> rule.splitLineColor = v
                "forcePortraitActivity" -> rule.forcePortraitActivity = v
                "splitMinWidth" -> rule.splitMinWidth = v
                "splitMinSmallestWidth" -> rule.splitMinSmallestWidth = v
                "minSupportVersion" -> rule.minSupportVersion = v
                "flags" -> rule.flags = v
                "procCompat" -> rule.procCompat = v
                "autoUiRule" -> rule.autoUiRule = v
                "defaultSettings" -> rule.defaultSettings = v
                "layoutDirection" -> rule.layoutDirection = v
                "killApps" -> rule.killApps = v
                "forcePortraitWhenSwitch" -> rule.forcePortraitWhenSwitch = v
                "sizecompatRatio" -> rule.sizecompatRatio = v
                "sizecompatRule" -> rule.sizecompatRule = v
                "transparentBar" -> rule.transparentBar = v
                "adaptCutout" -> rule.embAdaptCutout = v
                "relaunchRule" -> rule.embRelaunchRule = v
                // 黑/白名单两种写法：disableCameraPreview=true 等价于不支持相机预览
                "disableCameraPreview" -> v.toBoolOrNull()?.let { rule.supportCameraPreview = !it }
                "supportCameraPreview" -> rule.supportCameraPreview = v.toBool()
            }
        }

        if (fixed.isNotEmpty()) {
            fixed.forEach { (k, v) ->
                when (k) {
                    "supportModes" -> rule.foSupportModes = v
                    "defaultSettings" -> rule.foDefaultSettings = v
                    "compatChange" -> rule.foCompatChange = v
                    "supportFullSize" -> rule.foSupportFullSize = v.toBool()
                    "isScale" -> rule.foIsScale = v.toBool()
                    "skipCompatMode" -> rule.foSkipCompatMode = v.toBool()
                    "allowEmbInPortrait" -> rule.foAllowEmbInPortrait = v.toBool()
                    "forceKillWhenSwitch" -> rule.foForceKillWhenSwitch = v.toBool()
                    "allPortrait" -> rule.foAllPortrait = v.toBool()
                    "autoUI" -> rule.foAutoUI = v.toBool()
                    "isShowDivider" -> rule.foIsShowDivider = v.toBool()
                    "skipSelfAdaptive" -> rule.foSkipSelfAdaptive = v.toBool()
                    "forcePortraitActivity" -> rule.foForcePortraitActivity = v
                    "fullForcePortraitActivity" -> rule.foFullForcePortraitActivity = v
                    "adjustmentOrientation" -> rule.foAdjustmentOrientation = v
                    "adjustmentOrientationActivity" -> rule.foAdjustmentOrientationActivity = v
                    "ratio" -> rule.foRatio = v
                    "relaunchRule" -> rule.foRelaunchRule = v
                    "transparentBar" -> rule.foTransparentBar = v
                    "adaptCutout" -> rule.foAdaptCutout = v
                }
            }
            // 分档（fo/full）值要按当前生效的档取，必须在 defaultSettings 映射后处理
            val tier =
                if (fixed["defaultSettings"]?.contains(SCOPE_FULL) == true) SCOPE_FULL else SCOPE_FO
            tieredBool(fixed["relaunch"], tier)?.let { rule.foRelaunch = it }
            fixed["disableCameraPreview"]?.toBoolOrNull()
                ?.let { rule.foSupportCameraPreview = !it }
            tieredBool(fixed["supportCameraPreview"], tier)
                ?.let { rule.foSupportCameraPreview = it }
        }

        auto.forEach { (k, v) ->
            when (k) {
                "enable" -> rule.autoUiEnable = v.toBool()
                "optimizeWebView" -> rule.autoUiOptimizeWebView = v.toBool()
                "activityRule" -> rule.autoUiActivityRule = v
                "skippedActivityRule" -> rule.autoUiSkippedActivityRule = v
                "skippedAppConfigChange" -> rule.autoUiSkippedAppConfigChange = v
                "versionCode" -> rule.autoUiVersionCode = v
            }
        }

        // 内置模式按系统自身优先级预选：固定横屏 > 平行窗口
        rule.mode = when {
            fixed.isNotEmpty() -> WindowMode.FIXED_ORIENTATION
            emb.isNotEmpty() -> WindowMode.EMBEDDING
            else -> rule.mode
        }
    }

    /**
     * 解析 fixed 规则的分档值（语义与 FixedOrientationRule.parseRule 一致）：
     *   fo:false / full:true → 只在对应档取值；
     *   *:false              → 两档都取该值；
     *   裸值 false/true      → 按 fo 档处理。
     * 写的是另一档或无法识别时返回 null（表示该项对当前档不生效，不覆盖默认值）。
     */
    private fun tieredBool(raw: String?, tier: String): Boolean? {
        if (raw == null) return null
        val value = raw.trim()
        if (value.contains(':')) {
            val scope = value.substringBefore(':')
            val boolPart = value.substringAfter(':')
            return when (scope) {
                "*" -> boolPart.toBoolOrNull()
                tier -> boolPart.toBoolOrNull()
                else -> null
            }
        }
        return if (tier == SCOPE_FO) value.toBoolOrNull() else null
    }

}

// ── 布尔解析（来源文件里有 true/false、1/0，Magisk 导出还有 yes/no） ──

internal fun String?.toBoolOrNull(): Boolean? = when (this?.trim()?.lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}

internal fun String?.toBool(): Boolean = toBoolOrNull() == true
