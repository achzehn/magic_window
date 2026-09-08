package com.github.lsposed.magicwindow.hook

import com.github.lsposed.magicwindow.hook.steps.ActivityTracker
import com.github.lsposed.magicwindow.hook.steps.AutoUiCloudInjector
import com.github.lsposed.magicwindow.hook.steps.EmbeddingQueryOverride
import com.github.lsposed.magicwindow.hook.steps.PluginClassLoaderCatcher
import com.github.lsposed.magicwindow.hook.steps.PropertyGate
import com.github.lsposed.magicwindow.hook.steps.RuleTableInjector
import com.github.lsposed.magicwindow.hook.steps.UserSettingSwitcher

/**
 * system_server 侧的注入编排。
 *
 * 落地顺序按资料评估 6.3：
 *   第 0 步 校验总开关 property
 *   第一步 捕获插件 ClassLoader
 *   第四步 用户开关翻转（收益最高）
 *   第三步 A 档查询 hook 兜底
 *   第二步 autoui 云控注入
 */
object SystemServerHooks {

    fun install(classLoader: ClassLoader) {
        // 同步读一次；之后的配置变更由后台线程发现，热路径不再碰磁盘
        RuleStore.loadNow()
        RuleStore.startWatching()

        runCatching { PropertyGate.apply(classLoader) }
            .onFailure { XLog.e("第 0 步失败", it) }

        runCatching { ActivityTracker.apply(classLoader) }
            .onFailure { XLog.e("页面抓取挂钩失败", it) }

        PluginClassLoaderCatcher.whenReady { pluginCl ->
            runCatching { UserSettingSwitcher.apply(pluginCl) }
                .onFailure { XLog.e("第四步失败", it) }
            runCatching { EmbeddingQueryOverride.apply(pluginCl) }
                .onFailure { XLog.e("第三步失败", it) }
            runCatching { RuleTableInjector.apply(pluginCl) }
                .onFailure { XLog.e("第三步 B 档失败", it) }
            runCatching { AutoUiCloudInjector.apply(pluginCl, classLoader) }
                .onFailure { XLog.e("第二步失败", it) }
            XLog.i("全部注入完成，生效规则 ${RuleStore.activeRules().size} 条")
        }

        PluginClassLoaderCatcher.start(classLoader)
    }
}
