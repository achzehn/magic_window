package com.github.lsposed.magicwindow.data

import android.content.Context

/**
 * AI 页面推荐标签缓存。
 * 以「包名 + 场景（fieldContext）+ Activity 类名」为 key 持久化 AI 的适合/不适合标签，
 * 避免每次打开页面选择器都重新请求 AI。
 * 容量上限见 [MAX_ENTRIES]，超限按访问时间淘汰最旧条目。
 */
object AiSuggestCache : PrefixedLruCache("ai_suggest_cache", MAX_ENTRIES) {
    override fun prefixOf(packageName: String, dimension: String) = "$packageName|$dimension|"

    /** 获取某包名 + 场景下的全部缓存标签：Activity类名 → 标签 */
    fun getAllForPackage(context: Context, packageName: String, fieldContext: String): Map<String, String> =
        getAll(context, packageName, fieldContext)

    /** 批量保存缓存标签 */
    fun putForPackage(
        context: Context,
        packageName: String,
        fieldContext: String,
        tags: Map<String, String>
    ) = putAll(context, packageName, fieldContext, tags)
}

/** 条目上限：约 200 个应用 × 15 个场景 */
private const val MAX_ENTRIES = 3000
