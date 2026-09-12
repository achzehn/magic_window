package com.github.lsposed.magicwindow.hook

import android.content.Context
import com.github.lsposed.magicwindow.hook.steps.AutoUiCloudInjector
import com.github.lsposed.magicwindow.hook.steps.ConfigSyncReceiver
import com.github.lsposed.magicwindow.hook.steps.EmbeddedFixedCloudInjector
import com.github.lsposed.magicwindow.hook.steps.PluginClassLoaderCatcher
import com.github.lsposed.magicwindow.hook.steps.PropertyGate
import de.robv.android.xposed.XposedHelpers

/**
 * system_server 侧的注入编排（云控单路径版本）。
 *
 *   第 0 步 校验总开关 property（一次性，必要时兜底）
 *   第一步 捕获插件 ClassLoader（拿到即摘钩）
 *   第二步 云控注入（autoui / embedding / fixed）：hook 解析类仅为拿到宿主实例，
 *          之后配置变更经「App 保存广播」（ConfigSyncReceiver）用缓存实例热重载
 */
object SystemServerHooks {

    fun install(classLoader: ClassLoader) {
        // 同步读一次；之后由「App 保存广播」事件驱动，热路径不碰磁盘
        RuleStore.loadNow()
        RuleStore.startWatching()

        // FileObserver 在 system_server 上收不到 LSPosed prefs 目录的事件（SELinux 类别标签），
        // 热更新统一走 App 广播
        runCatching {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", classLoader),
                "currentActivityThread"
            )
            val systemContext = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
            ConfigSyncReceiver.register(systemContext)
        }.onFailure { XLog.e("获取 SystemContext 失败", it) }

        runCatching { PropertyGate.apply(classLoader) }
            .onFailure { XLog.e("第 0 步失败", it) }

        PluginClassLoaderCatcher.whenReady { pluginCl ->
            runCatching { AutoUiCloudInjector.apply(pluginCl, classLoader) }
                .onFailure { XLog.e("autoui 云控注入失败", it) }
            runCatching { EmbeddedFixedCloudInjector.apply(pluginCl) }
                .onFailure { XLog.e("embedding/fixed 云控注入失败", it) }
            XLog.i("全部注入完成，生效规则 ${RuleStore.activeRules().size} 条")
        }

        // 配置保存后热更新：缓存宿主实例重新落盘云控 + 热重载，无需重启手机
        RuleStore.onChange {
            AutoUiCloudInjector.injectNow()
            EmbeddedFixedCloudInjector.injectNow()
        }

        PluginClassLoaderCatcher.start(classLoader)
    }
}
