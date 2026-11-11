# 项目踩坑约束

## LSPosed / libxposed API 102

- 反射定位系统插件方法时禁止硬编码参数个数：`MiuiParsingEmbeddedRule.loadPackage` 在旧版 ROM 为 1 参、Android 17（OS4.x）已改为无参，按个数匹配会抛 `NoSuchMethodException` 并被 `runCatching` 吞掉，导致 `embeddedRule` 恒为 null、平行窗口规则永远不落盘。统一用 `Reflect.findMethods(name).minByOrNull { it.parameterCount }`。
- `XLog` / `XposedInterface.log` 的 tag 必须传 `null`，自定义 tag 会被 libxposed 日志框架过滤，LSPosed 管理器看不到任何模块输出（会误判为 hook 未运行）。

细节与修法见 `lsposed_module/Code_Wiki.md` 的「踩坑与修法」章节。

## 真机验证

- 严禁在无线 adb 下执行 `su -c 'stop; start'` 重启 framework：framework 停止会切断 WiFi，adbd 无法再连，设备只能手动重启。需要让模块重新加载时，先提示用户，再由用户手动重启手机。