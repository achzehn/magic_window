package com.github.lsposed.magicwindow.hook.steps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog

/**
 * 配置热更新广播：App 保存规则后发 [Constants.ACTION_CONFIG_CHANGED]，
 * system_server 收到后重读配置并重新落盘云控 + 热重载。
 *
 * 为什么不用 FileObserver：LSPosed 托管的 prefs 目录带 SELinux 类别标签
 * （lsposed_file:s0:cN…），system_server 的 inotify_add_watch 被静默拒绝，
 * 实测任何写入都不会收到事件，所以改由 App 显式广播。
 *
 * 安全性：不能用 signature 权限保护——实测权限的声明包并不会自动持有它，
 * 自己发自己收也会被拒。改用 [Intent.getSentFromPackage]（API 34+，系统
 * 填充不可伪造）在接收端校验发送者；API < 34 只做包名一致性弱校验。
 *
 * 时机：注入发生在 AMS 服务发布之前，registerReceiver 会 NPE，
 * 所以失败后用 system_server 主线程延迟重试，直到注册成功。
 */
object ConfigSyncReceiver {

    private const val RETRY_INTERVAL_MS = 3_000L
    private const val MAX_RETRIES = 20

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Constants.ACTION_CONFIG_CHANGED) return
            // API 34 提供 Intent.getSentFromPackage()（系统填充不可伪造）；
            // 编译环境不一定带这个 API，用反射取
            val sender = runCatching {
                Intent::class.java.getMethod("getSentFromPackage").invoke(intent) as? String
            }.getOrNull()
            if (sender != null && sender != Constants.MODULE_PACKAGE) {
                XLog.i("忽略配置广播：发送者 $sender 不是本模块")
                return
            }
            if (sender == null) {
                // API < 34 无发送者信息，无法校验；本 action 只做重读配置，风险可控
                XLog.i("收到配置变更广播（无法读取发送者，跳过校验）")
            }
            RuleStore.notifyFromApp()
        }
    }

    fun register(systemContext: Context) {
        tryRegister(systemContext, retriesLeft = MAX_RETRIES)
    }

    private fun tryRegister(systemContext: Context, retriesLeft: Int) {
        runCatching {
            systemContext.registerReceiver(
                receiver,
                IntentFilter(Constants.ACTION_CONFIG_CHANGED),
                Context.RECEIVER_EXPORTED
            )
            XLog.i("配置热更新广播已注册")
        }.onFailure {
            if (retriesLeft <= 0) {
                XLog.e("注册配置广播失败（保存规则后将需要重启生效）", it)
                return
            }
            android.os.Handler(systemContext.mainLooper).postDelayed(
                { tryRegister(systemContext, retriesLeft - 1) },
                RETRY_INTERVAL_MS
            )
        }
    }
}
