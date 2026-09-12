package com.github.lsposed.magicwindow.hook

import android.os.FileObserver
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.RuleCodec
import de.robv.android.xposed.XSharedPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server 侧的配置读取。
 *
 * 性能约束：读侧全部只读内存快照，绝不做文件 IO 与 JSON 解析。
 * 配置变更由 [FileObserver] 事件驱动感知（App 保存 → LSPosed 同步落盘 → 目录事件），
 * 仅在拿不到 prefs 文件路径时才退回低频 stat 轮询兜底。
 */
object RuleStore {

    /** FileObserver 反射失败时的兜底轮询间隔（只是 stat 时间戳，开销极小） */
    private const val FALLBACK_POLL_MS = 15_000L

    /** 连续写入事件的去抖窗口 */
    private const val DEBOUNCE_MS = 500L

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

    private val started = AtomicBoolean(false)

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val notifyRunnable = Runnable {
        loadNow()
        XLog.i("配置已更新，生效规则 ${snapshot.activeRules.size} 条")
        changeListeners.forEach { l -> runCatching { l() }.onFailure { XLog.e("配置变更回调失败", it) } }
    }

    /** 回调去抖在 FileObserver 的事件线程上串行执行 */
    private val handlerThread by lazy {
        android.os.HandlerThread("MagicWindow-config").apply { start() }
    }
    private val handler by lazy { android.os.Handler(handlerThread.looper) }

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

    /**
     * App 保存配置后由广播接收器调用：FileObserver 在 system_server 上收不到
     * LSPosed prefs 目录的事件，热更新统一走这条路。
     */
    fun notifyFromApp() {
        handler.post(notifyRunnable)
    }

    /**
     * 启动配置变更监听：优先在 prefs 所在目录挂 FileObserver（零轮询），
     * 反射不到文件路径时退回 [FALLBACK_POLL_MS] 的低频 stat。
     */
    fun startWatching() {
        if (!started.compareAndSet(false, true)) return
        val prefFile = resolvePrefFile()
        if (prefFile != null && prefFile.parentFile != null) {
            startFileObserver(prefFile)
            XLog.i("配置监听：FileObserver（${prefFile.parentFile?.path}）")
        } else {
            startFallbackPolling()
            XLog.i("配置监听：${FALLBACK_POLL_MS / 1000}s 轮询兜底")
        }
    }

    private fun startFileObserver(prefFile: File) {
        val dir = prefFile.parentFile
        val observer = object : FileObserver(dir, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                // SELinux/MCS 很可能让 watch 静默失效，任何收到的事件都记一笔便于诊断
                XLog.i("FileObserver 事件 event=$event path=$path")
                if (path != prefFile.name) return
                handler.removeCallbacks(notifyRunnable)
                handler.postDelayed(notifyRunnable, DEBOUNCE_MS)
            }
        }
        observer.startWatching()
    }

    private fun startFallbackPolling() {
        Thread {
            runCatching {
                while (true) {
                    Thread.sleep(FALLBACK_POLL_MS)
                    if (prefs.hasFileChanged()) handler.post(notifyRunnable)
                }
            }.onFailure { XLog.e("配置监听线程退出", it) }
        }.apply {
            name = "MagicWindow-config-poll"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** XSharedPreferences 没有公开路径 API，反射取其内部 File 字段 */
    private fun resolvePrefFile(): File? = runCatching {
        generateSequence<Class<*>>(prefs.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.type == File::class.java }
            ?.apply { isAccessible = true }
            ?.get(prefs) as? File
    }.getOrNull()

    // ── 读侧：纯内存读取 ───────────────────────────────────

    fun activeRules(): List<AppRule> = snapshot.activeRules

    fun autoUiRules(): List<AppRule> = snapshot.autoUiRules
}
