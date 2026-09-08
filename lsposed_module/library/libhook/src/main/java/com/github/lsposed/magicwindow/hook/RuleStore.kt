package com.github.lsposed.magicwindow.hook

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.GlobalConfig
import com.github.lsposed.magicwindow.common.model.RuleCodec
import com.github.lsposed.magicwindow.common.model.WindowMode
import de.robv.android.xposed.XSharedPreferences
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server 侧的配置读取。
 *
 * 性能约束（关系到开机快慢）：本模块的 hook 落点绝大多数运行在 WindowManagerGlobalLock 之内，
 * 开机阶段每个应用、每个页面都会反复触发。所以这里的全部查询方法都只读一份内存快照，
 * 绝不做文件读取和 JSON 解析；配置变更交给一个低优先级后台线程去发现。
 */
object RuleStore {

    /** 开机静默期。这段时间里用户不可能改配置，索性一点 IO 都不做，不跟开机抢磁盘 */
    private const val BOOT_QUIET_MS = 60_000L

    /** 静默期过后的检查间隔。只是 stat 一下文件时间戳，开销极小 */
    private const val POLL_INTERVAL_MS = 5_000L

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences(Constants.MODULE_PACKAGE, Constants.PREFS_NAME).apply {
            makeWorldReadable()
        }
    }

    /**
     * 一次读取的完整结果。所有派生数据在构造时算好，之后整体替换引用，
     * 读侧因此完全不需要加锁，也不会有任何重复计算。
     */
    private class Snapshot(
        val global: GlobalConfig,
        val rules: Map<String, AppRule>
    ) {
        val activeRules: List<AppRule> = rules.values.filter { it.enabled }

        val autoUiRules: List<AppRule> = activeRules.filter { it.autoUiEnable }

        /** 包名 → 主模式，预先算好，省掉热路径上的 resolveSwitches */
        val modes: Map<String, WindowMode> = activeRules.associate { rule ->
            val (emb, fo, full) = rule.resolveSwitches()
            rule.packageName to WindowMode.fromSwitches(emb, fo, full)
        }
    }

    @Volatile
    private var snapshot = Snapshot(GlobalConfig(), emptyMap())

    private val watcherStarted = AtomicBoolean(false)

    /** 配置变更监听。用于那些「一次性动作」的步骤在开机后重新计算，避免改了配置必须重启手机 */
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // ── 写侧 ────────────────────────────────────────────────

    /** 阻塞读一次配置。只应在注入入口调用，热路径禁止调用 */
    fun loadNow() {
        runCatching {
            prefs.reload()
            val global = RuleCodec.decodeGlobal(prefs.getString(Constants.KEY_GLOBAL, null))
            val rules = RuleCodec.decodeRules(prefs.getString(Constants.KEY_RULES, null))
            snapshot = Snapshot(global, rules)
            XLog.verbose = global.verboseLog
        }.onFailure { XLog.e("读取配置失败", it) }
    }

    /** 注册配置变更回调。回调运行在低优先级后台线程上 */
    fun onChange(listener: () -> Unit) {
        changeListeners += listener
    }

    /**
     * 启动配置变更监听。开机静默期内不做任何事，之后每 [POLL_INTERVAL_MS] 只比对一次文件时间戳，
     * 真的变了才重新解析。线程为守护线程且最低优先级，不会影响开机。
     */
    fun startWatching() {
        if (!watcherStarted.compareAndSet(false, true)) return
        Thread {
            runCatching {
                Thread.sleep(BOOT_QUIET_MS)
                while (true) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    if (prefs.hasFileChanged()) {
                        loadNow()
                        XLog.i("配置已更新，生效规则 ${snapshot.activeRules.size} 条")
                        changeListeners.forEach { l ->
                            runCatching { l() }.onFailure { XLog.e("配置变更回调失败", it) }
                        }
                    }
                }
            }.onFailure { XLog.e("配置监听线程退出", it) }
        }.apply {
            name = "MagicWindow-config"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    // ── 读侧：以下方法全部是纯内存读取 ───────────────────────

    fun global(): GlobalConfig = snapshot.global

    /** 只返回已启用的规则；未配置或已停用都返回 null */
    fun ruleOf(pkg: String?): AppRule? {
        if (pkg.isNullOrEmpty()) return null
        return snapshot.rules[pkg]?.takeIf { it.enabled }
    }

    fun activeRules(): List<AppRule> = snapshot.activeRules

    fun modeOf(pkg: String?): WindowMode {
        if (pkg.isNullOrEmpty()) return WindowMode.OFF
        return snapshot.modes[pkg] ?: WindowMode.OFF
    }

    fun hasEmbeddingRule(pkg: String?): Boolean = modeOf(pkg) == WindowMode.EMBEDDING

    fun hasFixedOrientationRule(pkg: String?): Boolean =
        modeOf(pkg) == WindowMode.FIXED_ORIENTATION

    fun hasFullScreenRule(pkg: String?): Boolean = modeOf(pkg) == WindowMode.FULL_SCREEN

    fun autoUiRules(): List<AppRule> = snapshot.autoUiRules
}
