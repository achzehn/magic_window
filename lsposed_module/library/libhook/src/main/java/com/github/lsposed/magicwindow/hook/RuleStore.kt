package com.github.lsposed.magicwindow.hook

import android.content.SharedPreferences
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.RuleCodec
import io.github.libxposed.api.XposedInterface

/**
 * system_server 侧的配置读取（libxposed 远端首选项）。
 *
 * 配置由模块 App 经 LSPosed 框架写入（`XposedService.getRemotePreferences`），
 * hook 侧用同名的 `XposedInterface.getRemotePreferences` 读取——底层是 LSPosed 数据库，
 * 跨进程共享并支持变更监听，替代了旧 API 的 `XSharedPreferences` + 广播通知。
 *
 * 性能约束：读侧全部只读内存快照，绝不做文件 IO 与 JSON 解析；
 * 变更监听回调落在 binder 线程，重读与注入一律转到 [worker]。
 */
object RuleStore {

    @Volatile
    private var prefs: SharedPreferences? = null

    private class Snapshot(rules: Map<String, AppRule>) {
        val activeRules: List<AppRule> = rules.values.filter { it.enabled }
        val autoUiRules: List<AppRule> = activeRules.filter { it.autoUiEnable }
    }

    @Volatile
    private var snapshot = Snapshot(emptyMap())

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val loadLock = Any()

    /** 热更新专用工作线程：变更回调在 binder 线程触发，文件 IO 与注入器全量读写必须离开该线程 */
    private val worker = android.os.Handler(
        android.os.HandlerThread("mw-rulestore").apply { start() }.looper
    )

    // ── 初始化 ──────────────────────────────────────────────

    /** 绑定框架并做一次首读。必须在 system_server 注入入口调用，失败则本轮无配置可用 */
    fun attach(xposed: XposedInterface) {
        if (prefs != null) return
        val p = runCatching { xposed.getRemotePreferences(Constants.PREFS_NAME) }.getOrElse {
            XLog.e("获取远端首选项失败，本轮无配置可用", it)
            return
        }
        prefs = p
        // 变更回调在 binder 线程，只入队；重读与注入全部交给工作线程
        runCatching {
            p.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == Constants.KEY_RULES) notifyFromApp()
            }
        }.onFailure { XLog.e("注册配置变更监听失败，配置热更新将不可用", it) }
        loadNow()
    }

    // ── 写侧 ────────────────────────────────────────────────

    /** 阻塞读一次配置。只应在注入入口调用，热路径禁止调用 */
    fun loadNow() {
        val p = prefs ?: return
        // 与本对象内其他线程的 loadNow 互斥，避免快照发布交叉
        synchronized(loadLock) {
            runCatching {
                val rules = RuleCodec.decodeRules(p.getString(Constants.KEY_RULES, null))
                snapshot = Snapshot(rules)
            }.onFailure { XLog.e("读取配置失败（保留旧快照）", it) }
        }
    }

    fun onChange(listener: () -> Unit) {
        changeListeners += listener
    }

    /** 配置变更后调用：只入队，重读与注入全部在工作线程执行 */
    fun notifyFromApp() {
        worker.post {
            loadNow()
            XLog.i("配置已更新，生效规则 ${snapshot.activeRules.size} 条")
            changeListeners.forEach { l -> runCatching { l() }.onFailure { XLog.e("配置变更回调失败", it) } }
        }
    }

    // ── 读侧：纯内存读取 ───────────────────────────────────

    fun activeRules(): List<AppRule> = snapshot.activeRules

    fun autoUiRules(): List<AppRule> = snapshot.autoUiRules
}
