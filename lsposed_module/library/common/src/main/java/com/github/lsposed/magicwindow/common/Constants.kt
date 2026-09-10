package com.github.lsposed.magicwindow.common

/**
 * 全局常量。
 *
 * 类名、方法名、字段名、文件名均取自《完美横屏_LSPosed实现资料评估.md》4.5 ~ 4.10 章的反编译实证，
 * 不要凭猜测修改。
 */
object Constants {

    const val MODULE_PACKAGE = "com.github.lsposed.magicwindow"
    const val SYSTEM_PACKAGE = "android"
    const val LOG_TAG = "MagicWindow"

    /** 模块配置文件名（LSPosed `xposedsharedprefs` 托管，system_server 侧用 XSharedPreferences 读） */
    const val PREFS_NAME = "magic_window_config"
    const val KEY_GLOBAL = "global_config"
    const val KEY_RULES = "app_rules"

    // ── 模块自身：激活状态回显 ──
    const val CLASS_MODULE_STATUS = "com.github.lsposed.magicwindow.ModuleStatus"
    const val M_IS_MODULE_ACTIVE = "isModuleActive"

    // ── 4.8.4 三个 SystemProperty 总开关 ──
    const val PROP_ACTIVITY_EMBEDDING = "ro.config.miui_activity_embedding_enable"
    const val PROP_AUTO_UI = "persist.miui.auto_ui_enable"
    const val PROP_CHARACTERISTICS = "ro.build.characteristics"

    // ── 4.5 平行窗口 / 固定横屏（miui-embedding-window.jar，插件 ClassLoader） ──
    const val CLASS_EWS = "com.android.server.wm.MiuiEmbeddingWindowService"
    const val CLASS_EWS_LOADER = "com.android.server.wm.MiuiEmbeddingWindowServiceLoader"
    const val CLASS_EWS_IMPL_COLLECTOR = "com.android.server.wm.MiuiEmbeddingWindowServiceImplCollector"
    const val CLASS_SYSTEM_EMBEDDED_RULE = "com.android.server.wm.MiuiSystemEmbeddedRule"
    const val CLASS_PARSING_EMBEDDED_RULE = "com.android.server.wm.MiuiParsingEmbeddedRule"
    const val CLASS_FIXED_ORI_CONTROLLER = "com.android.server.wm.MiuiFixedOrientationController"

    const val M_IS_EMBEDDING_LISTED = "isEmbeddingListedForPackage"
    const val M_IS_FIXED_ORI_LISTED = "isFixedOrientationListedForPackage"
    const val M_IS_EMBEDDING_ENABLED = "isEmbeddingEnabledForPackage"
    const val M_IS_EMBEDDING_ENABLED_INCL_ADAPT = "isEmbeddingEnabledForPackageIncludeAdaptApp"
    const val M_QUERY_EMBEDDING_FROM_RULES = "queryEmbeddingEnableFormRulesList"
    const val M_GET_FIX_ORI_RULES = "getFixOrientationRules"
    const val M_HAS_PACKAGE_RULE = "hasPackageRule"
    const val M_CLEAR_EMBEDDED_RULE = "clearEmbeddedRule"
    const val M_PARSE_SINGLE_PACKAGE = "parseSinglePackage"

    /** 4.5.2 用户开关文件：/data/system/users/<userId>/embedded_setting_config.xml */
    const val SETTING_CONFIG_FILE_NAME = "embedded_setting_config.xml"

    // ── 4.10.1 系统内置规则文件（不同机型目录 + 文件名都有分叉，按顺序探测） ──

    /** 云控规则目录（system_server 写出，普通应用无权读，需 root） */
    const val CLOUD_RULE_DIR = "/data/system/"

    val SYSTEM_RULE_DIRS = listOf("/product/etc/", "/system_ext/etc/", "/system/etc/")

    /**
     * 平行窗口内置名单候选文件名。
     * 部分机型（如实测的 pudding / OS4.0）只提供 `*_projection.xml`，没有不带后缀的版本。
     */
    val FILES_EMBEDDED_RULES = listOf("embedded_rules_list.xml", "embedded_rules_list_projection.xml")
    val FILES_FIXED_ORI_RULES = listOf("fixed_orientation_list.xml", "fixed_orientation_list_projection.xml")
    val FILES_AUTO_UI_RULES = listOf("autoui_list.xml", "autoui_list_projection.xml")

