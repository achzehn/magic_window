package com.github.lsposed.magicwindow.common.model

import org.json.JSONObject

/**
 * 全局配置（总开关 + 各 Hook 步骤的启用控制）。
 *
 * 步骤编号对应资料评估 6.3「一期实现骨架」：
 *   第 0 步 校验总开关 property → verifyGates / forceActivityEmbedding / forceAutoUi
 *   第一步 捕获插件 ClassLoader → （始终执行）
 *   第二步 autoui 云控注入 → autoUiCloudInject / cloudDataVersion / hookCloudDataString
 *   第三步 A 档查询 hook 兜底 → queryHookFallback / overrideSystemDisable
 *   第四步 批量翻转用户开关 → flipUserSwitches
 */
data class GlobalConfig(
    /** 第 0 步：启动时校验三个 SystemProperty 前置条件并打日志 */
    var verifyGates: Boolean = true,
    /** 第 0 步：强制写 ro.config.miui_activity_embedding_enable=true */
    var forceActivityEmbedding: Boolean = true,
    /** 第 0 步：强制写 persist.miui.auto_ui_enable=true */
    var forceAutoUi: Boolean = true,
    /** 第四步：按应用规则批量翻转 embedded_setting_config.xml 用户开关（运行时热路径 hook，
     *  云控方案已由 onAppSwitchChanged 主动翻转，默认关） */
    var flipUserSwitches: Boolean = false,
    /** 第三步：hook isEmbeddingListedForPackage / isFixedOrientationListedForPackage 兜底
     *  （运行时热路径 hook，云控方案下默认关） */
    var queryHookFallback: Boolean = false,
    /** 第三步：连 isEmbeddingEnabledForPackage 等「是否已开启」查询也一并谎报（热路径，默认关） */
    var enableQueryHook: Boolean = false,
    /** 第三步：把固定横屏规则直接注入系统内部规则表并拦截清理（getter 热路径，默认关） */
    var ruleTableInject: Boolean = false,
    /** 第三步：覆盖系统内置 197 条 disable="true" 的固定横屏规则（热路径，默认关） */
    var overrideSystemDisable: Boolean = false,
    /** 第二步：autoui 云控注入（方案 C，主路径） */
    var autoUiCloudInject: Boolean = true,
    /** 第二步：平行窗口云控注入（方案 2 终态，主路径，写 cloudFeature_embedded_rules_list.xml） */
    var embeddedCloudInject: Boolean = true,
    /** 第二步：固定横屏云控注入（方案 2 终态，主路径，写 cloudFeature_fixed_orientation_list.xml） */
    var fixedCloudInject: Boolean = true,
    /** 抬高云控版本号，必须 > 360816（4.10.3） */
    var cloudDataVersion: Long = 99999999L,
    /** 附加：hook MiuiSettings$SettingsCloudData.getCloudDataString 兜底（风险较高，默认关） */
    var hookCloudDataString: Boolean = false,
    /** 页面抓取工具：在 system_server 侧记录 Activity 启动，供 UI 一键填入 */
    var captureEnabled: Boolean = false,
    /** 详细日志 */
    var verboseLog: Boolean = false,
    /** 详情页是否用简单模式（只显示常用项）；false 显示完整参数。仅 UI 偏好，不影响 hook */
    var detailSimpleMode: Boolean = true
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("verifyGates", verifyGates)
        put("forceActivityEmbedding", forceActivityEmbedding)
        put("forceAutoUi", forceAutoUi)
        put("flipUserSwitches", flipUserSwitches)
        put("queryHookFallback", queryHookFallback)
        put("enableQueryHook", enableQueryHook)
        put("ruleTableInject", ruleTableInject)
        put("overrideSystemDisable", overrideSystemDisable)
        put("autoUiCloudInject", autoUiCloudInject)
        put("embeddedCloudInject", embeddedCloudInject)
        put("fixedCloudInject", fixedCloudInject)
        put("cloudDataVersion", cloudDataVersion)
        put("hookCloudDataString", hookCloudDataString)
        put("captureEnabled", captureEnabled)
        put("verboseLog", verboseLog)
        put("detailSimpleMode", detailSimpleMode)
    }

    companion object {
        fun fromJson(o: JSONObject): GlobalConfig {
            val d = GlobalConfig()
            return d.apply {
                verifyGates = o.optBoolean("verifyGates", d.verifyGates)
                forceActivityEmbedding =
                    o.optBoolean("forceActivityEmbedding", d.forceActivityEmbedding)
                forceAutoUi = o.optBoolean("forceAutoUi", d.forceAutoUi)
                flipUserSwitches = o.optBoolean("flipUserSwitches", d.flipUserSwitches)
                queryHookFallback = o.optBoolean("queryHookFallback", d.queryHookFallback)
                enableQueryHook = o.optBoolean("enableQueryHook", d.enableQueryHook)
                ruleTableInject = o.optBoolean("ruleTableInject", d.ruleTableInject)
                overrideSystemDisable =
                    o.optBoolean("overrideSystemDisable", d.overrideSystemDisable)
                autoUiCloudInject = o.optBoolean("autoUiCloudInject", d.autoUiCloudInject)
                embeddedCloudInject = o.optBoolean("embeddedCloudInject", d.embeddedCloudInject)
                fixedCloudInject = o.optBoolean("fixedCloudInject", d.fixedCloudInject)
                cloudDataVersion = o.optLong("cloudDataVersion", d.cloudDataVersion)
                hookCloudDataString = o.optBoolean("hookCloudDataString", d.hookCloudDataString)
                captureEnabled = o.optBoolean("captureEnabled", d.captureEnabled)
                verboseLog = o.optBoolean("verboseLog", d.verboseLog)
                detailSimpleMode = o.optBoolean("detailSimpleMode", d.detailSimpleMode)
            }
        }
    }
}
