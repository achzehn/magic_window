package com.github.lsposed.magicwindow.data

import android.content.Context

/**
 * 占位页配对选择器的「页面角色」缓存。
 *
 * 保存 AI 对 Activity 角色的判定结果：主页面 / 占位页面 / 其他。
 * key = 包名|placeholderRole|Activity 类名。value 为角色串（直接复用展示文案）。
 *
 * 复用动机：配对选择器此前每次打开都对全部页面重新请求 AI 判定角色，
 * 且角色结果只存活于对话框生命周期，关闭即丢失，是所有 AI 入口里
 * 唯一没有增量与缓存的路径——典型的重复 token 消耗。
 */
object PageRoleCache : PrefixedLruCache("page_role_cache", MAX_ENTRIES) {

    /** 维度名固定，避免调用方散落字面量 */
    const val DIMENSION = "placeholderRole"

    override fun prefixOf(packageName: String, dimension: String) =
        "$packageName|${dimension.ifEmpty { DIMENSION }}|"

    /** 取该包名下的全部角色缓存：Activity类名 → 角色 */
    fun getAllForPackage(context: Context, packageName: String): Map<String, String> =
        getAll(context, packageName, DIMENSION)

    /** 批量保存角色结果 */
    fun putForPackage(context: Context, packageName: String, roles: Map<String, String>) =
        putAll(context, packageName, DIMENSION, roles)
}

/** 条目上限：约 200 个应用 × 10 个页面 */
private const val MAX_ENTRIES = 2000
