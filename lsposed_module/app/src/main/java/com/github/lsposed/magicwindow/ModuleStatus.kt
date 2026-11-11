package com.github.lsposed.magicwindow

import com.github.lsposed.magicwindow.data.XposedServiceBridge

/**
 * 激活状态探针。
 *
 * 现代 API 下模块 App 自身不再被 hook，改由 libxposed 框架服务是否绑定来判断：
 * 收到绑定即说明 LSPosed 已加载本模块，收不到则未激活。
 */
object ModuleStatus {

    @JvmStatic
    fun isModuleActive(): Boolean = XposedServiceBridge.isActive
}
