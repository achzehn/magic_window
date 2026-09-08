package com.github.lsposed.magicwindow.data

import android.content.Context

/**
 * 页面抓取记录存储。
 *
 * system_server 侧的 ActivityTracker 通过 [CaptureProvider] 把记录写进来，
 * UI 侧读出来供用户勾选后一键填入规则字段。
 *
 * 单条记录格式为 `包名|Activity 全类名`，用独立 prefs 存放，避免污染配置文件。
 */
object CaptureStore {

    private const val PREFS_NAME = "magic_window_capture"
    private const val KEY_RECORDS = "records"

    /** 最多保留的记录条数，防止长期开启后无限增长 */
    private const val MAX_RECORDS = 3000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 追加一条记录，重复的自动忽略 */
    fun add(context: Context, pkg: String, activity: String) {
        if (pkg.isEmpty() || activity.isEmpty()) return
        val p = prefs(context)
        val set = LinkedHashSet(p.getStringSet(KEY_RECORDS, emptySet()).orEmpty())
        if (!set.add("$pkg|$activity")) return
        val trimmed = if (set.size > MAX_RECORDS) {
            set.toList().takeLast(MAX_RECORDS).toSet()
        } else {
            set
        }
        p.edit().putStringSet(KEY_RECORDS, trimmed).apply()
    }

    /** 取某个应用抓到的 Activity 全类名，按字典序返回 */
    fun activitiesOf(context: Context, pkg: String): List<String> =
        prefs(context).getStringSet(KEY_RECORDS, emptySet())
            .orEmpty()
            .mapNotNull { line ->
                val i = line.indexOf('|')
                if (i <= 0) return@mapNotNull null
                if (line.substring(0, i) != pkg) null else line.substring(i + 1)
            }
            .distinct()
            .sorted()

    /** 清空某个应用的记录 */
    fun clear(context: Context, pkg: String) {
        val p = prefs(context)
        val kept = p.getStringSet(KEY_RECORDS, emptySet())
            .orEmpty()
            .filterNot { it.startsWith("$pkg|") }
            .toSet()
        p.edit().putStringSet(KEY_RECORDS, kept).apply()
    }
}
