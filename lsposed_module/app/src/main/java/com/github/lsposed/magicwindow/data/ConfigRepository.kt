package com.github.lsposed.magicwindow.data

import android.content.Context
import android.content.SharedPreferences
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.GlobalConfig
import com.github.lsposed.magicwindow.common.model.RuleCodec

/**
 * 配置仓储。
 *
 * 依赖 `xposedsharedprefs=true`：LSPosed 会把这份 SharedPreferences 变为 hook 侧可读，
 * 因此需要用 MODE_WORLD_READABLE 打开（未激活模块时会抛异常，回落到 MODE_PRIVATE）。
 */
object ConfigRepository {

    private lateinit var prefs: SharedPreferences

    private val rules = LinkedHashMap<String, AppRule>()
    private var global = GlobalConfig()

    @Suppress("DEPRECATION")
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = runCatching {
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        }
        reload()
    }

    fun reload() {
        global = RuleCodec.decodeGlobal(prefs.getString(Constants.KEY_GLOBAL, null))
        rules.clear()
        rules.putAll(RuleCodec.decodeRules(prefs.getString(Constants.KEY_RULES, null)))
    }

    fun global(): GlobalConfig = global

    fun saveGlobal(config: GlobalConfig) {
        global = config
        prefs.edit().putString(Constants.KEY_GLOBAL, RuleCodec.encodeGlobal(config)).apply()
    }

    /** 已配置的应用数 */
    fun configuredCount(): Int = rules.count { it.value.enabled }

    fun allRules(): Map<String, AppRule> = rules

    fun rule(pkg: String): AppRule? = rules[pkg]

    fun ruleOrNew(pkg: String): AppRule = rules[pkg] ?: AppRule(pkg)

    fun saveRule(rule: AppRule) {
        rules[rule.packageName] = rule
        persistRules()
    }

    fun saveRules(list: Collection<AppRule>) {
        list.forEach { rules[it.packageName] = it }
        persistRules()
    }

    fun removeRule(pkg: String) {
        rules.remove(pkg)
        persistRules()
    }

    fun removeRules(pkgs: Collection<String>) {
        pkgs.forEach { rules.remove(it) }
        persistRules()
    }

    private fun persistRules() {
        prefs.edit().putString(Constants.KEY_RULES, RuleCodec.encodeRules(rules.values)).apply()
    }

    /** 返回美化的 JSON 字符串，用于编辑器展示 */
    fun allRulesJson(): String {
        return RuleCodec.prettyJson(RuleCodec.encodeRules(rules.values))
    }
}
