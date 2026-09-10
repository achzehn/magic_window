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

    // ── 平行窗口：新版 JAR 新增属性 ───────────────────────
    var disableSensor: Boolean = false,
    var allowRepeatPage: Boolean = false,
    var finishPrimaryWithSecondary: Boolean = false,
    var isShowDialog: Boolean = false,
    var useMiuiSplit: Boolean = false,
    var miuiMagicWinEnabled: Boolean = false,
    var disableCameraPreview: Boolean = false,
    var embForceKillWhenSwitch: Boolean = false,
    var splitMinSmallestWidth: String = "",
    var layoutDirection: String = "",
    var killApps: String = "",
    var forcePortraitWhenSwitch: String = "",
    var sizecompatRatio: String = "",
    var sizecompatRule: String = "",
    var transparentBar: String = "",
    var embAdaptCutout: String = "",
    var embRelaunchRule: String = "",
    /** 规则版本，影响云控更新优先级 */
    var version: String = "",

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
    /** 禁用（直接控制是否生效，与 foOverrideDisable 互补） */
    var foDisable: Boolean = false,
    /** 禁用相机预览（黑名单语义，与 foSupportCameraPreview 互补） */
    var foDisableCameraPreview: String = "",

    // ── 固定横屏：新版 JAR 新增属性 ───────────────────────
    /** 旋转方向调整，"0" 不调整 / "1" 调整（默认 0） */
    var foAdjustmentOrientation: String = "",
    /** 活动级旋转调整，格式 包名/类名:1，多个逗号隔开 */
    var foAdjustmentOrientationActivity: String = "",
    /** 宽高比 1.x~2.x；系统默认 "0" 表示不限制 */
    var foRatio: String = "",
    /** 所有页面都竖屏显示 */
    var foAllPortrait: Boolean = false,
    /** 重启规则，格式 DefaultScenario:true:页面名 */
    var foRelaunchRule: String = "",
    /** 顺带启用的界面适配（autoUI） */
    var foAutoUI: Boolean = false,
    /** 透明导航栏，系统默认 true；用 String 以便区分「不设置」 */
    var foTransparentBar: String = "",
    /** 挖孔屏适配，系统默认 -1 */
    var foAdaptCutout: String = "",
    var foIsShowDivider: Boolean = false,
    var foSkipSelfAdaptive: Boolean = false,

    // ── autoui ───────────────────────────────────────────
    var autoUiEnable: Boolean = false,
    var autoUiOptimizeWebView: Boolean = false,
    var autoUiActivityRule: String = "",
    var autoUiSkippedActivityRule: String = "",
    var autoUiSkippedAppConfigChange: String = "",
    var autoUiVersionCode: String = "1",
    /** 描述（便于管理） */
    var autoUiDescribe: String = "",

    // ── 手动系统开关（不再根据 mode 自动推导） ───────────────────────────
    /** 平行窗口开关 */
    var swEmbedded: Boolean = false,
    /** 固定横屏开关 */
    var swFixedOrientation: Boolean = false,
    /** 全屏开关 */
    var swFullScreen: Boolean = false,
    /** 4:3 比例开关 */
    var ratio43Enable: Boolean = false,
    /** 16:9 比例开关 */
    var ratio169Enable: Boolean = false,
    /** 全屏比例开关 */
    var ratioFullScreenEnable: Boolean = false
) {

    /**
     * 最终写入 embedded_setting_config.xml 的三个开关值。
     * **仅返回手动设置的开关值，不再根据 mode 自动推导**。
     * 用户需手动设置 swEmbedded、swFixedOrientation、swFullScreen。
     */
    fun resolveSwitches(): Triple<Boolean, Boolean, Boolean> =
        Triple(swEmbedded, swFixedOrientation, swFullScreen)

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

        put("disableSensor", disableSensor)
        put("allowRepeatPage", allowRepeatPage)
        put("finishPrimaryWithSecondary", finishPrimaryWithSecondary)
        put("isShowDialog", isShowDialog)
        put("useMiuiSplit", useMiuiSplit)
        put("miuiMagicWinEnabled", miuiMagicWinEnabled)
        put("disableCameraPreview", disableCameraPreview)
        put("embForceKillWhenSwitch", embForceKillWhenSwitch)
        put("splitMinSmallestWidth", splitMinSmallestWidth)
        put("layoutDirection", layoutDirection)
        put("killApps", killApps)
        put("forcePortraitWhenSwitch", forcePortraitWhenSwitch)
        put("sizecompatRatio", sizecompatRatio)
        put("sizecompatRule", sizecompatRule)
        put("transparentBar", transparentBar)
        put("embAdaptCutout", embAdaptCutout)
        put("embRelaunchRule", embRelaunchRule)
        put("version", version)

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
        put("foDisable", foDisable)
        put("foDisableCameraPreview", foDisableCameraPreview)
        put("foAdjustmentOrientation", foAdjustmentOrientation)
        put("foAdjustmentOrientationActivity", foAdjustmentOrientationActivity)
        put("foRatio", foRatio)
        put("foAllPortrait", foAllPortrait)
        put("foRelaunchRule", foRelaunchRule)
        put("foAutoUI", foAutoUI)
        put("foTransparentBar", foTransparentBar)
        put("foAdaptCutout", foAdaptCutout)
        put("foIsShowDivider", foIsShowDivider)
        put("foSkipSelfAdaptive", foSkipSelfAdaptive)

        put("autoUiEnable", autoUiEnable)
        put("autoUiOptimizeWebView", autoUiOptimizeWebView)
        put("autoUiActivityRule", autoUiActivityRule)
        put("autoUiSkippedActivityRule", autoUiSkippedActivityRule)
        put("autoUiSkippedAppConfigChange", autoUiSkippedAppConfigChange)
        put("autoUiVersionCode", autoUiVersionCode)
        put("autoUiDescribe", autoUiDescribe)

        put("swEmbedded", swEmbedded)
        put("swFixedOrientation", swFixedOrientation)
        put("swFullScreen", swFullScreen)
        put("ratio43Enable", ratio43Enable)
        put("ratio169Enable", ratio169Enable)
        put("ratioFullScreenEnable", ratioFullScreenEnable)
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
                // splitRatio 必须是数字字符串（如 "0.5"），过滤掉误存的 "false"
                splitRatio = o.optString("splitRatio", d.splitRatio).takeIf {
                    it != "false" && it != "true"
                } ?: ""
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

                disableSensor = o.optBoolean("disableSensor", d.disableSensor)
                allowRepeatPage = o.optBoolean("allowRepeatPage", d.allowRepeatPage)
                finishPrimaryWithSecondary =
                    o.optBoolean("finishPrimaryWithSecondary", d.finishPrimaryWithSecondary)
                isShowDialog = o.optBoolean("isShowDialog", d.isShowDialog)
                useMiuiSplit = o.optBoolean("useMiuiSplit", d.useMiuiSplit)
                miuiMagicWinEnabled = o.optBoolean("miuiMagicWinEnabled", d.miuiMagicWinEnabled)
                disableCameraPreview = o.optBoolean("disableCameraPreview", d.disableCameraPreview)
                embForceKillWhenSwitch =
                    o.optBoolean("embForceKillWhenSwitch", d.embForceKillWhenSwitch)
                splitMinSmallestWidth =
                    o.optString("splitMinSmallestWidth", d.splitMinSmallestWidth)
                layoutDirection = o.optString("layoutDirection", d.layoutDirection)
                killApps = o.optString("killApps", d.killApps)
                forcePortraitWhenSwitch =
                    o.optString("forcePortraitWhenSwitch", d.forcePortraitWhenSwitch)
                sizecompatRatio = o.optString("sizecompatRatio", d.sizecompatRatio)
                sizecompatRule = o.optString("sizecompatRule", d.sizecompatRule)
                transparentBar = o.optString("transparentBar", d.transparentBar)
                embAdaptCutout = o.optString("embAdaptCutout", d.embAdaptCutout)
                embRelaunchRule = o.optString("embRelaunchRule", d.embRelaunchRule)
                version = o.optString("version", d.version)

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
                foDisable = o.optBoolean("foDisable", d.foDisable)
                foDisableCameraPreview =
                    o.optString("foDisableCameraPreview", d.foDisableCameraPreview)
                foAdjustmentOrientation =
                    o.optString("foAdjustmentOrientation", d.foAdjustmentOrientation)
                foAdjustmentOrientationActivity =
                    o.optString("foAdjustmentOrientationActivity", d.foAdjustmentOrientationActivity)
                foRatio = o.optString("foRatio", d.foRatio)
                foAllPortrait = o.optBoolean("foAllPortrait", d.foAllPortrait)
                foRelaunchRule = o.optString("foRelaunchRule", d.foRelaunchRule)
                foAutoUI = o.optBoolean("foAutoUI", d.foAutoUI)
                foTransparentBar = o.optString("foTransparentBar", d.foTransparentBar)
                foAdaptCutout = o.optString("foAdaptCutout", d.foAdaptCutout)
                foIsShowDivider = o.optBoolean("foIsShowDivider", d.foIsShowDivider)
                foSkipSelfAdaptive = o.optBoolean("foSkipSelfAdaptive", d.foSkipSelfAdaptive)

                autoUiEnable = o.optBoolean("autoUiEnable", d.autoUiEnable)
                autoUiOptimizeWebView = o.optBoolean("autoUiOptimizeWebView", d.autoUiOptimizeWebView)
                autoUiActivityRule = o.optString("autoUiActivityRule", d.autoUiActivityRule)
                autoUiSkippedActivityRule =
                    o.optString("autoUiSkippedActivityRule", d.autoUiSkippedActivityRule)
                autoUiSkippedAppConfigChange =
                    o.optString("autoUiSkippedAppConfigChange", d.autoUiSkippedAppConfigChange)
                autoUiVersionCode = o.optString("autoUiVersionCode", d.autoUiVersionCode)
                autoUiDescribe = o.optString("autoUiDescribe", d.autoUiDescribe)

                swEmbedded = o.optBoolean("swEmbedded", d.swEmbedded)
                swFixedOrientation = o.optBoolean("swFixedOrientation", d.swFixedOrientation)
                swFullScreen = o.optBoolean("swFullScreen", d.swFullScreen)
                ratio43Enable = o.optBoolean("ratio43Enable", d.ratio43Enable)
                ratio169Enable = o.optBoolean("ratio169Enable", d.ratio169Enable)
                ratioFullScreenEnable = o.optBoolean("ratioFullScreenEnable", d.ratioFullScreenEnable)
            }
        }
    }
}
