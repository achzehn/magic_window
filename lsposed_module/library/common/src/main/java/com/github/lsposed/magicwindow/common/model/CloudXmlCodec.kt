package com.github.lsposed.magicwindow.common.model

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.StringReader
import java.io.StringWriter

/**
 * 云控规则 XML 的解析与序列化（方案 2，app 与 hook 两侧共用）。
 *
 * 数据形态统一为「包名 → 属性表」，与系统 `MiuiParsingEmbeddedRule` /
 * `MiuiParsingFixedOrientationRule` 落盘格式一一对应：
 *   - embedding：根标签 `packageRules`，无版本号
 *   - fixed：根标签 `fixedOrientationRules`，带 `dataVersion`
 *
 * 系统读取云控文件是「存在即全量生效、内置文件被忽略」，所以写入必须是
 * 「读全量 → 按包覆盖 → 整体写回」。本类负责「整体写回」的序列化，
 * 「按包覆盖」由 [mergeWith] 完成。
 */
object CloudXmlCodec {

    const val KIND_EMBEDDING = 0
    const val KIND_FIXED = 1

    private const val TAG_PACKAGE = "package"
    private const val ATTR_NAME = "name"
    private const val ATTR_DATA_VERSION = "dataVersion"
    private const val ROOT_EMBEDDING = "packageRules"
    private const val ROOT_FIXED = "fixedOrientationRules"

