package com.github.lsposed.magicwindow.data

import android.content.Context
import android.content.SharedPreferences
import com.github.lsposed.magicwindow.common.Constants
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 与 LSPosed 框架的服务通道（libxposed 现代 API）。
 *
 * 现代 API 下模块 App 自身不再被 hook，原先「hook 自身方法回显激活状态」的做法失效，
 * 配置也无法再用 `xposedsharedprefs` 让 system_server 侧直接读文件。两者统一改走框架服务：
 *   - 激活状态：服务绑定成功即视为模块已激活（[isActive]）
 *   - 配置共享：App 侧写远端首选项，hook 侧（system_server）读取并监听变更，免广播
 *
 * 服务由 LSPosed 在模块 App 启动时经 `XposedProvider` 送入，未激活时永远收不到绑定，
 * 因此 [isActive] 是可靠的激活探针。
 */
object XposedServiceBridge {

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var listening = false

    /** 服务绑定/断开时在主线程通知界面刷新激活状态；同一时刻只允许一个订阅者（随生命周期清空） */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    /**
     * 上一次由框架回调确认过的激活状态，`null` 表示本机从未确认过。
     *
     * 冷启动时框架投递服务绑定晚于 Activity 首帧，界面只能用历史值判断该显示
     * 「检测中」还是直接显示「未生效」，否则每次进入都会先闪一下红色失败文案。
     */
    @Volatile
    var lastKnownActive: Boolean? = null
        private set

    private var statusPrefs: SharedPreferences? = null

    private const val KEY_LAST_ACTIVE = "module_last_active"

    /** Application 启动时调用一次：载入上次确认的状态（复用配置首选项，不额外读文件） */
    fun init(context: Context) {
        if (statusPrefs != null) return
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        statusPrefs = prefs
        if (prefs.contains(KEY_LAST_ACTIVE)) {
            lastKnownActive = prefs.getBoolean(KEY_LAST_ACTIVE, false)
        }
    }

    /** 一次性动作（如配置补推），绑定后执行；绑定后注册的会立即补执行 */
    private val bindActions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun onBind(action: () -> Unit) {
        bindActions += action
        ensureListening()
        // 服务早已绑定、本次只是新加动作时，ensureListening 不会再回调，需手动补执行
        if (service != null) runCatching { action() }
    }

    private fun ensureListening() {
        if (listening) return
        listening = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                remotePrefs = runCatching { s.getRemotePreferences(Constants.PREFS_NAME) }.getOrNull()
                rememberStatus(true)
                bindActions.forEach { a -> runCatching { a() } }
                mainHandler.post { onStateChanged?.invoke() }
            }

            override fun onServiceDied(s: XposedService) {
                service = null
                remotePrefs = null
                rememberStatus(false)
                mainHandler.post { onStateChanged?.invoke() }
            }
        })
    }

    /** 框架服务已绑定：模块处于激活状态 */
    val isActive: Boolean get() = service != null

    /**
     * 界面兜底：进入页面一段时间后仍未收到绑定回调，判定为未激活并落盘。
     *
     * 只在服务确实未绑定、且尚未确认过「未生效」时写入，避免把慢绑定误记为失败；
     * 之后框架一旦绑定，[rememberStatus] 会立即纠正。
     */
    fun confirmInactive() {
        if (service != null || lastKnownActive == false) return
        rememberStatus(false)
    }

    private fun rememberStatus(active: Boolean) {
        lastKnownActive = active
        runCatching { statusPrefs?.edit()?.putBoolean(KEY_LAST_ACTIVE, active)?.apply() }
    }

    /** 把规则写入远端首选项。服务未就绪返回 false，由调用方决定何时重试 */
    fun writeRules(encoded: String): Boolean {
        val prefs = remotePrefs ?: return false
        return runCatching {
            // 内容与框架侧一致就不写：每次写都会触发 hook 侧重载并重算云控文件
            if (prefs.getString(Constants.KEY_RULES, null) == encoded) {
                true
            } else {
                prefs.edit().putString(Constants.KEY_RULES, encoded).commit()
            }
        }.getOrDefault(false)
    }
}
