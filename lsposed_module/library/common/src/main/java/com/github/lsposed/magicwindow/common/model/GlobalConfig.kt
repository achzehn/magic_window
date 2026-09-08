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
    /** 第四步：按应用规则批量翻转 embedded_setting_config.xml 用户开关 */
    var flipUserSwitches: Boolean = true,
    /** 第三步：hook isEmbeddingListedForPackage / isFixedOrientationListedForPackage 兜底 */
    var queryHookFallback: Boolean = true,
    /** 第三步：连 isEmbeddingEnabledForPackage 等「是否已开启」查询也一并谎报 */
    var enableQueryHook: Boolean = true,
    /** 第三步：把固定横屏规则直接注入系统内部规则表并拦截清理（依赖机型内部结构） */
    var ruleTableInject: Boolean = true,
    /** 第三步：覆盖系统内置 197 条 disable="true" 的固定横屏规则 */
    var overrideSystemDisable: Boolean = true,
    /** 第二步：autoui 云控注入（方案 C） */
    var autoUiCloudInject: Boolean = true,
    /** 抬高云控版本号，必须 > 360816（4.10.3） */
    var cloudDataVersion: Long = 99999999L,
    /** 附加：hook MiuiSettings$SettingsCloudData.getCloudDataString 兜底（风险较高，默认关） */
    var hookCloudDataString: Boolean = false,
    /** 页面抓取工具：在 system_server 侧记录 Activity 启动，供 UI 一键填入 */
    var captureEnabled: Boolean = false,
    /** 详细日志 */
    var verboseLog: Boolean = false
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
        put("cloudDataVersion", cloudDataVersion)
        put("hookCloudDataString", hookCloudDataString)
        put("captureEnabled", captureEnabled)
        put("verboseLog", verboseLog)
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
                cloudDataVersion = o.optLong("cloudDataVersion", d.cloudDataVersion)
                hookCloudDataString = o.optBoolean("hookCloudDataString", d.hookCloudDataString)
                captureEnabled = o.optBoolean("captureEnabled", d.captureEnabled)
                verboseLog = o.optBoolean("verboseLog", d.verboseLog)
            }
        }
    }
}