    /** 解析一份云控 XML 文本为「包名 → 属性表」。无法解析时返回空表。 */
    fun parse(text: String): MutableMap<String, MutableMap<String, String>> {
        val result = LinkedHashMap<String, MutableMap<String, String>>()
        if (text.isBlank()) return result
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == TAG_PACKAGE) {
                    val attrs = LinkedHashMap<String, String>(parser.attributeCount)
                    var name: String? = null
                    for (i in 0 until parser.attributeCount) {
                        val key = parser.getAttributeName(i)
                        val value = parser.getAttributeValue(i)
                        if (key == ATTR_NAME) name = value else attrs[key] = value
                    }
                    if (!name.isNullOrEmpty()) result[name] = attrs
                }
                event = parser.next()
            }
        }
        return result
    }

    /**
     * 以 [base]（系统当前生效的全量规则）为底，用 [overrides]（本模块配置的规则）
     * 按包名覆盖，返回合并后的全量属性表。
     */
    fun mergeWith(
        base: Map<String, Map<String, String>>,
        overrides: Map<String, Map<String, String>>
    ): Map<String, Map<String, String>> {
        val merged = LinkedHashMap<String, Map<String, String>>()
        base.forEach { (pkg, attrs) -> merged[pkg] = attrs }
        overrides.forEach { (pkg, attrs) -> merged[pkg] = attrs }
        return merged
    }

    /**
     * 把全量属性表序列化为系统可读的云控 XML。
     *
     * @param kind [KIND_EMBEDDING] 或 [KIND_FIXED]，决定根标签与是否带 dataVersion
     * @param dataVersion 仅 fixed 使用；embedding 忽略
     */
    fun serialize(
        table: Map<String, Map<String, String>>,
        kind: Int,
        dataVersion: Long = 0L
    ): String {
        val writer = StringWriter()
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

        val root = if (kind == KIND_FIXED) ROOT_FIXED else ROOT_EMBEDDING
        serializer.startTag(null, root)
        if (kind == KIND_FIXED) {
            serializer.attribute(null, ATTR_DATA_VERSION, dataVersion.toString())
        }
        table.forEach { (pkg, attrs) ->
            serializer.startTag(null, TAG_PACKAGE)
            serializer.attribute(null, ATTR_NAME, pkg)
            attrs.forEach { (k, v) ->
                if (v.isNotEmpty()) serializer.attribute(null, k, v)
            }
            serializer.endTag(null, TAG_PACKAGE)
        }
        serializer.endTag(null, root)
        serializer.endDocument()
        serializer.flush()
        return writer.toString()
    }

    // ── AppRule → 属性表 ─────────────────────────────────────

    /**
     * 把一条模块规则转成 embedding 云控属性表。
     * 只在 mode=EMBEDDING 时生成，避免与 fixed 规则冲突。
     * 空字符串字段不写入，交由系统默认值处理；布尔只在需要时写 "true"/"false"。
     */
    fun embeddingAttrsOf(rule: AppRule): Map<String, String> {
        if (rule.mode != WindowMode.EMBEDDING) return emptyMap()
        return buildMap {
        // 布尔属性只在 true 时写入（系统默认 false，显式写 "false" 可能导致解析异常）
        if (rule.supportFullSize) put("supportFullSize", "true")
        if (rule.isShowDivider) put("isShowDivider", "true")
        if (rule.skipSelfAdaptive) put("skipSelfAdaptive", "true")
        if (rule.supportCameraPreview) put("supportCameraPreview", "true")
        if (rule.relaunch) put("relaunch", "true")
        if (rule.clearTop) put("clearTop", "true")
        // finishPrimaryWithSecondary / finishSecondaryWithPrimary 系统期望数字 "1" 而不是 "true"
        if (rule.finishSecondaryWithPrimary) put("finishSecondaryWithPrimary", "1")
        if (rule.finishPrimaryWithSecondary) put("finishPrimaryWithSecondary", "1")
        if (rule.disableSensor) put("disableSensor", "true")
        if (rule.allowRepeatPage) put("allowRepeatPage", "true")
        if (rule.isShowDialog) put("isShowDialog", "true")
        if (rule.useMiuiSplit) put("useMiuiSplit", "true")
        if (rule.miuiMagicWinEnabled) put("miuiMagicWinEnabled", "true")
        if (rule.disableCameraPreview) put("disableCameraPreview", "true")
        if (rule.embForceKillWhenSwitch) put("forceKillWhenSwitch", "true")

        putIfNotEmpty("splitRatio", rule.splitRatio)
        putIfNotEmpty("activityRule", rule.activityRule)
        putIfNotEmpty("splitPairRule", rule.splitPairRule)
        putIfNotEmpty("placeholder", rule.placeholder)
        // 注意：不能写 fullRule——系统解析到 fullRule 会判定该应用「支持全屏、不支持平行窗口」
        // （SettingRule.setSupportAEOrFullRule），平行窗口将永远无法选中
        // scaleMode 必须是数字（int），过滤误存的 "true"/"false"
        rule.scaleMode.takeIf { it != "true" && it != "false" && it.isNotEmpty() }
            ?.let { put("scaleMode", it) }
        putIfNotEmpty("middleRule", rule.middleRule)
        putIfNotEmpty("transitionRules", rule.transitionRules)
        putIfNotEmpty("splitLineColor", rule.splitLineColor)
        putIfNotEmpty("forcePortraitActivity", rule.forcePortraitActivity)
        putIfNotEmpty("splitMinWidth", rule.splitMinWidth)
        putIfNotEmpty("splitMinSmallestWidth", rule.splitMinSmallestWidth)
        putIfNotEmpty("minSupportVersion", rule.minSupportVersion)
        putIfNotEmpty("flags", rule.flags)
        putIfNotEmpty("procCompat", rule.procCompat)
        putIfNotEmpty("autoUiRule", rule.autoUiRule)
        putIfNotEmpty("defaultSettings", rule.defaultSettings)
        putIfNotEmpty("layoutDirection", rule.layoutDirection)
        putIfNotEmpty("killApps", rule.killApps)
        putIfNotEmpty("forcePortraitWhenSwitch", rule.forcePortraitWhenSwitch)
        putIfNotEmpty("sizecompatRatio", rule.sizecompatRatio)
        putIfNotEmpty("sizecompatRule", rule.sizecompatRule)
        putIfNotEmpty("transparentBar", rule.transparentBar)
        // adaptCutout 必须是数字（int），过滤误存的 "true"/"false"
        rule.embAdaptCutout.takeIf { it != "true" && it != "false" && it.isNotEmpty() }
            ?.let { put("adaptCutout", it) }
        putIfNotEmpty("relaunchRule", rule.embRelaunchRule)
        // version 必须是纯数字：系统 dealWithAppVersion 会 Long.parseLong，"1.0" 这类值会抛 NumberFormatException
        if (rule.version.isNotEmpty() && rule.version.all { it.isDigit() }) {
            put("version", rule.version)
        }
        }
    }

    /**
     * 通用全屏的 embedding 占位属性：只写 fullRule。
     * 系统（MiuiSystemEmbeddedRule）解析到 fullRule 后，会把应用标记为
     * 「支持全屏、不支持平行窗口」，此时再通过官方入口 onAppUiModeChanged(pkg, 3)
     * 才能真正选中全屏；缺省用通配值 "*"（全部页面整屏显示）。
     */
    fun fullScreenAttrsOf(rule: AppRule): Map<String, String> {
        if (rule.mode != WindowMode.FULL_SCREEN) return emptyMap()
        return mapOf("fullRule" to rule.fullRule.ifEmpty { "*" })
    }

    /**
     * 把一条模块规则转成 fixed 云控属性表。
     * 只在 mode=FIXED_ORIENTATION 时生成，避免与 embedding 规则冲突。
     * 分档布尔（relaunch / supportCameraPreview）写成系统约定的 `fo:`/`full:` 前缀格式。
     */
    fun fixedAttrsOf(rule: AppRule): Map<String, String> {
        if (rule.mode != WindowMode.FIXED_ORIENTATION) return emptyMap()
        return buildMap {
        putIfNotEmpty("supportModes", rule.foSupportModes)
        // defaultSettings 决定当前生效档（fo 或 full），缺省按 fo
        val tier = if (rule.foDefaultSettings.contains("full")) "full" else "fo"
        putIfNotEmpty("defaultSettings", rule.foDefaultSettings)
        // 分档布尔属性只在 true 时写入（格式 "fo:true" 或 "full:true"）
        if (rule.foRelaunch) put("relaunch", "$tier:true")
        if (rule.foSupportCameraPreview) put("supportCameraPreview", "$tier:true")
        // 其他布尔属性只在 true 时写入
        if (rule.foSupportFullSize) put("supportFullSize", "true")
        if (rule.foIsScale) put("isScale", "true")
        if (rule.foSkipCompatMode) put("skipCompatMode", "true")
        if (rule.foAllowEmbInPortrait) put("allowEmbInPortrait", "true")
        if (rule.foForceKillWhenSwitch) put("forceKillWhenSwitch", "true")
        if (rule.foAllPortrait) put("allPortrait", "true")
        if (rule.foAutoUI) put("autoUI", "true")
        if (rule.foIsShowDivider) put("isShowDivider", "true")
        if (rule.foSkipSelfAdaptive) put("skipSelfAdaptive", "true")
        // disable 直接控制固定横屏是否对该应用生效
        if (rule.foDisable) put("disable", "true")

        putIfNotEmpty("compatChange", rule.foCompatChange)
        putIfNotEmpty("disableCameraPreview", rule.foDisableCameraPreview)
        putIfNotEmpty("forcePortraitActivity", rule.foForcePortraitActivity)
        putIfNotEmpty("fullForcePortraitActivity", rule.foFullForcePortraitActivity)
        putIfNotEmpty("adjustmentOrientation", rule.foAdjustmentOrientation)
        putIfNotEmpty("adjustmentOrientationActivity", rule.foAdjustmentOrientationActivity)
        putIfNotEmpty("ratio", rule.foRatio)
        putIfNotEmpty("relaunchRule", rule.foRelaunchRule)
        putIfNotEmpty("transparentBar", rule.foTransparentBar)
        // adaptCutout 必须是数字（int），过滤误存的 "true"/"false"
        rule.foAdaptCutout.takeIf { it != "true" && it != "false" && it.isNotEmpty() }
            ?.let { put("adaptCutout", it) }
        }
    }

    private fun MutableMap<String, String>.putIfNotEmpty(key: String, value: String) {
        if (value.isNotEmpty()) put(key, value)
    }
}
