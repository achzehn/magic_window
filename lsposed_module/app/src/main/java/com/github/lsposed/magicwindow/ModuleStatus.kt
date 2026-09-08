package com.github.lsposed.magicwindow

/** 激活状态探针：模块生效时该方法会被 hook 成返回 true。 */
object ModuleStatus {

    @JvmStatic
    fun isModuleActive(): Boolean = false
}
