package com.github.lsposed.magicwindow.common.model

import org.json.JSONObject

/**
 * 单个应用的完整规则。
 *
 * 字段来源：资料评估 4.10.4「规则属性字典」（embedding 侧 25+ 属性 / fixed orientation 侧 15 属性）
 * 与 4.6「autoui 规则」。所有属性均可在 UI 中修改。
 *
 * 空字符串表示「不写入该属性」，交由系统默认值处理。
 */
data class AppRule(
    val packageName: String,

    // ── 通用 ─────────────────────────────────────────────
    /** 该应用是否启用本模块的注入 */
    var enabled: Boolean = true,
    /** 主模式（互斥单选，见 WindowMode） */
    var mode: WindowMode = WindowMode.EMBEDDING,

    // ── 平行窗口 embedding ───────────────────────────────
    var supportFullSize: Boolean = true,
    var isShowDivider: Boolean = true,
    /** 实测 100% 必带，恒为 true */
    var skipSelfAdaptive: Boolean = true,
    var splitRatio: String = "",
    var activityRule: String = "",
    var splitPairRule: String = "",
    var placeholder: String = "",
    var fullRule: String = "",
    var scaleMode: String = "",
    var middleRule: String = "",
    var transitionRules: String = "",
    var splitLineColor: String = "",
    var forcePortraitActivity: String = "",
    var supportCameraPreview: Boolean = false,
    var relaunch: Boolean = false,
    var clearTop: Boolean = false,
    var finishSecondaryWithPrimary: Boolean = false,
    var splitMinWidth: String = "",
    var minSupportVersion: String = "",
    var flags: String = "",
    var procCompat: String = "",
    var autoUiRule: String = "",
    var defaultSettings: String = "",

    // ── 固定横屏 fixed orientation ───────────────────────
    /** 实测恒为 "full,fo" */
    var foSupportModes: String = "full,fo",
    /** full 或 fo */
    var foDefaultSettings: String = "fo",
    var foRelaunch: Boolean = false,
    var foSupportFullSize: Boolean = true,
    var foSupportCameraPreview: Boolean = false,
    var foCompatChange: String = "",
    var foIsScale: Boolean = false,
    var foSkipCompatMode: Boolean = false,
    var foForcePortraitActivity: String = "",
    var foFullForcePortraitActivity: String = "",
    var foAllowEmbInPortrait: Boolean = false,
    var foForceKillWhenSwitch: Boolean = false,
    /** 覆盖系统内置 197 条 disable="true"（需 A 档查询 hook 配合） */
    var foOverrideDisable: Boolean = true,

    // ── autoui ───────────────────────────────────────────
    var autoUiEnable: Boolean = false,
    var autoUiOptimizeWebView: Boolean = false,
    var autoUiActivityRule: String = "",
    var autoUiSkippedActivityRule: String = "",
    var autoUiSkippedAppConfigChange: String = "",
    var autoUiVersionCode: String = "1",

    // ── 用户开关覆写（4.10.5） ───────────────────────────
    /** 打开后不再由 mode 推导用户开关，改用下面三个手动值 */
    var overrideUserSwitch: Boolean = false,
    var swEmbedded: Boolean = false,
    var swFixedOrientation: Boolean = false,
    /** UI 上单个开关，写入时 fullScreenEnable 与 ratio_fullScreenEnable 成对落盘 */
    var swFullScreen: Boolean = false
) {

    /** 最终写入 embedded_setting_config.xml 的三个开关值（fullScreen 需成对写两个属性） */
    fun resolveSwitches(): Triple<Boolean, Boolean, Boolean> =
        if (overrideUserSwitch) {
            Triple(swEmbedded, swFixedOrientation, swFullScreen)
        } else {
            when (mode) {
                WindowMode.OFF -> Triple(false, false, false)
                WindowMode.FULL_SCREEN -> Triple(false, false, true)
                WindowMode.EMBEDDING -> Triple(true, false, false)
                WindowMode.FIXED_ORIENTATION -> Triple(false, true, false)
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("enabled", enabled)
        put("mode", mode.key)

        put("supportFullSize", supportFullSize)
        put("isShowDivider", isShowDivider)
        put("skipSelfAdaptive", skipSelfAdaptive)
        put("splitRatio", splitRatio)
        put("activityRule", activityRule)
        put("splitPairRule", splitPairRule)
        put("placeholder", placeholder)
        put("fullRule", fullRule)
        put("scaleMode", scaleMode)
        put("middleRule", middleRule)
        put("transitionRules", transitionRules)
        put("splitLineColor", splitLineColor)
        put("forcePortraitActivity", forcePortraitActivity)
        put("supportCameraPreview", supportCameraPreview)
        put("relaunch", relaunch)
        put("clearTop", clearTop)
        put("finishSecondaryWithPrimary", finishSecondaryWithPrimary)
        put("splitMinWidth", splitMinWidth)
        put("minSupportVersion", minSupportVersion)
        put("flags", flags)
        put("procCompat", procCompat)
        put("autoUiRule", autoUiRule)
        put("defaultSettings", defaultSettings)

        put("foSupportModes", foSupportModes)
        put("foDefaultSettings", foDefaultSettings)
        put("foRelaunch", foRelaunch)
        put("foSupportFullSize", foSupportFullSize)
        put("foSupportCameraPreview", foSupportCameraPreview)
        put("foCompatChange", foCompatChange)
        put("foIsScale", foIsScale)
        put("foSkipCompatMode", foSkipCompatMode)
        put("foForcePortraitActivity", foForcePortraitActivity)
        put("foFullForcePortraitActivity", foFullForcePortraitActivity)
        put("foAllowEmbInPortrait", foAllowEmbInPortrait)
        put("foForceKillWhenSwitch", foForceKillWhenSwitch)
        put("foOverrideDisable", foOverrideDisable)

        put("autoUiEnable", autoUiEnable)
        put("autoUiOptimizeWebView", autoUiOptimizeWebView)
        put("autoUiActivityRule", autoUiActivityRule)
        put("autoUiSkippedActivityRule", autoUiSkippedActivityRule)
        put("autoUiSkippedAppConfigChange", autoUiSkippedAppConfigChange)
        put("autoUiVersionCode", autoUiVersionCode)

        put("overrideUserSwitch", overrideUserSwitch)
        put("swEmbedded", swEmbedded)
        put("swFixedOrientation", swFixedOrientation)
        put("swFullScreen", swFullScreen)
    }

    companion object {
        fun fromJson(o: JSONObject): AppRule {
            val d = AppRule(o.optString("packageName"))
            return d.apply {
                enabled = o.optBoolean("enabled", d.enabled)
                mode = WindowMode.from(o.optString("mode", d.mode.key))

                supportFullSize = o.optBoolean("supportFullSize", d.supportFullSize)
                isShowDivider = o.optBoolean("isShowDivider", d.isShowDivider)
                skipSelfAdaptive = o.optBoolean("skipSelfAdaptive", d.skipSelfAdaptive)
                splitRatio = o.optString("splitRatio", d.splitRatio)
                activityRule = o.optString("activityRule", d.activityRule)
                splitPairRule = o.optString("splitPairRule", d.splitPairRule)
                placeholder = o.optString("placeholder", d.placeholder)
                fullRule = o.optString("fullRule", d.fullRule)
                scaleMode = o.optString("scaleMode", d.scaleMode)
                middleRule = o.optString("middleRule", d.middleRule)
                transitionRules = o.optString("transitionRules", d.transitionRules)
                splitLineColor = o.optString("splitLineColor", d.splitLineColor)
                forcePortraitActivity = o.optString("forcePortraitActivity", d.forcePortraitActivity)
                supportCameraPreview = o.optBoolean("supportCameraPreview", d.supportCameraPreview)
                relaunch = o.optBoolean("relaunch", d.relaunch)
                clearTop = o.optBoolean("clearTop", d.clearTop)
                finishSecondaryWithPrimary =
                    o.optBoolean("finishSecondaryWithPrimary", d.finishSecondaryWithPrimary)
                splitMinWidth = o.optString("splitMinWidth", d.splitMinWidth)
                minSupportVersion = o.optString("minSupportVersion", d.minSupportVersion)
                flags = o.optString("flags", d.flags)
                procCompat = o.optString("procCompat", d.procCompat)
                autoUiRule = o.optString("autoUiRule", d.autoUiRule)
                defaultSettings = o.optString("defaultSettings", d.defaultSettings)

                foSupportModes = o.optString("foSupportModes", d.foSupportModes)
                foDefaultSettings = o.optString("foDefaultSettings", d.foDefaultSettings)
                foRelaunch = o.optBoolean("foRelaunch", d.foRelaunch)
                foSupportFullSize = o.optBoolean("foSupportFullSize", d.foSupportFullSize)
                foSupportCameraPreview =
                    o.optBoolean("foSupportCameraPreview", d.foSupportCameraPreview)
                foCompatChange = o.optString("foCompatChange", d.foCompatChange)
                foIsScale = o.optBoolean("foIsScale", d.foIsScale)
                foSkipCompatMode = o.optBoolean("foSkipCompatMode", d.foSkipCompatMode)
                foForcePortraitActivity =
                    o.optString("foForcePortraitActivity", d.foForcePortraitActivity)
                foFullForcePortraitActivity =
                    o.optString("foFullForcePortraitActivity", d.foFullForcePortraitActivity)
                foAllowEmbInPortrait = o.optBoolean("foAllowEmbInPortrait", d.foAllowEmbInPortrait)
                foForceKillWhenSwitch =
                    o.optBoolean("foForceKillWhenSwitch", d.foForceKillWhenSwitch)
                foOverrideDisable = o.optBoolean("foOverrideDisable", d.foOverrideDisable)

                autoUiEnable = o.optBoolean("autoUiEnable", d.autoUiEnable)
                autoUiOptimizeWebView = o.optBoolean("autoUiOptimizeWebView", d.autoUiOptimizeWebView)
                autoUiActivityRule = o.optString("autoUiActivityRule", d.autoUiActivityRule)
                autoUiSkippedActivityRule =
                    o.optString("autoUiSkippedActivityRule", d.autoUiSkippedActivityRule)
                autoUiSkippedAppConfigChange =
                    o.optString("autoUiSkippedAppConfigChange", d.autoUiSkippedAppConfigChange)
                autoUiVersionCode = o.optString("autoUiVersionCode", d.autoUiVersionCode)

                overrideUserSwitch = o.optBoolean("overrideUserSwitch", d.overrideUserSwitch)
                swEmbedded = o.optBoolean("swEmbedded", d.swEmbedded)
                swFixedOrientation = o.optBoolean("swFixedOrientation", d.swFixedOrientation)
                swFullScreen = o.optBoolean("swFullScreen", d.swFullScreen)
            }
        }
    }
}
