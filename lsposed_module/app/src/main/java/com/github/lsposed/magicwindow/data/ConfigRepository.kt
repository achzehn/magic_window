package com.github.lsposed.magicwindow.data

import android.content.Context
import android.content.SharedPreferences
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.RuleCodec

/**
 * 配置仓储。
 *
 * 双写：本地普通 SharedPreferences 保证 App 自身随时可读（模块未激活也能编辑规则），
 * 每次保存后再经 libxposed 框架服务写入远端首选项，hook 侧（system_server）据此读取并热重载。
 *
 * 线程模型：AI 工具在 IO 线程读写、MCP 工具在后台单线程执行、UI 在主线程——
 * 所有对 [rules] 的结构性访问都在 synchronized 块内，[allRules] 返回快照避免迭代中 CME。
 */
object ConfigRepository {

    private lateinit var prefs: SharedPreferences

    /** 持久化/推送专用单线程：commit 与跨进程写不能在 UI 线程执行，且需按调用顺序落盘 */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mw-config-io").apply { isDaemon = true }
    }

    private val rules = LinkedHashMap<String, AppRule>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
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
        // commit（本地 + 跨进程）在调用方线程同步执行会阻塞 UI，统一交给单个后台线程按序执行
        io.execute {
            val ok = prefs.edit().putString(Constants.KEY_RULES, encoded).commit()
            if (!ok) {
                // commit 失败仍推送会让 hook 侧生效一份与界面不一致的配置，状态分叉且无从察觉
                android.util.Log.w("ConfigRepository", "规则持久化失败（commit 返回 false），跳过推送 hook 侧")
                return@execute
            }
            pushEncoded(encoded)
        }
    }

    /**
     * 把当前规则推送到远端首选项，hook 侧（system_server）监听该键即热重载。
     *
     * 服务未绑定时（模块未激活 / 框架刚重启）静默失败：规则已落本地，
     * 待 [XposedServiceBridge] 绑定成功后由 [MagicWindowApp] 补推一次。
     */
    fun pushToHook() {
        val encoded = synchronized(rules) { RuleCodec.encodeRules(rules.values) }
        io.execute { pushEncoded(encoded) }
    }

    private fun pushEncoded(encoded: String) {
        if (!XposedServiceBridge.writeRules(encoded)) {
            android.util.Log.i("ConfigRepository", "框架服务未就绪，规则仅落本地，待绑定后补推")
        }
    }
}
