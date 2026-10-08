package com.github.lsposed.magicwindow.hook

import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.RuleCodec
import de.robv.android.xposed.XSharedPreferences

/**
 * system_server 侧的配置读取。
 *
 * 性能约束：读侧全部只读内存快照，绝不做文件 IO 与 JSON 解析。
 * 配置变更由 App 保存广播（ConfigSyncReceiver）驱动感知；FileObserver 在 system_server 上
 * 收不到 LSPosed prefs 目录的事件（SELinux 类别标签），故不监听文件。
 */
object RuleStore {

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences(Constants.MODULE_PACKAGE, Constants.PREFS_NAME).apply {
            makeWorldReadable()
        }
    }

    private class Snapshot(rules: Map<String, AppRule>) {
        val activeRules: List<AppRule> = rules.values.filter { it.enabled }
        val autoUiRules: List<AppRule> = activeRules.filter { it.autoUiEnable }
    }

    @Volatile
    private var snapshot = Snapshot(emptyMap())

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // ── 写侧 ────────────────────────────────────────────────

    /** 阻塞读一次配置。只应在注入入口调用，热路径禁止调用 */
    fun loadNow() {
        runCatching {
            prefs.reload()
            val rules = RuleCodec.decodeRules(prefs.getString(Constants.KEY_RULES, null))
            snapshot = Snapshot(rules)
        }.onFailure { XLog.e("读取配置失败", it) }
    }

    fun onChange(listener: () -> Unit) {
        changeListeners += listener
    }

    /** App 保存配置后由广播接收器调用：直接热重载并通知所有监听者 */
    fun notifyFromApp() {
        loadNow()
        XLog.i("配置已更新，生效规则 ${snapshot.activeRules.size} 条")
        changeListeners.forEach { l -> runCatching { l() }.onFailure { XLog.e("配置变更回调失败", it) } }
    }

    // ── 读侧：纯内存读取 ───────────────────────────────────

    fun activeRules(): List<AppRule> = snapshot.activeRules

    fun autoUiRules(): List<AppRule> = snapshot.autoUiRules
}
