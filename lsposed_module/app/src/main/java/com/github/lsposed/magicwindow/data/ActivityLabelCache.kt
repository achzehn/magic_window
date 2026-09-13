package com.github.lsposed.magicwindow.data

import android.content.Context
import org.json.JSONObject

/**
 * Activity 中文描述缓存。
 * AI 补全的中文说明持久化到本地，避免重复消耗 token。
 */
object ActivityLabelCache {
    private const val PREFS = "activity_label_cache"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 获取缓存的中文描述 */
    fun get(context: Context, packageName: String, activityName: String): String? {
        val key = "$packageName|$activityName"
        return prefs(context).getString(key, null)
    }

    /** 获取整个包名下的所有缓存 */
    fun getAllForPackage(context: Context, packageName: String): Map<String, String> {
        val prefix = "$packageName|"
        val result = mutableMapOf<String, String>()
        prefs(context).all.forEach { (key, value) ->
            if (key.startsWith(prefix) && value is String) {
                val actName = key.removePrefix(prefix)
                result[actName] = value
            }
        }
        return result
    }

    /** 批量保存缓存 */
    fun putAll(context: Context, packageName: String, labels: Map<String, String>) {
        val editor = prefs(context).edit()
        labels.forEach { (actName, label) ->
            editor.putString("$packageName|$actName", label)
        }
        editor.apply()
    }

    /** 保存单条缓存 */
    fun put(context: Context, packageName: String, activityName: String, label: String) {
        prefs(context).edit()
            .putString("$packageName|$activityName", label)
            .apply()
    }

    /** 清除某个包名下的所有缓存 */
    fun clearPackage(context: Context, packageName: String) {
        val prefix = "$packageName|"
        val editor = prefs(context).edit()
        prefs(context).all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }
}
