# 项目踩坑约束

## LSPosed / libxposed API 102

- 反射定位系统插件方法时禁止硬编码参数个数：`MiuiParsingEmbeddedRule.loadPackage`（旧版 1 参 / Android 17 无参）、`MiuiParsingAutoUI.loadPackage`、`MiuiParsingFixedOrientationRule.loadLocalFixOrientationRuleXML` 都跨 ROM 分叉，按个数匹配会抛 `NoSuchMethodException` 并被 `runCatching` 吞掉，导致宿主实例恒为 null、规则永远不落盘。统一用 `Reflect.findMethods(name).minByOrNull { it.parameterCount }`。
- `XLog` / `XposedInterface.log` 的 tag 必须传 `null`，自定义 tag 会被 libxposed 日志框架过滤，LSPosed 管理器看不到任何模块输出（会误判为 hook 未运行）。
- API 102 下不要 hook 构造方法：构造方法的 `getThisObject()` 为 null 且 `proceed()` 返回值被框架忽略，取不到实例，只会白白付出拦截开销。捕获实例一律 hook 方法。
- `SharedPreferences` 内部用 `WeakHashMap` 持有 `OnSharedPreferenceChangeListener`：注册后必须用字段强引用住监听器，否则会被 GC，配置热更新静默失效且无日志。
- 框架服务的绑定是异步投递的，晚于模块 App 的 Activity 首帧：直接用「服务已绑定」渲染激活状态会让每次冷启动都先闪一下「未生效」再变绿。首帧必须用持久化的上次结论区分「检测中 / 未生效」，宽限计时只在前台运行（`onResume` 排程、`onPause` 取消）。
- `writeRules` 每次进程启动都会写远端首选项，即使内容没变；`SharedPreferences.Editor` 的 `commit()` 会无条件通知监听器，进而让 hook 侧重载规则、重算云控文件。写入前先比对远端当前值，一致就直接返回。

细节与修法见 `lsposed_module/Code_Wiki.md` 的「踩坑与修法」章节。

## 平行窗口规则生成

- 平行窗口能否触发取决于 `splitPairRule` 左栏是否命中用户实际打开的入口 Activity：AI 用 `recommended_main`（可能只是列表页/内部页）或多页面 `activityRule` 时，规则能落盘但分屏不触发。最小可用配置只有一对：`activityRule=启动器入口 Activity`、`splitPairRule=入口:*`，其余可选属性省略（`RulePresets.minimizeEmbedding`）。
- 三套机制互斥且优先级为 固定横屏 > 平行窗口 > 通用全屏；同一应用不要同时下发两套主规则。系统预置名单（`cloudFeature_fixed_orientation_list.xml` / `cloudFeature_autoui_list.xml` / `autoui_list_product.xml` / `embedded_setting_config.xml`）只读，模块只合并、不编辑。
- 固定横屏名单里 `disable="true"` 的应用属于「被系统禁用」：只允许出现在列表的「被系统禁用」分组，UI 只读，AI/MCP 的 `set_app_rule` 直接拒绝，不要为其生成任何规则。

## 真机验证

- 严禁在无线 adb 下执行 `su -c 'stop; start'` 重启 framework：framework 停止会切断 WiFi，adbd 无法再连，设备只能手动重启。需要让模块重新加载时，先提示用户，再由用户手动重启手机。

## Android UI / Material 3

- 主题里没有 `android:dialogAnimation` 这个属性，写了会直接编译失败（`style attribute ... not found`）。弹窗转场必须走 `ThemeOverlay`（parent `ThemeOverlay.Material3.MaterialAlertDialog`）内设置 `android:windowAnimationStyle`，再把 overlay 挂到 app 主题的 `materialAlertDialogTheme`。
- Material Chip 的 `chipMinHeight`（视觉高度）与触摸热区是两回事：`ensureMinTouchTargetSize=true` 时实际点击区会扩到 48dp，不要为了“看起来不臃肿”把高度压到 32dp 以下，否则 48dp 热区规范失守。
- `RecyclerView.layoutAnimation` 只在 `scheduleLayoutAnimation()` 之后的那次布局生效，必须在数据提交前调度；数据到齐后再调度会白等一次布局，动画不播放。
- 给带 `OnClickListener` 的卡片 / 列表行加按压缩放时，`OnTouchListener` 必须 `return false`，否则点击、长按、无障碍点击都会被吞掉（Lint 报 `ClickableViewAccessibility` 时按此排查）。

## 发布 / GitHub Actions

- 仓库必须同时保留 `lsposed_module/gradlew`（LF 换行、可执行位）与 `gradlew.bat`：工作流在 ubuntu runner 上 `chmod +x lsposed_module/gradlew` 后执行 `./gradlew`，只留 `.bat` 会让 CI 在「Grant execute permission」直接失败。
- `KEYSTORE_BASE64` Secret 必须与 `lsposed_module/magicwindow.keystore` 同步：Secret 陈旧会导致 CI 报 `Failed PKCS12 integrity checking`，而本地构建正常，容易误判为代码问题。更新命令：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("...\magicwindow.keystore")) | gh secret set KEYSTORE_BASE64`。
- `softprops/action-gh-release` 偶发 `Too many retries`：此时构建与 artifact 上传已成功，仅创建 Release 失败，用 `gh run download` 取产物后 `gh release create <tag> <apk> --notes-file ...` 补发即可，不必重跑整条流水线（重跑会用 TODO 模板覆盖已核对过的说明）。
- 发布产物核对：`aapt2 dump badging app-release.apk` 必须显示 `versionCode` / `versionName` 与 `app/build.gradle.kts` 一致。