package com.github.lsposed.magicwindow.data

import android.util.Xml
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * 读取系统自带（ROM 内置）的三份规则文件，作为「系统已内置」标记与详情页初值来源。
 *
 * 文件位置见资料评估 4.10.1，条目标签统一为 `package`：
 *   - embedded_rules_list.xml / embedded_rules_list_projection.xml     平行窗口
 *   - fixed_orientation_list.xml / fixed_orientation_list_projection.xml  固定横屏
 *   - autoui_list.xml / autoui_list_projection.xml             界面自动适配
 *
 * 部分机型（如 pudding / OS4.0）只提供 `*_projection.xml` 版本，故使用候选列表。
 */
object SystemRuleSource {

    enum class Kind { EMBEDDING, FIXED, AUTO_UI }

    /** 解析结果：包名 → 属性表 */
    private val tables = mutableMapOf<Kind, Map<String, Map<String, String>>>()

    /** 读取过程中出现的问题（例如无读取权限），非空时 UI 上给出提示 */
    @Volatile
    var errorMessage: String? = null
        private set

    @Volatile
    var loaded = false
        private set

    /** 同步加载，调用方需保证在后台线程。重复调用直接返回。 */
    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val missing = mutableListOf<String>()
            tables[Kind.EMBEDDING] = parse(Constants.FILES_EMBEDDED_RULES, missing)
            tables[Kind.FIXED] = parse(Constants.FILES_FIXED_ORI_RULES, missing)
            tables[Kind.AUTO_UI] = parse(Constants.FILES_AUTO_UI_RULES, missing)
            errorMessage = if (missing.isEmpty()) null else missing.joinToString("、")
            loaded = true
        }
    }

    private fun parse(fileNames: List<String>, missing: MutableList<String>): Map<String, Map<String, String>> {
        for (fileName in fileNames) {
            val file = Constants.SYSTEM_RULE_DIRS
                .map { File(it, fileName) }
                .firstOrNull { it.canRead() }
            if (file == null) continue
            val result = HashMap<String, Map<String, String>>()
            try {
                file.inputStream().use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, null)
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && parser.name == "package") {
                            val attrs = HashMap<String, String>(parser.attributeCount)
                            var name: String? = null
                            for (i in 0 until parser.attributeCount) {
                                val key = parser.getAttributeName(i)
                                val value = parser.getAttributeValue(i)
                                if (key == "name") name = value else attrs[key] = value
                            }
                            if (!name.isNullOrEmpty()) result[name] = attrs
                        }
                        event = parser.next()
                    }
                }
            } catch (t: Throwable) {
                continue // 这个文件解析失败，试下一个候选名
            }
            return result // 找到第一个可读且可解析的文件即返回
        }
        // 所有候选文件名都不可读
        missing += fileNames.joinToString("/")
        return emptyMap()
    }

    fun has(kind: Kind, pkg: String): Boolean = tables[kind]?.containsKey(pkg) == true

    fun attrsOf(kind: Kind, pkg: String): Map<String, String> =
        tables[kind]?.get(pkg) ?: emptyMap()

    /** 固定横屏名单里被 ROM 标记为 disable="true" 的条目（197 条），需覆盖才能生效 */
    fun isFixedDisabled(pkg: String): Boolean =
        attrsOf(Kind.FIXED, pkg)["disable"] == "true"

    /** 某个应用被哪些内置规则覆盖，用于列表徽标，例如「平行窗口 · 固定横屏」 */
    fun kindsOf(pkg: String): List<Kind> = Kind.entries.filter { has(it, pkg) }

    fun anyOf(pkg: String): Boolean = kindsOf(pkg).isNotEmpty()

    /**
     * 用系统内置值填充一条尚未被用户保存过的规则，让「先看到系统当前行为，再改」。
     * 只写内置文件里真实存在的属性，其余保持 [AppRule] 默认值。
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
                "supportCameraPreview" -> rule.supportCameraPreview = v.toBool()
                "relaunch" -> rule.relaunch = v.toBool()
                "clearTop" -> rule.clearTop = v.toBool()
                "finishSecondaryWithPrimary" -> rule.finishSecondaryWithPrimary = v.toBool()
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
                "minSupportVersion" -> rule.minSupportVersion = v
                "flags" -> rule.flags = v
                "procCompat" -> rule.procCompat = v
                "autoUiRule" -> rule.autoUiRule = v
                "defaultSettings" -> rule.defaultSettings = v
            }
        }

        fixed.forEach { (k, v) ->
            when (k) {
                "supportModes" -> rule.foSupportModes = v
                "defaultSettings" -> rule.foDefaultSettings = v
                "compatChange" -> rule.foCompatChange = v
                "relaunch" -> rule.foRelaunch = v.toBool()
                "supportFullSize" -> rule.foSupportFullSize = v.toBool()
                "supportCameraPreview" -> rule.foSupportCameraPreview = v.toBool()
                "isScale" -> rule.foIsScale = v.toBool()
                "skipCompatMode" -> rule.foSkipCompatMode = v.toBool()
                "allowEmbInPortrait" -> rule.foAllowEmbInPortrait = v.toBool()
                "forceKillWhenSwitch" -> rule.foForceKillWhenSwitch = v.toBool()
                "forcePortraitActivity" -> rule.foForcePortraitActivity = v
                "fullForcePortraitActivity" -> rule.foFullForcePortraitActivity = v
            }
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

    private fun String.toBool(): Boolean = equals("true", ignoreCase = true) || this == "1"
}
