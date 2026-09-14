package com.github.lsposed.magicwindow.data

import android.content.Context

/**
 * AI 页面推荐标签缓存。
 * 以「包名 + 场景（fieldContext）+ Activity 类名」为 key 持久化 AI 的适合/不适合标签，
 * 避免每次打开页面选择器都重新请求 AI。
 */
object AiSuggestCache {
    private const val PREFS = "ai_suggest_cache"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(packageName: String, fieldContext: String, activityName: String) =
        "$packageName|$fieldContext|$activityName"

    /** 获取某包名 + 场景下的全部缓存标签：Activity类名 → 标签 */
    fun getAllForPackage(
        context: Context,
        packageName: String,
        fieldContext: String
    ): Map<String, String> {
        val prefix = "$packageName|$fieldContext|"
        val result = mutableMapOf<String, String>()
        prefs(context).all.forEach { (k, v) ->
            if (k.startsWith(prefix) && v is String) {
                result[k.removePrefix(prefix)] = v
            }
        }
        return result
    }

    /** 批量保存缓存标签 */
    fun putAll(
        context: Context,
        packageName: String,
        fieldContext: String,
        tags: Map<String, String>
    ) {
        if (tags.isEmpty()) return
        val editor = prefs(context).edit()
        tags.forEach { (act, tag) ->
            editor.putString(key(packageName, fieldContext, act), tag)
        }
        editor.apply()
    }
}
