package com.github.lsposed.magicwindow.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
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

    @Suppress("DEPRECATION")
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        this.context = java.lang.ref.WeakReference(context.applicationContext)
        prefs = runCatching {
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        }
        reload()
    }

    fun reload() {
        rules.clear()
        rules.putAll(RuleCodec.decodeRules(prefs.getString(Constants.KEY_RULES, null)))
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

    /** 全量替换：导入配置、保存全规则编辑器时使用，保证被移除的规则真的消失 */
    fun replaceAllRules(list: Collection<AppRule>) {
        rules.clear()
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
        prefs.edit().putString(Constants.KEY_RULES, RuleCodec.encodeRules(rules.values)).commit()
        notifyHookSide()
    }

    /**
     * 通知 hook 侧热重载。
     * FileObserver 在 system_server 上收不到 LSPosed prefs 目录的事件（SELinux 类别标签），
     * 所以保存后显式发广播；system_server 侧用签名权限校验发送者。
     * 延迟 500ms 是给 LSPosed 守护进程留出把 prefs 同步到托管目录的时间。
     */
    private fun notifyHookSide() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                context?.get()?.sendBroadcast(Intent(Constants.ACTION_CONFIG_CHANGED))
            }
        }, 500L)
    }

    /** 应用上下文，仅用于发广播；init 时传入不持有 Activity */
    private var context: java.lang.ref.WeakReference<Context>? = null
}
