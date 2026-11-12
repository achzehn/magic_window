package com.github.lsposed.magicwindow.data

import android.content.Context
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode

/**
 * 平行窗口最小配置策略（AI / MCP 写规则专用，UI 手动编辑不受限）。
 *
 * 真机结论：给未适配应用写平行窗口规则时，多填页面或可选属性不会提升效果，
 * 反而容易匹配不到用户实际打开的页面，导致「规则已保存但进不了平行窗口」。
 * 最小可用配置只有一对：
 *   activityRule  = 启动器入口 Activity
 *   splitPairRule = 启动器入口 Activity:*
 */
object RulePresets {

    /** 启动器入口 Activity；应用没有启动入口时返回 null */
    fun entryActivity(context: Context, pkg: String): String? = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className
    }.getOrNull()

    /**
     * 把一条平行窗口规则收敛为最小配置：入口 Activity 已经是主页面时保持原样。
     * @param entryOverride 调用方显式指定的入口 Activity；为空时取启动器入口
     * @return true 表示发生了收敛（调用方可据此告知用户）
     */
    fun minimizeEmbedding(context: Context, rule: AppRule, entryOverride: String? = null): Boolean {
        if (rule.mode != WindowMode.EMBEDDING) return false
        val entry = entryOverride ?: entryActivity(context, rule.packageName) ?: return false
        val activities = rule.activityRule.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val pairLefts = rule.splitPairRule.split(',').map { it.substringBefore(':').trim() }
        if (entry in activities && entry in pairLefts) return false
        rule.activityRule = entry
        rule.splitPairRule = "$entry:*"
        // 可选属性一并留空，避免与系统默认策略冲突
        rule.placeholder = ""
        rule.transitionRules = ""
        rule.splitRatio = ""
        return true
    }
}