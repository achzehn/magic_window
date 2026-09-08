package com.github.lsposed.magicwindow.common.model

/**
 * 横屏主模式。
 *
 * 依据资料评估 4.5.5：三套机制互斥且存在硬优先级
 *   固定横屏（信箱 / fixed orientation） > 平行窗口（embedding） > 通用规则（generic / fullScreen）
 * MiuiGenericController.createGenericRule 中存在短路返回，一旦命中高优先级机制，低优先级不再生效。
 * 因此 UI 必须做单选而非多选。
 */
enum class WindowMode(val key: String) {
    /** 不处理该应用，保持系统原始行为 */
    OFF("off"),

    /** 通用规则 / 全屏拉伸（优先级最低） */
    FULL_SCREEN("fullScreen"),

    /** 平行窗口（优先级居中） */
    EMBEDDING("embedding"),

    /** 固定横屏 · 信箱模式（优先级最高） */
    FIXED_ORIENTATION("fixedOrientation");

    /** 优先级权重，数值越大越优先。用于冲突判定时自动修正。 */
    val priority: Int
        get() = when (this) {
            OFF -> -1
            FULL_SCREEN -> 0
            EMBEDDING -> 1
            FIXED_ORIENTATION -> 2
        }

    companion object {
        fun from(key: String?): WindowMode =
            entries.firstOrNull { it.key == key } ?: OFF

        /**
         * 由三个用户开关反推主模式（按优先级取最高的那个）。
         * 对应 4.10.5 中 embedded_setting_config.xml 的开关语义。
         */
        fun fromSwitches(
            embedded: Boolean,
            fixedOrientation: Boolean,
            fullScreen: Boolean
        ): WindowMode = when {
            fixedOrientation -> FIXED_ORIENTATION
            embedded -> EMBEDDING
            fullScreen -> FULL_SCREEN
            else -> OFF
        }
    }
}
