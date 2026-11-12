package com.github.lsposed.magicwindow

import android.app.Application
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.XposedServiceBridge

class MagicWindowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigRepository.init(this)
        // 载入上次确认的激活状态，供界面冷启动首帧判断显示「检测中」还是「未生效」
        XposedServiceBridge.init(this)
        // 框架服务绑定后补推一次规则：覆盖「上次保存时服务未就绪」与框架重启后的失同步
        XposedServiceBridge.onBind { ConfigRepository.pushToHook() }
        // 固定使用 HyperOS 风格品牌色板，不启用 DynamicColors（避免壁纸取色覆盖品牌蓝）
        // 系统规则预读放在 MainActivity：只有真正打开界面才拉 su 读云控，进程被后台拉起时不产生任何开销
    }
}
