package com.github.lsposed.magicwindow.hook

import com.github.lsposed.magicwindow.hook.steps.AutoUiCloudInjector
import com.github.lsposed.magicwindow.hook.steps.EmbeddedFixedCloudInjector
import com.github.lsposed.magicwindow.hook.steps.PluginClassLoaderCatcher
import com.github.lsposed.magicwindow.hook.steps.PropertyGate
import io.github.libxposed.api.XposedInterface

/**
 * system_server 侧的注入编排（云控单路径版本）。
 *
 *   第 0 步 校验总开关 property（一次性，必要时兜底）
 *   第一步 捕获插件 ClassLoader（拿到即摘钩）
 *   第二步 云控注入（autoui / embedding / fixed）：hook 解析类仅为拿到宿主实例，
 *          之后配置变更经远端首选项的变更监听（[RuleStore.attach]）用缓存实例热重载
 */
object SystemServerHooks {

    fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        // 绑定远端首选项并首读；变更监听替代旧 API 的 XSharedPreferences + 广播
        RuleStore.attach(xposed)

        runCatching { PropertyGate.apply(xposed, classLoader) }
            .onFailure { XLog.e("第 0 步失败", it) }

        PluginClassLoaderCatcher.whenReady { pluginCl ->
            runCatching { AutoUiCloudInjector.apply(xposed, pluginCl, classLoader) }
                .onFailure { XLog.e("autoui 云控注入失败", it) }
            runCatching { EmbeddedFixedCloudInjector.apply(xposed, pluginCl) }
                .onFailure { XLog.e("embedding/fixed 云控注入失败", it) }
            XLog.i("全部注入完成，生效规则 ${RuleStore.activeRules().size} 条")
        }

        // 配置保存后热更新：缓存宿主实例重新落盘云控 + 热重载，无需重启手机
        RuleStore.onChange {
            AutoUiCloudInjector.injectNow()
            EmbeddedFixedCloudInjector.injectNow()
        }

        PluginClassLoaderCatcher.start(xposed, classLoader)
    }
}
