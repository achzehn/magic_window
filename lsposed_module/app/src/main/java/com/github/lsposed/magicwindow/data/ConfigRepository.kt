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
 *
 * 线程模型：AI 工具在 IO 线程读写、MCP 工具在后台单线程执行、UI 在主线程——
 * 所有对 [rules] 的结构性访问都在 synchronized 块内，[allRules] 返回快照避免迭代中 CME。
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
        // 损坏的 JSON 按空配置处理（decodeRules 失败抛异常），不让启动路径崩溃
        synchronized(rules) {
            rules.clear()
            rules.putAll(runCatching { RuleCodec.decodeRules(prefs.getString(Constants.KEY_RULES, null)) }
                .getOrElse { emptyMap() })
        }
    }

    /** 已配置的应用数 */
    fun configuredCount(): Int = synchronized(rules) { rules.count { it.value.enabled } }

    /** 返回快照（防御迭代期间其他线程结构性修改），元素为共享实例、只读使用 */
    fun allRules(): Map<String, AppRule> = synchronized(rules) { rules.toMap() }

    fun rule(pkg: String): AppRule? = synchronized(rules) { rules[pkg] }

    fun ruleOrNew(pkg: String): AppRule = synchronized(rules) { rules[pkg] } ?: AppRule(pkg)

    fun saveRule(rule: AppRule) {
        synchronized(rules) { rules[rule.packageName] = rule }
        persistRules()
    }

    fun saveRules(list: Collection<AppRule>) {
        synchronized(rules) { list.forEach { rules[it.packageName] = it } }
        persistRules()
    }

    /** 全量替换：导入配置、保存全规则编辑器时使用，保证被移除的规则真的消失 */
    fun replaceAllRules(list: Collection<AppRule>) {
        synchronized(rules) {
            rules.clear()
            list.forEach { rules[it.packageName] = it }
        }
        persistRules()
    }

    fun removeRule(pkg: String) {
        synchronized(rules) { rules.remove(pkg) }
        persistRules()
    }

    fun removeRules(pkgs: Collection<String>) {
        synchronized(rules) { pkgs.forEach { rules.remove(it) } }
        persistRules()
    }

    private fun persistRules() {
        val encoded = synchronized(rules) { RuleCodec.encodeRules(rules.values) }
        val ok = prefs.edit().putString(Constants.KEY_RULES, encoded).commit()
        if (!ok) {
            // commit 失败仍发广播会让 hook 侧重载到旧配置，状态分叉且无从察觉
            android.util.Log.w("ConfigRepository", "规则持久化失败（commit 返回 false），跳过热更新广播")
            return
        }
        notifyHookSide()
    }

    /**
     * 通知 hook 侧热重载。
     * FileObserver 在 system_server 上收不到 LSPosed prefs 目录的事件（SELinux 类别标签），
     * 所以保存后显式发广播；system_server 侧在接收端用 Intent.getSentFromPackage 校验发送者。
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
