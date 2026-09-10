package com.github.lsposed.magicwindow.data

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode
import org.xmlpull.v1.XmlPullParser
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Magisk 完美横屏模块规则导入器
 * 支持解析以下 XML 文件：
 * - embedded_rules_list.xml（平行窗口）
 * - fixed_orientation_list.xml（固定横屏）
 * - autoui_list.xml（界面自动适配）
 * - embedded_setting_config.xml（平行窗口设置）
 */
object MagiskRuleImporter {

    enum class Source {
        EMBEDDING, FIXED, AUTO_UI, SETTINGS
    }

    data class ImportResult(
        val rules: List<AppRule>,
        val summary: Map<Source, Int>,
        val warnings: List<String>
    )

    /**
     * 从文件描述符导入 Magisk 规则
     * @param context 上下文
     * @param fd 文件描述符（从 Intent 获取）
     * @return 导入结果
     */
    fun importFromFd(context: Context, fd: ParcelFileDescriptor): ImportResult {
        val warnings = mutableListOf<String>()
        val rulesMap = mutableMapOf<String, AppRule>()
        val summary = mutableMapOf<Source, Int>()

        try {
            val parser = Xml.newPullParser()
            parser.setInput(InputStreamReader(FileInputStream(fd.fileDescriptor)))

            var eventType = parser.eventType
            var currentSource: Source? = null
            var currentPkg: String? = null
            var currentAttrs = mutableMapOf<String, String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "rules" || parser.name == "embedded_rules" ||
                            parser.name == "fixed_orientation" || parser.name == "autoui" ||
                            parser.name == "config") {
                            currentSource = when (parser.name) {
                                "fixed_orientation", "fixed_orientation_list" -> Source.FIXED
                                "autoui", "autoui_list" -> Source.AUTO_UI
                                "config", "embedded_setting_config" -> Source.SETTINGS
                                else -> Source.EMBEDDING
                            }
                            summary[currentSource] = 0
                        } else if (parser.name == "package" || parser.name == "item" || parser.name == "rule") {
                            currentPkg = parser.getAttributeValue(null, "name")
                                ?: parser.getAttributeValue(null, "packageName")
                            currentAttrs.clear()
                            for (i in 0 until parser.attributeCount) {
                                val key = parser.getAttributeName(i)
                                val value = parser.getAttributeValue(i)
                                currentAttrs[key] = value
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if ((parser.name == "package" || parser.name == "item" || parser.name == "rule")
                            && currentPkg != null) {
                            val rule = rulesMap.getOrElse(currentPkg) { AppRule(currentPkg!!) }
                            val source = currentSource ?: Source.EMBEDDING

                            when (source) {
                                Source.EMBEDDING -> applyEmbeddingAttrs(rule, currentAttrs, warnings)
                                Source.FIXED -> applyFixedAttrs(rule, currentAttrs, warnings)
                                Source.AUTO_UI -> applyAutoUiAttrs(rule, currentAttrs, warnings)
                                Source.SETTINGS -> applySettingsAttrs(rule, currentAttrs, warnings)
                            }

                            rulesMap[currentPkg!!] = rule
                            summary[source] = (summary[source] ?: 0) + 1
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            warnings.add("解析错误：${e.message}")
        } finally {
            fd.close()
        }

        return ImportResult(
            rules = rulesMap.values.toList(),
            summary = summary,
            warnings = warnings
        )
    }

    private fun applyEmbeddingAttrs(rule: AppRule, attrs: Map<String, String>, warnings: MutableList<String>) {
        rule.mode = WindowMode.EMBEDDING
        rule.enabled = true

        attrs.forEach { (key, value) ->
            when (key) {
                "splitRatio" -> rule.splitRatio = value
                "activityRule" -> rule.activityRule = value
                "splitPairRule" -> rule.splitPairRule = value
                "supportFullSize" -> rule.supportFullSize = value.toBool()
                "isShowDivider" -> rule.isShowDivider = value.toBool()
                "skipSelfAdaptive" -> rule.skipSelfAdaptive = value.toBool()
                "relaunch" -> rule.relaunch = value.toBool()
                "clearTop" -> rule.clearTop = value.toBool()
                "disableSensor" -> rule.disableSensor = value.toBool()
                "allowRepeatPage" -> rule.allowRepeatPage = value.toBool()
                "useMiuiSplit" -> rule.useMiuiSplit = value.toBool()
                "miuiMagicWinEnabled" -> rule.miuiMagicWinEnabled = value.toBool()
                "scaleMode" -> rule.scaleMode = value
                "fullRule" -> rule.fullRule = value
                "middleRule" -> rule.middleRule = value
                "transitionRules" -> rule.transitionRules = value
                "splitMinWidth" -> rule.splitMinWidth = value
                "flags" -> rule.flags = value
                "autoUiRule" -> rule.autoUiRule = value
                "layoutDirection" -> rule.layoutDirection = value
                "adaptCutout" -> rule.embAdaptCutout = value
                "relaunchRule" -> rule.embRelaunchRule = value
                else -> {
                    // 未知属性忽略
                }
            }
        }
    }

    private fun applyFixedAttrs(rule: AppRule, attrs: Map<String, String>, warnings: MutableList<String>) {
        rule.mode = WindowMode.FIXED_ORIENTATION
        rule.enabled = true

        attrs.forEach { (key, value) ->
            when (key) {
                "supportModes" -> rule.foSupportModes = value
                "defaultSettings" -> rule.foDefaultSettings = value
                "supportFullSize" -> rule.foSupportFullSize = value.toBool()
                "isScale" -> rule.foIsScale = value.toBool()
                "skipCompatMode" -> rule.foSkipCompatMode = value.toBool()
                "allowEmbInPortrait" -> rule.foAllowEmbInPortrait = value.toBool()
                "allPortrait" -> rule.foAllPortrait = value.toBool()
                "autoUI" -> rule.foAutoUI = value.toBool()
                "isShowDivider" -> rule.foIsShowDivider = value.toBool()
                "skipSelfAdaptive" -> rule.foSkipSelfAdaptive = value.toBool()
                "forcePortraitActivity" -> rule.foForcePortraitActivity = value
                "fullForcePortraitActivity" -> rule.foFullForcePortraitActivity = value
                "adjustmentOrientation" -> rule.foAdjustmentOrientation = value
                "ratio" -> rule.foRatio = value
                "relaunchRule" -> rule.foRelaunchRule = value
                "transparentBar" -> rule.foTransparentBar = value
                "adaptCutout" -> rule.foAdaptCutout = value
                "disable" -> {
                    if (value.toBool()) {
                        warnings.add("应用 ${rule.packageName} 被标记为禁用，导入后可能需要手动开启")
                    }
                }
                else -> {
                    // 未知属性忽略
                }
            }
        }
    }

    private fun applyAutoUiAttrs(rule: AppRule, attrs: Map<String, String>, warnings: MutableList<String>) {
        rule.enabled = true

        attrs.forEach { (key, value) ->
            when (key) {
                "enable" -> rule.autoUiEnable = value.toBool()
                "optimizeWebView" -> rule.autoUiOptimizeWebView = value.toBool()
                "activityRule" -> rule.autoUiActivityRule = value
                "skippedActivityRule" -> rule.autoUiSkippedActivityRule = value
                "skippedAppConfigChange" -> rule.autoUiSkippedAppConfigChange = value
                "versionCode" -> rule.autoUiVersionCode = value
                else -> {
                    // 未知属性忽略
                }
            }
        }
    }

    private fun applySettingsAttrs(rule: AppRule, attrs: Map<String, String>, warnings: MutableList<String>) {
        // 设置配置一般不直接映射到 AppRule，仅记录
    }

    private fun String?.toBool(): Boolean = when (this?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> false
    }
}
