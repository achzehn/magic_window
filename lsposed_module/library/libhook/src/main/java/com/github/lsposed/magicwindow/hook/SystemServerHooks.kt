package com.github.lsposed.magicwindow.hook

import com.github.lsposed.magicwindow.hook.steps.ActivityTracker
import com.github.lsposed.magicwindow.hook.steps.AutoUiCloudInjector
import com.github.lsposed.magicwindow.hook.steps.EmbeddingQueryOverride
import com.github.lsposed.magicwindow.hook.steps.EmbeddedFixedCloudInjector
import com.github.lsposed.magicwindow.hook.steps.PluginClassLoaderCatcher
import com.github.lsposed.magicwindow.hook.steps.PropertyGate
import com.github.lsposed.magicwindow.hook.steps.RuleTableInjector
import com.github.lsposed.magicwindow.hook.steps.UserSettingSwitcher

/**
 * system_server 侧的注入编排。
 *
 * 精简取向（最低程度 hook、最低资源占用）：以云控方案为主路径——规则落盘到
 * /data/system/cloudFeature_*.xml 后由系统自行读取判定，不再挂运行时查询 hook。
 *   第 0 步 校验总开关 property（一次性写）
 *   第一步 捕获插件 ClassLoader（拿到即摘钩）
 *   第二步 云控注入（autoui / embedding / fixed）：hook 解析类的 loadPackage 仅为
 *         拿到宿主实例并缓存，之后配置变更经 RuleStore.onChange 用缓存实例热重载
 *   第三/四步 A·B 档查询 hook、用户开关运行时翻转：默认关闭，仅作降级备用
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
                .onFailure { XLog.e("第二步 autoui 云控注入失败", it) }
            runCatching { EmbeddedFixedCloudInjector.apply(pluginCl) }
                .onFailure { XLog.e("第二步 embedding/fixed 云控注入失败", it) }
            XLog.i("全部注入完成，生效规则 ${RuleStore.activeRules().size} 条")
        }

        // 配置保存后热更新：用缓存的宿主实例重新落盘云控 + 热重载，
        // 无需任何运行时查询 hook，也无需重启手机
        RuleStore.onChange {
            AutoUiCloudInjector.injectNow()
            EmbeddedFixedCloudInjector.injectNow()
        }

        PluginClassLoaderCatcher.start(classLoader)
    }
}
