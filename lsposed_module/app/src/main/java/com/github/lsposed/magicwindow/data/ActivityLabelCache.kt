package com.github.lsposed.magicwindow.data

import android.content.Context

/**
 * Activity 中文描述缓存。
 * AI 补全的中文说明持久化到本地，避免重复消耗 token。
 * 容量上限见 [MAX_ENTRIES]，超限按访问时间淘汰最旧条目。
 */
object ActivityLabelCache : PrefixedLruCache("activity_label_cache", MAX_ENTRIES) {
    override fun prefixOf(packageName: String, dimension: String) = "$packageName|"

    /** 获取整个包名下的所有缓存 */
    fun getAllForPackage(context: Context, packageName: String): Map<String, String> =
        getAll(context, packageName)

    /** 批量保存缓存 */
    fun putForPackage(context: Context, packageName: String, labels: Map<String, String>) =
        putAll(context, packageName, "", labels)
}

/** 条目上限：约 200 个应用 × 10 个页面 */
private const val MAX_ENTRIES = 2000
