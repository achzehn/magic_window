package com.github.lsposed.magicwindow.ui

import android.content.Context
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.SystemRuleSource

/** 「系统已内置」徽标文案：把命中的内置规则种类拼成一行小字。 */
object BuiltinUi {

    private fun name(context: Context, kind: SystemRuleSource.Kind, pkg: String): String =
        when (kind) {
            SystemRuleSource.Kind.EMBEDDING -> context.getString(R.string.builtin_embedding)
            SystemRuleSource.Kind.FIXED ->
                if (SystemRuleSource.isFixedDisabled(pkg)) {
                    context.getString(R.string.builtin_fixed_disabled)
                } else {
                    context.getString(R.string.builtin_fixed)
                }

            SystemRuleSource.Kind.AUTO_UI -> context.getString(R.string.builtin_autoui)
        }

    /** 例如「系统已内置：平行窗口 · 界面适配」，没有内置规则时返回 null */
    fun badge(context: Context, pkg: String): String? {
        val kinds = SystemRuleSource.kindsOf(pkg)
        if (kinds.isEmpty()) return null
        val body = kinds.joinToString(" · ") { name(context, it, pkg) }
        return "${context.getString(R.string.builtin_prefix)}：$body"
    }
}
