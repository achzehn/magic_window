package com.github.lsposed.magicwindow.data

import android.content.Context

/**
 * 「包名前缀 + 条目」结构的 SharedPreferences 缓存基类。
 *
 * 复用动机：ActivityLabelCache / AiSuggestCache / PageRoleCache 三者结构完全一致
 * （key = 包名|维度|Activity 类名，value 为短字符串），此前各自重复实现前缀扫描，
 * 且都没有容量上限。这里收敛为一份实现，顺带补上条目淘汰，避免 prefs 无限膨胀。
 *
 * 淘汰策略：以「上次访问时间」（key 前缀 ts:）为序的近似 LRU。写入时若条目数超过
 * [maxEntries]，按访问时间从旧到新删除直到回落上限。读取时整批刷新一次访问时间，
 * 保证是 O(n) 而非每 key 一次写入。
 */
abstract class PrefixedLruCache(
    private val prefsName: String,
    private val maxEntries: Int
) {
    private fun prefs(context: Context) =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun tsKey(k: String) = "ts:$k"

    /** 前缀，由子类给出，形如 "$packageName|" 或 "$packageName|$fieldContext|" */
    protected abstract fun prefixOf(packageName: String, dimension: String): String

    /** 组合完整 key；剥离前缀后即为返回 map 的键 */
    private fun fullKey(packageName: String, dimension: String, name: String) =
        prefixOf(packageName, dimension) + name

    /** 读取某维度下全部条目：剥离前缀后的名称 → 值 */
    fun getAll(context: Context, packageName: String, dimension: String = ""): Map<String, String> {
        val prefix = prefixOf(packageName, dimension)
        val p = prefs(context)
        val result = LinkedHashMap<String, String>()
        p.all.forEach { (k, v) ->
            if (!k.startsWith("ts:") && k.startsWith(prefix) && v is String) {
                result[k.removePrefix(prefix)] = v
            }
        }
        if (result.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val editor = p.edit()
            result.keys.forEach { editor.putLong(tsKey(prefix + it), now) }
            editor.apply()
        }
        return result
    }

    /** 批量写入；entries 为空时直接返回，不做无意义 prefs 操作 */
    fun putAll(
        context: Context,
        packageName: String,
        dimension: String = "",
        entries: Map<String, String>
    ) {
        if (entries.isEmpty()) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val editor = p.edit()
        entries.forEach { (name, value) ->
            val k = fullKey(packageName, dimension, name)
            editor.putString(k, value)
            editor.putLong(tsKey(k), now)
        }
        val existing = p.all.keys.count { !it.startsWith("ts:") }
        if (existing + entries.size > maxEntries) {
            val need = existing + entries.size - maxEntries
            p.all.asSequence()
                .filter { !it.key.startsWith("ts:") }
                .map { it.key to p.getLong(tsKey(it.key), 0L) }
                .sortedBy { it.second }
                .take(need)
                .forEach { (k) ->
                    editor.remove(k)
                    editor.remove(tsKey(k))
                }
        }
        editor.apply()
    }
}
