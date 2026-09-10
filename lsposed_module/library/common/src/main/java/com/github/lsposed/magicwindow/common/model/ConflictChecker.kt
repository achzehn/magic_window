package com.github.lsposed.magicwindow.common.model

/**
 * 互斥优先级冲突判定。
 *
 * 依据资料评估 4.5.5：
 *   固定横屏（信箱） > 平行窗口 > 通用规则
 *   MiuiGenericController.createGenericRule 存在短路返回，命中高优先级后低优先级不再生效。
 * 依据 4.10.5：
 *   fullScreenEnable 与 ratio_fullScreenEnable 必须成对写入（真机各 1054 次，完全相等）。
 */
object ConflictChecker {

    enum class Level { NONE, WARN, ERROR }

    data class Issue(
        val level: Level,
        /** 冲突涉及的字段 key，供 UI 高亮定位 */
        val fields: List<String>,
        val message: String
    )

    data class Result(
        val issues: List<Issue>
    ) {
        val hasError: Boolean get() = issues.any { it.level == Level.ERROR }
        val hasWarn: Boolean get() = issues.any { it.level == Level.WARN }
        val isClean: Boolean get() = issues.isEmpty()

        /** 所有存在问题的字段（去重），UI 用它决定哪些控件加红/加黄边框 */
        val conflictFields: Set<String> get() = issues.flatMap { it.fields }.toSet()
    }

    fun check(rule: AppRule): Result {
        val issues = mutableListOf<Issue>()

        if (!rule.enabled) return Result(emptyList())

        // 检测三个手动开关是否同时开启多个（互斥）
        val on = listOf(
            "swFixedOrientation" to rule.swFixedOrientation,
            "swEmbedded" to rule.swEmbedded,
            "swFullScreen" to rule.swFullScreen
        ).filter { it.second }.map { it.first }

        if (on.size > 1) {
            val effective = WindowMode.fromSwitches(
                rule.swEmbedded, rule.swFixedOrientation, rule.swFullScreen
            )
            issues += Issue(
                Level.ERROR,
                on,
                "三套机制互斥：同时开启 ${on.size} 项，系统只会命中优先级最高的" +
                        "「${label(effective)}」，其余配置不会生效"
            )
        }

        if (on.isEmpty()) {
            issues += Issue(
                Level.WARN,
                listOf("swEmbedded", "swFixedOrientation", "swFullScreen"),
                "三个开关全为关，该应用等同于未启用"
            )
        }

        // 主模式与已填写的细项之间的矛盾
        when (rule.mode) {
            WindowMode.FIXED_ORIENTATION -> {
                if (hasEmbeddingDetail(rule)) {
                    issues += Issue(
                        Level.WARN,
                        embeddingDetailFields(rule),
                        "当前为固定横屏模式，优先级高于平行窗口，已填写的平行窗口参数不会生效"
                    )
                }
                if (rule.foDefaultSettings.isNotEmpty() &&
                    rule.foDefaultSettings !in setOf("full", "fo")
                ) {
                    issues += Issue(
                        Level.ERROR,
                        listOf("foDefaultSettings"),
                        "默认模式只能是 full 或 fo"
                    )
                }
                if (rule.foSupportModes.isNotEmpty() && rule.foSupportModes != "full,fo") {
                    issues += Issue(
                        Level.WARN,
                        listOf("foSupportModes"),
                        "实测系统内全部规则该值恒为 full,fo，改动可能导致规则被丢弃"
                    )
                }
            }

            WindowMode.EMBEDDING -> {
                if (hasFixedOriDetail(rule)) {
                    issues += Issue(
                        Level.WARN,
                        fixedOriDetailFields(rule),
                        "当前为平行窗口模式，已填写的固定横屏参数不会生效"
                    )
                }
                if (!rule.skipSelfAdaptive) {
                    issues += Issue(
                        Level.WARN,
                        listOf("skipSelfAdaptive"),
                        "实测系统内全部 embedding 规则均携带 skipSelfAdaptive=true，关闭后规则可能被自适应逻辑跳过"
                    )
                }
                if (rule.splitRatio.isNotEmpty() && rule.splitRatio.toFloatOrNull() == null) {
                    issues += Issue(
                        Level.ERROR,
                        listOf("splitRatio"),
                        "分屏比例必须是数字，例如 0.5"
                    )
                }
            }

            WindowMode.FULL_SCREEN -> {
                if (hasEmbeddingDetail(rule) || hasFixedOriDetail(rule)) {
                    issues += Issue(
                        Level.WARN,
                        embeddingDetailFields(rule) + fixedOriDetailFields(rule),
                        "通用规则优先级最低，且不读取平行窗口/固定横屏的细项参数"
                    )
                }
            }

            WindowMode.OFF -> {
                if (rule.autoUiEnable) {
                    issues += Issue(
                        Level.WARN,
                        listOf("autoUiEnable"),
                        "主模式为关闭，autoui 规则通常也不会产生可见效果"
                    )
                }
            }
        }

        return Result(issues)
    }

    /** 按优先级自动修正：只保留优先级最高的那一项开关 */
    fun autoFix(rule: AppRule): AppRule {
        val effective = WindowMode.fromSwitches(
            rule.swEmbedded, rule.swFixedOrientation, rule.swFullScreen
        )
        rule.swFixedOrientation = effective == WindowMode.FIXED_ORIENTATION
        rule.swEmbedded = effective == WindowMode.EMBEDDING
        rule.swFullScreen = effective == WindowMode.FULL_SCREEN
        if (effective != WindowMode.OFF) rule.mode = effective
        return rule
    }

    fun label(mode: WindowMode): String = when (mode) {
        WindowMode.OFF -> "关闭"
        WindowMode.FULL_SCREEN -> "通用规则（全屏）"
        WindowMode.EMBEDDING -> "平行窗口"
        WindowMode.FIXED_ORIENTATION -> "固定横屏（信箱）"
    }

    private fun hasEmbeddingDetail(r: AppRule): Boolean = embeddingDetailFields(r).isNotEmpty()

    private fun embeddingDetailFields(r: AppRule): List<String> = buildList {
        if (r.splitRatio.isNotEmpty()) add("splitRatio")
        if (r.activityRule.isNotEmpty()) add("activityRule")
        if (r.splitPairRule.isNotEmpty()) add("splitPairRule")
        if (r.placeholder.isNotEmpty()) add("placeholder")
        if (r.fullRule.isNotEmpty()) add("fullRule")
        if (r.middleRule.isNotEmpty()) add("middleRule")
        if (r.transitionRules.isNotEmpty()) add("transitionRules")
        if (r.splitMinWidth.isNotEmpty()) add("splitMinWidth")
    }

    private fun hasFixedOriDetail(r: AppRule): Boolean = fixedOriDetailFields(r).isNotEmpty()

    private fun fixedOriDetailFields(r: AppRule): List<String> = buildList {
        if (r.foCompatChange.isNotEmpty()) add("foCompatChange")
        if (r.foForcePortraitActivity.isNotEmpty()) add("foForcePortraitActivity")
        if (r.foFullForcePortraitActivity.isNotEmpty()) add("foFullForcePortraitActivity")
        if (r.foIsScale) add("foIsScale")
        if (r.foAllowEmbInPortrait) add("foAllowEmbInPortrait")
    }
}
