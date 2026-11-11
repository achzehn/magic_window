# 项目踩坑约束

## LSPosed / libxposed API 102

- 反射定位系统插件方法时禁止硬编码参数个数：`MiuiParsingEmbeddedRule.loadPackage`（旧版 1 参 / Android 17 无参）、`MiuiParsingAutoUI.loadPackage`、`MiuiParsingFixedOrientationRule.loadLocalFixOrientationRuleXML` 都跨 ROM 分叉，按个数匹配会抛 `NoSuchMethodException` 并被 `runCatching` 吞掉，导致宿主实例恒为 null、规则永远不落盘。统一用 `Reflect.findMethods(name).minByOrNull { it.parameterCount }`。
- `XLog` / `XposedInterface.log` 的 tag 必须传 `null`，自定义 tag 会被 libxposed 日志框架过滤，LSPosed 管理器看不到任何模块输出（会误判为 hook 未运行）。
- API 102 下不要 hook 构造方法：构造方法的 `getThisObject()` 为 null 且 `proceed()` 返回值被框架忽略，取不到实例，只会白白付出拦截开销。捕获实例一律 hook 方法。
- `SharedPreferences` 内部用 `WeakHashMap` 持有 `OnSharedPreferenceChangeListener`：注册后必须用字段强引用住监听器，否则会被 GC，配置热更新静默失效且无日志。

细节与修法见 `lsposed_module/Code_Wiki.md` 的「踩坑与修法」章节。

## 真机验证

- 严禁在无线 adb 下执行 `su -c 'stop; start'` 重启 framework：framework 停止会切断 WiFi，adbd 无法再连，设备只能手动重启。需要让模块重新加载时，先提示用户，再由用户手动重启手机。

## Android UI / Material 3

- 主题里没有 `android:dialogAnimation` 这个属性，写了会直接编译失败（`style attribute ... not found`）。弹窗转场必须走 `ThemeOverlay`（parent `ThemeOverlay.Material3.MaterialAlertDialog`）内设置 `android:windowAnimationStyle`，再把 overlay 挂到 app 主题的 `materialAlertDialogTheme`。
- Material Chip 的 `chipMinHeight`（视觉高度）与触摸热区是两回事：`ensureMinTouchTargetSize=true` 时实际点击区会扩到 48dp，不要为了“看起来不臃肿”把高度压到 32dp 以下，否则 48dp 热区规范失守。
- `RecyclerView.layoutAnimation` 只在 `scheduleLayoutAnimation()` 之后的那次布局生效，必须在数据提交前调度；数据到齐后再调度会白等一次布局，动画不播放。
- 给带 `OnClickListener` 的卡片 / 列表行加按压缩放时，`OnTouchListener` 必须 `return false`，否则点击、长按、无障碍点击都会被吞掉（Lint 报 `ClickableViewAccessibility` 时按此排查）。