    /** /data/system 下的云控名单候选文件名，与上面的本地名单一一对应 */
    val FILES_CLOUD_EMBEDDED_RULES = listOf(
        "cloudFeature_embedded_rules_list.xml",
        "cloudFeature_embedded_rules_list_projection.xml"
    )
    val FILES_CLOUD_FIXED_ORI_RULES = listOf(
        "cloudFeature_fixed_orientation_list.xml",
        "cloudFeature_fixed_orientation_list_projection.xml"
    )
    val FILES_CLOUD_AUTO_UI_RULES = listOf(
        "cloudFeature_autoui_list.xml",
        "cloudFeature_autoui_list_projection.xml"
    )

    // ── 页面抓取工具 ──
    const val CAPTURE_AUTHORITY = "com.github.lsposed.magicwindow.capture"
    const val CAPTURE_PATH = "record"
    const val CAPTURE_COL_PACKAGE = "pkg"
    const val CAPTURE_COL_ACTIVITY = "activity"
    const val CLASS_ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord"
    const val F_ACTIVITY_COMPONENT = "mActivityComponent"

    // ── 4.10.5 用户开关 XML 的 4 个属性 ──
    const val ATTR_EMBEDDED_ENABLE = "embeddedEnable"
    const val ATTR_FIXED_ORI_ENABLE = "fixedOrientationEnable"
    const val ATTR_FULL_SCREEN_ENABLE = "fullScreenEnable"
    const val ATTR_RATIO_FULL_SCREEN_ENABLE = "ratio_fullScreenEnable"

    // ── 4.6 / 4.9 autoui（miui-services.autoui.jar + miui-framework.jar） ──
    const val CLASS_PARSING_AUTO_UI = "miui.autoui.MiuiParsingAutoUI"
    const val CLASS_SYSTEM_AUTO_UI_RULE = "miui.autoui.MiuiSystemAutoUIRule"
    const val CLASS_AUTO_UI_SERVICE_IMPL = "miui.autoui.MiuiAutoUIServiceImpl"
    const val CLASS_AUTO_UI_PACKAGE_RULE = "miui.autoui.policy.rule.PackageRule"

    const val F_LAST_CLOUD_CONFIG_VERSION = "mLastCloudConfigVersion"
    const val M_UPDATE_CLOUD_CONFIG_FILE = "updateAutoUICloudConfigFile"
    const val M_UPDATE_FROM_CLOUD_FILE = "updateAutoUIConfigFromCloudFile"
    const val M_CREATE_CLOUD_AUTO_UI_RULE = "createCloudAutoUIRule"
    const val M_LOAD_PACKAGE = "loadPackage"

    const val CLOUD_AUTO_UI_FILE = "cloudFeature_autoui_list.xml"
    const val AUTO_UI_CONTROL_MODULE = "autoui_application_config"
    const val CLOUD_KEY_DATA_VERSION = "dataVersion"

    /** 4.10.3 真机 /product/etc/autoui_list.xml 实测 dataVersion，抬高值必须大于它 */
    const val LOCAL_AUTO_UI_DATA_VERSION = 360816L

    /** 默认抬高值（4.9.4 / 6.3 第二步） */
    const val DEFAULT_CLOUD_DATA_VERSION = 99999999L

    // ── 4.9.5 云控数据入口 ──
    const val CLASS_SETTINGS_CLOUD_DATA = "android.provider.MiuiSettings\$SettingsCloudData"
    const val M_GET_CLOUD_DATA_STRING = "getCloudDataString"

    // ── 4.8.4 总开关兜底 hook 落点 ──
    const val CLASS_AE_PROP =
        "miui.thirdappadaptation.MiuiAppAdaptationProperties\$ActivityEmbeddingProp"
    const val M_IS_AE_ENABLED = "isActivityEmbeddingEnabled"
    const val CLASS_AUTO_UI_MANAGER_STUB = "miui.autoui.MIUIAutoUIManagerStub"
    const val F_IS_AUTO_UI_ENABLED = "IS_AUTO_UI_ENABLED"
}
