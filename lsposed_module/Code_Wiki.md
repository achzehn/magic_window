# Magic Window Adapter — Code Wiki

> 模块包名：`com.github.lsposed.magicwindow`
> 版本：2.0.0（versionCode 2）
> 许可证：Apache License 2.0

---

## 1. 项目概述

**完美横屏（Magic Window Adapter）** 是一个 LSPosed / Xposed 模块，用于将小米 HyperOS / MIUI 的三套大屏适配机制（平行窗口、固定横屏、通用全屏）以及界面自动适配（autoui）开放给用户自由配置，打破系统仅对内置名单生效的限制。

核心能力：
- 通过 Xposed Hook 注入 `system_server` 进程，把用户指定的应用「加进」系统大屏适配名单
- 提供完整的 Android UI，暴露 45+ 个规则属性供精细调节
- 内置 MCP（Model Context Protocol）调试服务器，支持 AI 模型通过局域网读写规则
- 热更新：App 保存配置后无需重启即可生效（通过广播通知 + 缓存实例重新落盘）

---

## 2. 项目结构

```
magic_window/
├── README.md                          # 项目说明
├── 完美横屏_使用文档.md                 # 完整用户使用文档
├── 完美横屏_LSPosed实现资料评估.md      # 反编译分析与技术方案评估
├── LICENSE                            # Apache 2.0
├── tmp/
│   ├── AI适配指导文档.md               # ★ AI 智能体适配规则生成指导（6 个 XML 文件 Schema + 模板）
│   └── 参考/                           # 真机提取的规则参考文件
│
└── lsposed_module/                    # Android 工程根目录
    ├── build.gradle.kts               # 根构建脚本（声明插件）
    ├── settings.gradle.kts            # 模块划分 + 国内镜像源
    ├── gradle.properties              # JVM 参数 + AndroidX 配置
    ├── gradle/
    │   ├── libs.versions.toml         # 版本目录（统一依赖版本管理）
    │   └── wrapper/                   # Gradle Wrapper
    │
    ├── app/                           # ── 主应用模块 ──
    │   ├── build.gradle.kts           # 应用构建配置（签名/编译选项）
    │   └── src/main/
    │       ├── AndroidManifest.xml    # Activity/LCPosed 元数据/权限
    │       ├── assets/xposed_init     # Xposed 入口类声明
    │       ├── res/                   # 布局/菜单/字符串/主题
    │       └── java/.../
    │           ├── MagicWindowApp.kt  # Application 初始化
    │           ├── ModuleStatus.kt    # 激活状态探针
    │           ├── ui/                # UI 层（4 个 Activity + 工具类）
    │           ├── data/              # 数据层（配置仓储/系统规则/导入导出）
    │           └── mcp/               # MCP 调试服务器
    │
    └── library/                       # ── 库模块 ──
        ├── common/                    # 共享数据模型与常量
        │   ├── build.gradle.kts
        │   └── src/main/java/.../
        │       ├── Constants.kt       # 全局常量（类名/文件名/属性名）
        │       └── model/
        │           ├── AppRule.kt     # 单应用规则数据模型（120+ 字段）
        │           ├── WindowMode.kt  # 主模式枚举（OFF/FULL_SCREEN/EMBEDDING/FIXED_ORIENTATION）
        │           ├── RuleCodec.kt   # 规则集 JSON 编解码
        │           └── CloudXmlCodec.kt # 云控 XML 解析/序列化/合并
        │
        └── libhook/                   # Hook 核心库
            ├── build.gradle.kts
            └── src/main/java/.../
                ├── XposedEntry.kt     # Xposed 入口（IXposedHookLoadPackage）
                ├── SystemServerHooks.kt # system_server 注入编排
                ├── RuleStore.kt       # 配置读取（内存快照 + FileObserver + 广播）
                ├── XLog.kt            # 统一日志出口
                └── steps/
                    ├── PropertyGate.kt           # 第 0 步：总开关 property 校验
                    ├── PluginClassLoaderCatcher.kt # 第一步：捕获插件 ClassLoader
                    ├── AutoUiCloudInjector.kt     # 第二步：autoui 云控注入
                    ├── EmbeddedFixedCloudInjector.kt # 第二步：embedding/fixed 云控注入
                    └── ConfigSyncReceiver.kt      # 配置热更新广播接收器
```

---

## 3. 架构设计

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                     用户界面（App 进程）                    │
│  MainActivity → AppListActivity → AppDetailActivity      │
│  McpServer (局域网 HTTP)                                  │
└──────────────────┬──────────────────────────────────────┘
                   │ SharedPreferences (MODE_WORLD_READABLE)
                   │ + 广播 ACTION_CONFIG_CHANGED
┌──────────────────▼──────────────────────────────────────┐
│               system_server 进程（Xposed 注入）            │
│                                                          │
│  XposedEntry                                             │
│    ├─ PropertyGate          (总开关兜底)                  │
│    ├─ PluginClassLoaderCatcher (捕获插件 ClassLoader)     │
│    ├─ AutoUiCloudInjector   (autoui 云控落盘 + 热重载)    │
│    ├─ EmbeddedFixedCloudInjector (embedding/fixed 注入)  │
│    │    ├─ doEmbedding()    (写入 embedding 云控 XML)    │
│    │    ├─ doFixed()        (写入 fixed 云控 XML)        │
│    │    └─ applyAppModes()  (翻转应用模式开关)            │
│    ├─ ConfigSyncReceiver    (接收配置变更广播)            │
│    └─ RuleStore             (内存快照配置缓存)            │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │         HyperOS 系统框架（miui-*.jar）            │   │
│  │  MiuiEmbeddingWindowService*                     │   │
│  │  MiuiParsingEmbeddedRule / FixedOrientationRule   │   │
│  │  MiuiParsingAutoUI                               │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 3.2 数据流

```
用户在 UI 修改规则
       │
       ▼
ConfigRepository.saveRule()
  ├─ prefs.commit() → LSPosed 托管目录
  └─ 500ms 延迟后发送广播 ACTION_CONFIG_CHANGED
       │
       ▼
ConfigSyncReceiver.onReceive()
  └─ RuleStore.notifyFromApp()
       ├─ RuleStore.loadNow()  → 重新读取 prefs → 更新内存快照
       └─ 触发 onChange 回调
            ├─ AutoUiCloudInjector.injectNow()  → 重新落盘 + 热重载
            └─ EmbeddedFixedCloudInjector.injectNow()
                 ├─ doEmbedding()  → 重新写入 embedding 云控 XML + 热重载
                 ├─ doFixed()      → 重新写入 fixed 云控 XML + 热重载
                 └─ applyAppModes() → 调用 onAppUiModeChanged 翻转模式
```

### 3.3 注入流程（开机时）

```
XposedEntry.handleLoadPackage("android")
  │
  ├─ SystemServerHooks.install(classLoader)
  │    ├─ RuleStore.loadNow()               # 同步读一次配置
  │    ├─ RuleStore.startWatching()         # 启动配置变更监听
  │    ├─ ConfigSyncReceiver.register()     # 注册配置变更广播（延迟重试）
  │    │
  │    ├─ [第 0 步] PropertyGate.apply()    # 校验总开关 property，必要时兜底 hook
  │    │
  │    ├─ PluginClassLoaderCatcher.start()  # [第一步] 捕获插件 ClassLoader
  │    │    ├─ 策略 1：直接用 system_server CL 试探
  │    │    ├─ 策略 2：hook ImplCollector 全部方法
  │    │    └─ 策略 3：hook Loader 全部方法
  │    │    └─ 拿到后立即摘钩，触发 whenReady 回调
  │    │
  │    ├─ whenReady 回调:
  │    │    ├─ AutoUiCloudInjector.apply()       # hook loadPackage → 落盘 + 抬版本
  │    │    └─ EmbeddedFixedCloudInjector.apply() # hook loadPackage/loadLocal...
  │    │         ├─ 拿到 mEmbeddedRule 实例
  │    │         ├─ 拿到 mFixOriController 实例
  │    │         └─ 2 秒延迟后异步注入（落盘 + 翻开关）
  │    │
  │    └─ RuleStore.onChange { ... }        # 配置变更时热重载
  │
  └─ ModuleStatus.isModuleActive() → hook 返回 true
```

---

## 4. 模块职责详解

### 4.1 `library:common` — 共享数据模型

无外部依赖（仅 `compileOnly(xposed-api)`），被 app 和 libhook 两个模块共同依赖。

| 文件 | 职责 |
|------|------|
| `Constants.kt` | 全局常量：包名、类名、方法名、文件名、系统 property 键等，全部来自逆向实证 |
| `AppRule.kt` | 核心数据模型，120+ 字段覆盖 embedding（25+属性）、fixedOrientation（15+属性）、autoui（6属性）以及比例档（3属性）。含 `toJson()` / `fromJson()` 序列化 |
| `WindowMode.kt` | 四种互斥模式的枚举：`OFF`、`FULL_SCREEN`、`EMBEDDING`、`FIXED_ORIENTATION`，key 与系统 JSON 对齐 |
| `RuleCodec.kt` | 规则集的 JSON 数组编解码，App 与 Hook 两侧共用 |
| `CloudXmlCodec.kt` | 云控 XML 的解析（`parse`）、合并（`mergeWith`）、序列化（`serialize`），以及 `AppRule` → 属性表的转换（`embeddingAttrsOf` / `fixedAttrsOf` / `fullScreenAttrsOf`） |

### 4.2 `library:libhook` — Hook 核心库

依赖 `library:common`，被 app 模块通过 `implementation` 引入。所有注入逻辑运行在 `system_server` 进程。

| 文件 | 职责 |
|------|------|
| `XposedEntry.kt` | Xposed 入口（`IXposedHookLoadPackage`）。作用域：`android`（全量注入）、模块自身（回显激活状态） |
| `SystemServerHooks.kt` | 注入编排器：按顺序调度第 0 步 → 捕获 ClassLoader → autoui 注入 → embedding/fixed 注入，并注册配置变更热重载 |
| `RuleStore.kt` | 配置读取层：基于 `XSharedPreferences` 的内存快照，FileObserver / 广播驱动热更新，热路径绝不做文件 IO |
| `XLog.kt` | 统一日志：写入 `XposedBridge.log`，仅 info / error 两级 |

**steps/ 子包（注入步骤）：**

| 文件 | 步骤 | 职责 |
|------|------|------|
| `PropertyGate.kt` | 第 0 步 | 校验三个 `SystemProperty` 总开关（AE/AutoUI/Characteristics），为 false 时 hook 兜底（`ro.` 属性无法 setprop） |
| `PluginClassLoaderCatcher.kt` | 第一步 | 三级降级策略捕获 HyperOS Stub 插件的 ClassLoader。拿到后**立即摘钩**，不影响系统热路径 |
| `AutoUiCloudInjector.kt` | 第二步 | hook `MiuiParsingAutoUI.loadPackage`，在 before 中落盘云控文件 + 抬高版本号，after 中触发热重载。支持指纹去重避免重复写文件 |
| `EmbeddedFixedCloudInjector.kt` | 第二步 | hook `MiuiParsingEmbeddedRule.loadPackage` / `MiuiParsingFixedOrientationRule.loadLocalFixOrientationRuleXML`，拿到宿主实例后缓存；异步执行「读全量 → 按包覆盖 → 写回 → 热重载 → 翻应用模式」 |
| `ConfigSyncReceiver.kt` | 热更新 | 注册 `ACTION_CONFIG_CHANGED` 广播，接收 App 侧保存通知并触发 `RuleStore.notifyFromApp()`。使用 `getSentFromPackage` 校验发送者 |

### 4.3 `app` — 用户界面

| 文件 | 职责 |
|------|------|
| `MagicWindowApp.kt` | Application：初始化 `ConfigRepository`、后台预热 `SystemRuleSource`（v2.3.0 起移除 DynamicColors，固定使用品牌色板） |
| `ModuleStatus.kt` | 激活状态探针：`isModuleActive()` 默认返回 false，被 Xposed hook 成返回 true |
| **ui/** | |
| `MainActivity.kt` (322行) | 主界面：模块状态显示、应用列表入口、MCP 服务器开关/配置（端口/令牌/客户端 JSON）、隐藏桌面图标、配置导入导出 |
| `AppListActivity.kt` (211行) | 应用列表：搜索/筛选（全部/已设置/被系统禁用/已安装/系统应用）、长按进入多选批量操作（套用模式/清除设置） |
| `AppDetailActivity.kt` (979行) | 应用详情：完整的 45+ 配置项表单，支持简单/高级切换、模式互斥联动、页面抓取填充、内置规则继承、系统禁用应用锁定、单规则导出导入 |
| `AppAdapter.kt` (145行) | RecyclerView 适配器：异步图标加载（LIFO 队列 + 缓存）、内置徽标/模式标签、多选模式 |
| `UiKit.kt` (202行) | 动态表单工具：`section`（分组卡片）、`switchRow`（开关行）、`chipBox/chip`（可勾选标签）、`textRow`（文本输入）、`dropdownRow`（下拉选择）、`note`（说明文字） |
| `ModeUi.kt` | 模式文案与配色映射 |
| `BuiltinUi.kt` | 「系统已内置」徽标文案生成 |
| **data/** | |
| `ConfigRepository.kt` | 配置仓储：SharedPreferences 读写（`MODE_WORLD_READABLE`）、规则 CRUD、保存后发广播通知 Hook 侧 |
| `AppItem.kt` | 应用列表项模型 + `AppLoader`（按包名/标签排序加载已安装应用） |
| `SystemRuleSource.kt` (343行) | 系统规则读取器：通过 root 读取 `/data/system/` 云控文件 + `/product/etc/` 本地名单，按 `dataVersion` 比较选择生效版本；提供 `applyDefaults()` 用系统内置值填充 AppRule |
| `ConfigExporter.kt` | 配置导入导出（version 1/2 兼容），支持 v1 的 `globalConfig` 字段兼容 |
| **mcp/** | |
| `McpServer.kt` (442行) | 内置 MCP 调试服务器（Streamable-HTTP JSON-RPC 2.0），提供 8 个工具：`list_rules` / `get_rule` / `set_rule` / `delete_rule` / `get_system_rule` / `search_apps` / `list_activities` / `export_rules`。支持令牌鉴权、端口配置、客户端配置 JSON 生成 |

**界面设计体系（HyperOS 4 风格，v2.3.0 起）：**

- 主题 `Theme.MagicWindow`（Material3 DayNight）：浅/暗色双套语义色板（`values/colors.xml` + `values-night/colors.xml`），品牌蓝 `#2F6BFF`；页面底色 `@color/app_bg`（浅灰 `#F3F4F7`/深黑 `#0B0E12`），卡片 `@color/card_bg`（白/深灰），零投影 + 22dp 大圆角（`Widget.MagicWindow.Card`）。
- 组件样式集中在 `values/themes.xml`：按钮 14dp 圆角不全大写、FAB 18dp 圆角方形、筛选 Chip 12dp 圆角无描边（选中=primaryContainer）、描边输入框 14dp 圆角、搜索框灰色胶囊（`Widget.MagicWindow.SearchBar`）、大标题 26sp bold（`TextAppearance.MagicWindow.LargeTitle`）。
- 手机/平板适配全部走资源限定符，无额外代码分支：
  - `values-sw600dp/`、`values-sw840dp/`：`page_padding` 加宽（16→56→128dp）使内容居中收窄；`integers.xml` 的 `list_columns`（1/2/3）驱动应用列表在平板上变为卡片网格（`AppListActivity` 按列数切换 `GridLayoutManager`，列表项背景 `drawable-sw600dp/bg_app_item.xml` 变为白色圆角卡片）；
  - AI 聊天气泡最大宽度 `chat_bubble_user/ai` 随屏宽放大；
  - 应用图标统一通过 `bg_icon_mask.xml` + `clipToOutline` 裁成圆角。

---

## 5. 关键类与函数说明

### 5.1 `AppRule` — 核心数据模型

位置：`library/common/src/.../model/AppRule.kt`

```kotlin
data class AppRule(val packageName: String) {
    var enabled: Boolean          // 是否启用本模块的注入
    var mode: WindowMode          // 主模式（互斥单选）

    // ── 平行窗口 embedding（25+ 属性）──
    var supportFullSize: Boolean  // 可放大到整屏
    var isShowDivider: Boolean    // 显示分割线
    var skipSelfAdaptive: Boolean // 跳过应用自适应（100% 必带）
    var splitRatio: String        // 左栏宽度占比
    var activityRule: String      // 参与分屏的页面
    var splitPairRule: String     // 左右两栏配对方式
    var placeholder: String       // 右栏默认页面
    // ... 更多属性见源码

    // ── 固定横屏 fixed orientation（15+ 属性）──
    var foSupportModes: String    // 支持档位（恒为 "full,fo"）
    var foDefaultSettings: String // 默认档（"fo" 或 "full"）
    var foDisable: Boolean        // 停用固定横屏
    // ... 更多属性见源码

    // ── 界面自动适配 autoui（6 属性）──
    var autoUiEnable: Boolean     // autoui 总开关
    var autoUiActivityRule: String

    // ── 显示比例（三选一）──
    var ratio43Enable: Boolean
    var ratio169Enable: Boolean
    var ratioFullScreenEnable: Boolean

    fun toJson(): JSONObject      // 序列化
    companion object { fun fromJson(o: JSONObject): AppRule }  // 反序列化（兼容旧版）
}
```

### 5.2 `CloudXmlCodec` — 云控 XML 编解码

位置：`library/common/src/.../model/CloudXmlCodec.kt`

| 方法 | 说明 |
|------|------|
| `parse(text)` | 解析云控 XML 为 `Map<包名, Map<属性, 值>>` |
| `mergeWith(base, overrides)` | 以系统全量规则为底，按包名覆盖模块规则 |
| `serialize(table, kind, dataVersion)` | 序列化为系统可读的云控 XML |
| `embeddingAttrsOf(rule)` | AppRule → embedding 属性表（空值不写入） |
| `fixedAttrsOf(rule)` | AppRule → fixed 属性表（分档布尔写成 `fo:true` 格式） |
| `fullScreenAttrsOf(rule)` | AppRule → 通用全屏占位属性（只写 `fullRule`） |

### 5.3 `RuleStore` — system_server 侧配置缓存

位置：`library/libhook/src/.../hook/RuleStore.kt`

```kotlin
object RuleStore {
    fun loadNow()                           // 阻塞读一次配置（仅入口调用）
    fun startWatching()                     // 启动 FileObserver 或轮询兜底
    fun notifyFromApp()                     // 广播触发的热更新
    fun onChange(listener: () -> Unit)      // 注册配置变更回调
    fun activeRules(): List<AppRule>        // 读取内存快照（热路径安全）
    fun autoUiRules(): List<AppRule>        // 过滤出 autoUiEnable=true 的规则
}
```

性能设计：读侧全在内存（`@Volatile snapshot`），FileObserver 事件驱动 + 500ms 去抖，热路径零 IO。

### 5.4 `PluginClassLoaderCatcher` — ClassLoader 捕获器

位置：`library/libhook/src/.../steps/PluginClassLoaderCatcher.kt`

```kotlin
object PluginClassLoaderCatcher {
    fun start(systemServerClassLoader: ClassLoader)  // 启动三级降级捕获
    fun whenReady(action: (ClassLoader) -> Unit)     // 注册就绪回调
}
```

捕获策略：
1. 直接用 system_server ClassLoader 试探 `MiuiSystemEmbeddedRule`
2. hook `MiuiEmbeddingWindowServiceImplCollector` 全部方法，从返回实例取 ClassLoader
3. hook `MiuiEmbeddingWindowServiceLoader` 全部方法，同上

**关键设计**：拿到 ClassLoader 后立即 `unhook()` 摘除所有捕获钩子，避免拖慢系统热路径。

### 5.5 `EmbeddedFixedCloudInjector` — 主注入路径

位置：`library/libhook/src/.../steps/EmbeddedFixedCloudInjector.kt`

```kotlin
object EmbeddedFixedCloudInjector {
    fun apply(pluginClassLoader: ClassLoader)  // 注册 hook，捕获宿主实例
    fun injectNow()                            // 重新落盘 + 热重载（配置变更时调用）
}
```

注入原理（云控全量生效机制）：
1. 系统读取云控文件时「存在即全量生效、内置文件被忽略」
2. 模块读取系统当前全量规则（云控优先，没有读本地名单）
3. 按包名覆盖用户配置的应用规则
4. 写回 `/data/system/cloudFeature_*.xml`
5. 调用系统方法触发热重载
6. 通过 `onAppUiModeChanged` 翻转每个应用的当前模式

### 5.6 `McpServer` — MCP 调试服务器

位置：`app/src/.../mcp/McpServer.kt`

提供 8 个 MCP 工具：

| 工具 | 说明 |
|------|------|
| `list_rules` | 列出本模块已配置的全部应用规则 |
| `get_rule` | 读取某个应用的规则 |
| `set_rule` | 创建或更新规则（增量更新，保存后立即热生效） |
| `delete_rule` | 删除规则 |
| `get_system_rule` | 读取系统内置规则（三个名单的原始属性） |
| `search_apps` | 按关键词搜索已安装应用 |
| `list_activities` | 列出某个应用的全部 Activity |
| `export_rules` | 导出规则为本地 JSON 文件 |

接入方式：`POST http://<IP>:8765/mcp`，JSON-RPC 2.0 协议。

---

## 6. 依赖关系

### 6.1 模块依赖

```
app ──→ library:common
    ──→ library:libhook ──→ library:common
    ──→ xposed-api (compileOnly)
    ──→ AndroidX + Material
```

### 6.2 外部依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| AGP | 8.7.0 | Android 构建插件 |
| Kotlin | 2.0.0 | 语言 |
| Xposed API | 82 | Xposed Hook 框架接口（compileOnly） |
| AndroidX Core KTX | 1.13.1 | Kotlin 扩展 |
| AndroidX AppCompat | 1.7.0 | 兼容性支持 |
| AndroidX RecyclerView | 1.3.2 | 列表视图 |
| AndroidX ConstraintLayout | 2.1.4 | 约束布局 |
| AndroidX Lifecycle | 2.8.4 | 生命周期管理 |
| Material | 1.12.0 | Material Design 3 组件 |

### 6.3 编译环境

| 项目 | 要求 |
|------|------|
| compileSdk | 35 |
| minSdk | 31 |
| targetSdk | 35 |
| JVM Target | 17 |
| Gradle | 8.10 (wrapper) |
| LSPosed API | 102+ |

---

## 7. 系统内部类映射

项目通过反射与 Hook 交互的 HyperOS / MIUI 内部类：

| 常量 | 内部类 | 说明 |
|------|--------|------|
| `CLASS_EWS_LOADER` | `MiuiEmbeddingWindowServiceLoader` | embedding 服务加载器 |
| `CLASS_EWS_IMPL_COLLECTOR` | `MiuiEmbeddingWindowServiceImplCollector` | embedding 服务实现收集器 |
| `CLASS_SYSTEM_EMBEDDED_RULE` | `MiuiSystemEmbeddedRule` | 系统 embedding 规则（用于 ClassLoader 探测） |
| `CLASS_PARSING_EMBEDDED_RULE` | `MiuiParsingEmbeddedRule` | embedding 规则解析（hook `loadPackage`） |
| `CLASS_PARSING_AUTO_UI` | `miui.autoui.MiuiParsingAutoUI` | autoui 规则解析（hook `loadPackage`） |
| `CLASS_SYSTEM_AUTO_UI_RULE` | `miui.autoui.MiuiSystemAutoUIRule` | autoui 系统规则 |
| `CLASS_AUTO_UI_PACKAGE_RULE` | `miui.autoui.policy.rule.PackageRule` | autoui 应用规则构造 |
| `CLASS_AE_PROP` | `MiuiAppAdaptationProperties$ActivityEmbeddingProp` | AE 总开关 |
| `CLASS_AUTO_UI_MANAGER_STUB` | `miui.autoui.MIUIAutoUIManagerStub` | AutoUI 总开关 |

---

## 8. 系统规则文件

> **AI 适配详细 Schema 与模板请参阅** `tmp/AI适配指导文档.md`，包含 6 个 XML 文件的完整属性字典、格式规范、常见场景模板和排错指南。

### 8.1 文件位置

| 类型 | 云控目录 | 本地目录 |
|------|----------|----------|
| embedding | `/data/system/cloudFeature_embedded_rules_list*.xml` | `/product/etc/embedded_rules_list*.xml` |
| fixed | `/data/system/cloudFeature_fixed_orientation_list*.xml` | `/product/etc/fixed_orientation_list*.xml` |
| autoui | `/data/system/cloudFeature_autoui_list.xml` | `/product/etc/autoui_list.xml` |

本地目录搜索顺序：`/product/etc/` → `/system_ext/etc/` → `/system/etc/`

### 8.2 文件选择逻辑

云控与本地各取第一个能解析的文件，比较根标签 `dataVersion`：
- 云控版本 >= 本地版本 → 用云控
- 否则用本地
- 只有一份时直接用

---

## 9. 配置存储

| 用途 | 位置 | 说明 |
|------|------|------|
| 模块规则 | SharedPreferences `magic_window_config`（键 `app_rules`） | LSPosed `xposedsharedprefs` 托管，system_server 侧用 `XSharedPreferences` 读取 |
| 页面抓取记录 | SharedPreferences `magic_window_capture`（键 `records`） | 格式 `包名|类名` |
| MCP 设置 | SharedPreferences `mcp_settings`（端口 + 令牌） | App 私有 |
| 云控规则文件 | `/data/system/cloudFeature_*.xml` | system_server 运行时写入，模块不会修改系统分区 |

---

## 10. 性能优化

- **热路径零 IO**：`RuleStore` 读侧全在内存快照，热路径不碰磁盘
- **Hook 即摘**：`PluginClassLoaderCatcher` 拿到 ClassLoader 后立即摘除所有捕获钩子
- **指纹去重**：`AutoUiCloudInjector` 和 `EmbeddedFixedCloudInjector` 都维护内容指纹，内容没变不重写文件
- **异步注入**：`EmbeddedFixedCloudInjector` 用后台线程延迟 2 秒注入，不阻塞系统启动
- **LIFO 图标加载**：`AppAdapter` 用 LIFO 队列让当前可见项优先加载图标
- **批量 su 调用**：`SystemRuleSource` 一次 `su -c` 读取所有云控文件，避免反复弹授权

---

## 11. 构建与运行

### 11.1 环境准备

- JDK 17+
- Android SDK（compileSdk 35）
- Gradle（通过 wrapper 自动下载）

### 11.2 构建命令

```bash
cd lsposed_module

# Debug 构建
.\gradlew assembleDebug

# Release 构建
.\gradlew assembleRelease

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 11.3 安装与激活

1. 安装 APK
2. 打开 LSPosed Manager → 模块 → 完美横屏，启用模块
3. 作用域**只勾「系统框架」（Android Framework）**
4. 重启手机
5. 打开模块界面，顶部应显示「模块已生效」

---

## 12. Xposed 作用域说明

```
xposed_scope = ["android", "com.github.lsposed.magicwindow"]
```

- `android`（system_server）：全部注入逻辑（云控落盘、属性钩子、配置热更新）
- 模块自身：仅用于回显「已激活」状态（`ModuleStatus.isModuleActive()` hook 成 true）

**不需要勾选任何被适配的应用**——所有注入都发生在 `system_server` 里。

**双轨声明（v2.3.0 起，参考 HyperCeiler-lite 写法）**：除上述 legacy 声明外，还打包了新版 libxposed 格式的 `META-INF/xposed/scope.list`（源文件在 `app/src/main/resources/META-INF/xposed/`，内容为 `system` + 模块自身），供读取 scope.list 的新版管理器（LSPosed_mod/Vector 等）识别推荐作用域。注意：
- 本模块是**经典 API 模块**，`META-INF/xposed/` 下**严禁添加 `java_init.list` / `module.prop`**——加了会被 LSPosed 识别为新版模块并走 libxposed 加载路径，经典入口（`assets/xposed_init` → `hook.XposedEntry`）将失效
- 官方 LSPosed 对经典模块只读 Manifest meta-data，不受 scope.list 影响

---

## 13. 已知限制与风险

- 依赖 HyperOS / MIUI 私有实现，非小米系统不适用
- 换机型 / 大版本升级后可能因内部类名变化而失效
- 「直接写进系统规则表」依赖内部结构，异常时优先关掉
- MCP 服务器应用退到后台后可能被系统暂停
- 部分 MIUI 版本可能存在 FileObserver SELinux 权限限制（已通过广播兜底）

---

## 14. 近期修改记录

### v2.1.0（2026-09-13）

**Bug 修复：**

1. **模式切换未保存问题**（#10）：引入 `pendingMode` 机制，用户切换模式后未点保存返回时，模式不会被修改。只有点击保存按钮时才将 `pendingMode` 写入 `rule.mode`。

2. **强制修改开关状态不持久化**（#1）：`forceEdit` 从 `AppDetailActivity` 的局部变量迁移到 `AppRule` 数据模型中，随规则一起序列化/反序列化，重进页面后状态保持。

3. **恢复默认后规则异常**（#2）：`applyBuiltinDefaults()` 恢复默认时重置 `foDisable` 标记，确保规则组合有效；同时同步 `pendingMode` 和 `swForceEdit` 控件状态。

**功能增强：**

4. **splitRatio 滑块**（#3）：平行窗口的「左栏宽度占比」从文本输入框改为 `MaterialSlider`，支持 0.1~0.9 无极拖动，步长 0.05，到达常用节点时触发震动反馈。

5. **embedding 默认填充 launcher**（#4）：选择平行窗口模式时，自动将应用的 launcher Activity 填入 `activityRule` 和 `splitPairRule`，省去手动填写。

6. **foDefaultSettings 默认值修正**（#5）：固定横屏模式的默认档位从 `fo`（信箱）改为 `full`（全屏拉伸）。

7. **fullRule 默认值修正**（#6）：通用全屏模式的整屏显示方式默认改为 `nra:cr:rcr:nr`（不重建+裁圆角）。

8. **模板系统**（#7）：新增 `TemplateManager`，为每种模式预定义 2~3 个常用模板（如「高效分栏」「信箱模式」「全屏拉伸」等），用户可在详情页点击「选择模板」快速套用。

9. **补充 page picker 交互**（#8）：`placeholder`、`flags`、`autoUiRule`、`forcePortraitWhenSwitch`、`sizecompatRule`、`foRelaunchRule` 等字段新增放大镜图标，点击可从应用 Activity 列表中多选填充。

10. **全局震动反馈**（#9）：在模式切换、Chip 选择、滑块到达节点、保存、恢复默认等关键交互点添加短震动反馈，提升操作确认感。

**新增文件：**

| 文件 | 说明 |
|------|------|
| `app/.../data/TemplateManager.kt` | 模板管理器：预定义模板定义与应用逻辑 |
| `app/src/main/res/layout/row_slider.xml` | 滑块行布局：标签+当前值+滑块 |

**修改文件：**

| 文件 | 变更 |
|------|------|
| `AppRule.kt` | 新增 `forceEdit` 字段；`fullRule` 默认值改为 `nra:cr:rcr:nr`；`foDefaultSettings` 默认值改为 `full` |
| `AppDetailActivity.kt` | `pendingMode` 机制；`forceEdit` 持久化；模板选择 UI；splitRatio 滑块；page picker 补充；震动反馈 |
| `UiKit.kt` | 新增 `vibrate()` 震动工具方法；新增 `sliderRow()` 滑块组件 |
| `strings.xml` | 新增模板相关字符串资源 |

---

### v2.2.0（2026-09-13）— AI 助手与 Root 适配

**新增 AI 大模型能力：**

1. **多模型管理**（`ai/ModelManager.kt`）：替代旧的单模型 `AiSettings`，支持多模型增删改查与全局切换。`ModelConfig` 含 apiBase/modelId/apiKey、token 上限、temperature/topP/topK、toolRounds、`enabled` 启停字段；SharedPreferences（`ai_models`）持久化。`getCurrent()` 只返回已启用模型，停用/删除当前模型时自动回退。

2. **模型管理对话框**（`MainActivity.showModelManagerDialog()`）：自定义 ListView，每行右侧依次为「编辑（铅笔）/ 删除（垃圾桶）/ 启停开关」。编辑复用 `showModelConfigDialog(model, onSaved)`（含高级设置折叠区与连通性测试），对话框叠加在管理列表之上，保存后列表自动刷新。

3. **AI 对话页**（`ai/AiChatActivity.kt`）：Kimi 风格 UI，底部输入栏含附件（图片/文本文件）、模型切换 chip（全局生效，仅列已启用模型）、发送 FAB；支持聊天历史（`ai/ChatHistory.kt`）、多轮 function calling（`ai/AiClient.kt`，兼容 OpenAI 协议）、7 个规则工具（`ai/AiToolExecutor.kt`）、系统提示词（`ai/AiSystemPrompt.kt`）。

4. **页面抓取 AI 增强**（`AppDetailActivity.showPagePicker()`）：
   - `勾选AI推荐（上下文）`：按入口字段（activityRule/splitPairRule/placeholder 等）生成上下文感知 prompt，AI 推荐项一键勾选；
   - `补全中文说明`：使用**独立 prompt**，只返回「类名|中文说明」，不修改勾选/推荐集合（修复了早期复用推荐解析器导致误勾选的问题）；结果写入 `data/ActivityLabelCache.kt`（按「包名|Activity」持久化），后续抓取直接读缓存，节省 token；
   - 列表项布局 `item_page_picker.xml`：类名单行省略 + 中文说明小字行 + AI 标签。

**Root 权限适配（Magisk / KernelSU / APatch）：**

5. **启动检测重构**（`MainActivity`）：
   - `hasRootIndicatorFile()` 同步检测三家 Root 工具特征目录（`/data/adb/magisk`、`/data/adb/ksu`、`/data/adb/apatch` 等）与传统 su 路径，命中即直接进入，不再误报；
   - 未命中时在后台线程执行 `verifySuGrant()`（`su -c id` 校验 `uid=0`，3 轮退避重试等授权弹窗），UI 显示「正在获取 Root 权限」提示，成功结果在进程内缓存（companion `rootConfirmed`）；
   - `SystemRuleSource.rootAvailable` 预热成功也作为放行依据。

6. **云控读取加固**（`SystemRuleSource.readViaRoot()`）：3 次重试（退避 2s/3s）；stdout/stderr 独立线程并发排空，防止大 XML 写满管道导致进程挂死；超时放宽到 20s；输出含分隔标记即认定 su 已授权（即使云控文件为空），避免「已授权但无文件」被误报为未取得 root。

7. **placeholder 专用配对选择器**（`AppDetailActivity.showPlaceholderPairPicker()`）：新增 `Fill.PLACEHOLDER` 类型。旧逻辑复用 PAIR 选择器会生成 `页面:*` 且易把「主页面/占位页面」顺序选反；新选择器分两段单选——① 主页面（左栏）② 右栏默认占位页面，校验两项非空且不相同，确认生成「主页面:占位页面」，并保留已有其他配对。对话框顶部提供「🤖 AI推荐配对」（一次性返回两段选择，第三段同时写中文说明）、「补全中文说明」（只更新说明不改变选择）、「清空选择」三个按钮；中文说明优先读 `ActivityLabelCache`，AI 补全后实时刷新。AI 系统提示词同步强调冒号左侧必须是主页面、顺序不可颠倒。

**新增文件：**

| 文件 | 说明 |
|------|------|
| `app/.../ai/ModelManager.kt` | 多模型配置管理（增删改查/启停/当前选择） |
| `app/.../ai/AiClient.kt` | OpenAI 兼容客户端，多轮 function calling + 连通性测试 |
| `app/.../ai/AiChatActivity.kt` | Kimi 风格 AI 对话页（附件/历史/全局切模型） |
| `app/.../ai/ChatHistory.kt` | 聊天历史持久化 |
| `app/.../ai/AiSystemPrompt.kt` | AI 系统提示词（四模式+autoui 叠加说明） |
| `app/.../ai/AiToolExecutor.kt` | 7 个规则读写工具执行器（set_app_rule 含 17 个参数） |
| `app/.../data/ActivityLabelCache.kt` | Activity 中文说明缓存（按包名） |
| `app/src/main/res/layout/dialog_ai_model.xml` | 模型配置对话框（基础+高级折叠+测试） |
| `app/src/main/res/layout/activity_ai_chat.xml` | AI 对话页布局 |
| `app/src/main/res/layout/item_page_picker.xml` | 页面抓取列表项（类名+中文说明+标签） |

**生命周期：** 全部 Activity 在 `AndroidManifest.xml` 声明 `configChanges`（orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden），旋转不重建页面，兼顾手机与平板。

### v2.3.0（2026-09-13）— HyperOS 4 风格界面改版 + 手机/平板适配

**设计系统（纯 View/XML，无 Compose 依赖）：**

1. **主题与配色**：`values/themes.xml` 重写 `Theme.MagicWindow`，映射全套 M3 语义色到自定义色板；新增 `values-night/colors.xml` 暗色版（品牌蓝降饱和、模式标签改浅色配深字）。页面灰底白卡、系统栏透明边到边、`app_bg` 色贯穿 AppBar/底栏/输入栏。`MagicWindowApp` 移除 `DynamicColors.applyToActivitiesIfAvailable`，Android 12+ 也固定品牌蓝。
2. **组件 HyperOS 化**：卡片 22dp 圆角零投影；按钮 14dp 圆角、不全大写；筛选 Chip 12dp 圆角无描边（颜色选择器 `color/chip_filter_*.xml`）；搜索框改灰色胶囊填充；FAB 改 18dp 圆角方形；新增自定义矢量图标（`ic_sparkle/ic_send/ic_add/ic_tune/ic_apps/ic_shield_check/ic_bolt/ic_chevron_down`）替换系统框架图标；主界面四张卡片加彩色（蓝/绿/紫/琥珀）圆角图标容器；AI 聊天用户气泡改品牌蓝、AI 头像改 sparkle 容器、输入框圆角胶囊。
3. **手机/平板适配**：
   - `values-sw600dp/`（7 寸）：`page_padding=56dp`，应用列表 2 列卡片网格；
   - `values-sw840dp/`（10 寸）：`page_padding=128dp`，应用列表 3 列；
   - `AppListActivity` 读取 `R.integer.list_columns` 在 `LinearLayoutManager`/`GridLayoutManager` 间切换；列表项背景按限定符在「通栏水波纹」与「18dp 圆角白卡」（`drawable-sw600dp/bg_app_item.xml`）间切换；
   - AI 聊天气泡宽度、输入栏/消息列表边距随屏宽放大。
4. **细节**：应用图标 `bg_icon_mask.xml` + `clipToOutline` 统一圆角（列表 11dp、详情 48dp 图标）；详情底部操作栏加分隔线与 8dp 投影；滑块行改为内嵌 16dp 圆角填充卡；模型状态「已配置」标签改圆角 pill（`bg_pill.xml`，`MainActivity` 用 `backgroundTintList` 着色而非 `setBackgroundColor`）；AI 模型对话框全部输入框继承新圆角样式。
5. **Bug 修复**：MCP 未启动时点「客户端配置 JSON」闪退（`UninitializedPropertyAccessException: appContext`，`McpServer.clientConfigJson()` 依赖仅在 `start()` 里初始化的 lateinit 字段）。改为 `clientConfigJson(context)` 由调用方传入 Activity Context 读取令牌；`localUrl()`/`curPort()` 本就无需实例上下文，不受影响。

---

### v2.4.0（2026-09-14）— 界面视觉统一优化 + 正式版发布

**页面抓取与配对对话框视觉统一：**

1. **统一样式系统**：在 `AppDetailActivity` 新增一组样式 helper 函数，确保多选抓取和单选配对两个对话框的视觉完全一致：
   - `stylePickerSearch()`：搜索框圆角浅底 + 左侧搜索图标，12dp 圆角（`bg_picker_search.xml`），13sp 文字，去掉裸下划线
   - `aiActionButton()`：AI 操作按钮（Tonal/Outlined 两种样式），等高单行、紧凑 12sp 文字、左右间距归零
   - `textActionButton()`：文字操作按钮（补全中文/清空），无背景、品牌蓝文字
   - `bindAiTag()`：AI 标签胶囊自动着色（适合=绿、不适合=红、中性=灰）

2. **列表项布局重构**（`item_page_picker.xml`）：
   - 类名从单行改为两行显示（`maxLines=2`），避免长类名截断
   - 第二行改为「中文说明（左）+ AI 标签胶囊（右）」并排布局
   - 中文说明小字 11.5sp 灰色，AI 标签 10.5sp 胶囊化

3. **单选/多选视觉区分**：
   - 配对选择器（单选场景）改用圆形单选圆点（`selector_radio.xml`，`ic_radio_off/ic_radio_on`），与多选场景的方形勾选框明确区分
   - 批量操作按钮改为轻量文字按钮，减少视觉重量

4. **防止对话框裁切**：配对对话框外层包裹 `ScrollView`，避免小屏设备上第二段列表被底部按钮裁切

**新增资源文件：**

| 文件 | 说明 |
|------|------|
| `res/drawable/bg_picker_search.xml` | 搜索框 12dp 圆角浅灰背景 |
| `res/drawable/selector_radio.xml` | 单选圆点选择器（选中/未选中状态） |
| `res/drawable/bg_tag_negative.xml` | "不适合"标签浅红背景 |
| `res/drawable/bg_tag_neutral.xml` | 中性标签浅灰背景（"适合"标签通过 setTint 着色） |

**修改文件：**

| 文件 | 变更 |
|------|------|
| `AppDetailActivity.kt` | 新增 4 个样式 helper 函数；列表项渲染逻辑重构；配对对话框加 ScrollView 包裹 |
| `item_page_picker.xml` | 类名 maxLines 改为 2；第二行改为中文说明 +AI 标签并排布局 |

**正式版发布：**

- 完成正式版签名密钥生成（`magicwindow.keystore`）
- 成功构建 Release APK：`app-release.apk`（10.9 MB，versionCode 2，versionName 2.0.0）
- 构建路径：`lsposed_module/app/build/outputs/apk/release/app-release.apk`

---

### v2.4.1（2026-09-14）— AI 工具参数对齐适配指导文档

依据 `tmp/AI适配指导文档.md` 的规则体系审计结果，补齐 AI 助手能力缺口：

1. **`set_app_rule` 工具参数扩展**（`AiToolExecutor.kt`）：从 6 个可选参数扩至 16 个，新增：
   - embedding：`placeholder`（右栏占位页）、`transition_rules`（过渡页）、`support_full_size`、`is_show_divider`
   - fixed：`fo_support_modes`、`fo_ratio`、`fo_force_portrait_activity`
   - autoui（叠加在任意模式上）：`autoui_enable`、`autoui_activity_rule`、`autoui_skipped_activity_rule`
   - 布尔参数仅在 AI 显式传入时覆盖（`args.has()` 判断），避免悄悄改掉既有配置

2. **系统提示词补全**（`AiSystemPrompt.kt`）：
   - 新增「界面适配（autoui）— 叠加增强，不是独立模式」章节（参数格式、模式码 1/2/6、通配符 `*:1` 用法）
   - embedding 关键参数补充 `transitionRules` 说明
   - 新增「高级参数说明」：明确 flags/procCompat/minSupportVersion 等不在工具支持范围，引导用户到应用详情页高级模式手动设置，禁止 AI 编造参数值

3. **审计结论（无代码缺陷项）**：
   - autoui 云控注入走 `AutoUiCloudInjector` 反射路径（构造系统 `PackageRule` 对象 + `updateAutoUICloudConfigFile`），`RuleStore.autoUiRules()` 已按 `autoUiEnable` 过滤，`CloudXmlCodec` 无需 autoui 序列化方法
   - `embedded_setting_config.xml` 的三个互斥开关由 `WindowMode` 枚举 + `fullScreenAttrsOf`（fullRule 占位）+ hook 查询接口覆盖，为既定架构设计，不改动

**修改文件：**

| 文件 | 变更 |
|------|------|
| `app/.../ai/AiToolExecutor.kt` | `set_app_rule` 工具定义与实现扩展 10 个参数 |
| `app/.../ai/AiSystemPrompt.kt` | 新增 autoui 章节 + transitionRules + 高级参数引导 |

**真机 XML 交叉验证后追加修正（同日第二轮审计）：**

以 `tmp/参考/` 真机规则文件逐条核对 AI 推荐内容，发现并修正 4 处不一致：

1. **指导文档 §3.1 `activityRule` 格式纠错**：原写成「`类名:模式码` + 分号分隔」，真机 embedded 数据（8000+ 条）实际为**逗号分隔的纯 Activity 全类名列表**，不带模式码；「模式码 + 分号」格式属于 autoui（§3.3），文档已修正并加防混淆警告。§11 模式码表同步标注"仅 autoui 使用"。
2. **`forcePortraitActivity` 真实格式补全**（文档 §3.1/§3.2 + AI 工具 + 提示词）：真机 20/20 样本均为 `包名/类名`（`/` 分隔，`/` 右侧可 `.相对类名`），多个逗号分隔，**不是**纯全类名，fixed 侧不带 `:1` 后缀。注意：`AppDetailActivity` 高级模式的 embedding 侧 `forcePortraitActivity` 输入框（Fill.LIST 抓取生成纯全类名逗号列表）与真机格式不符，属遗留问题待后续核对。
3. **提示词场景矛盾修复**：原推荐「京东→平行窗口」与文档「京东已自带适配」冲突，已将京东移出推荐并明确禁止；已适配示例扩为 微信/微博/酷安。
4. **fullRule 禁忌写入提示词与工具描述**：embedding 模式设置 fullRule 会被系统判定为「支持全屏、不支持平行窗口」（`CloudXmlCodec.embeddingAttrsOf` 注释约束），平行窗口章节与 `full_rule` 参数描述均已加⚠️警告。

**修改文件（第二轮）：**

| 文件 | 变更 |
|------|------|
| `tmp/AI适配指导文档.md` | §3.1 activityRule 纠错、forcePortraitActivity 格式规范新增、§3.2 fixed 侧格式、§10 排错表 +2 行、§11 模式码标注 |
| `app/.../ai/AiToolExecutor.kt` | `activity_rule`/`force_portrait_activity`/`fo_force_portrait_activity` 格式说明、`full_rule` 警告 |
| `app/.../ai/AiSystemPrompt.kt` | activityRule 防混淆、forcePortrait 格式、fullRule 禁忌、京东场景矛盾修复 |

**forcePortraitActivity 格式遗留问题修复（同日第三轮）：**

修复第二轮审计指出的 UI 层遗留问题——`forcePortraitActivity` 系列字段抓取生成**纯全类名**逗号列表，与真机要求的「包名/类名」格式不符：

1. **新增 `Fill.PKG_CLASS` 抓取类型**（`AppDetailActivity`）：
   - 输出转换：抓取的 Activity 全类名转成「包名/类名」，类名以包名为前缀时缩写为 `.相对类名`（`com.pkg/.ui.ScanActivity`），与系统内置规则写法一致；否则写 `包名/全类名`
   - 回显还原：打开抓取器时把已有值还原成全类名比对勾选状态，兼容三种历史格式（`.相对类名`、`包名/全类名`、纯全类名）——**旧格式规则重新抓取保存一次即自动转换为新格式**
   - 新增 helper：`toPkgClassForm()`（输出）、`flattenPkgClass()`（回显）
2. **三个字段切换到 PKG_CLASS 并更新提示**：embedding `forcePortraitActivity`、fixed `foForcePortraitActivity`、fixed `foFullForcePortraitActivity`（真机 6/6 样本确认同为包名/类名格式）
3. **`foAdjustmentOrientationActivity` 保持不动**：真机参考数据中零样本，"包名/类名:1" 提示格式无从验证

### v2.4.2（2026-09-14）— AI/MCP 调试工具统一 + 闪退定位 + MCP 性能优化

#### 1. 共享诊断模块 `data/RuleDiagnostics.kt`（新建）

AI 助手（`AiToolExecutor`）与 MCP 服务器（`McpServer`）共用的 debug 逻辑，保证两侧工具**同名同义同实现**：

- `validate()`：规则格式逐条校验（分隔符、`包名/类名` 格式、splitRatio 范围、placeholder 冒号左侧必须是主页面、模式互斥、页面类名存在性、autoui 模式码），返回 error/warning/info 三级问题列表
- `diagnose()`：综合诊断 = 模块规则 + 系统内置规则 + 校验结果 + 互斥冲突（固定横屏>平行窗口覆盖、autoui 内置名单、SystemRuleSource 未加载告警），输出 `valid` 总判定
- `moduleStatus()`：模块版本、规则统计、`SystemRuleSource.loaded/rootAvailable/errorMessage`（root/加载问题排查入口）
- `crashReport()`：**应用闪退定位**——通过 su 读取 crash 缓冲区（`logcat -d -b crash -t 400`），按包名过滤崩溃事件，自动分析：ClassNotFoundException 的崩溃类与规则字段比对（直接指出哪个字段写错）、Miui 嵌入层堆栈命中提示（先删规则验证）、Resources$NotFoundException 提示（关 autoui）

**su 安全边界（强制）**：`runSu()` 实施只读命令白名单（`SU_READ_ONLY_WHITELIST`，当前仅一条 logcat 只读命令）；外部输入（包名等）永不拼入 shell 命令行，仅用于本地正则比对且做 `Regex.escape` 转义；超时 10s；失败静默返回。绝不允许执行写入/删除/权限修改/挂载等任何有副作用操作。

#### 2. 工具清单统一（两侧各 13 个，完全同名）

`search_apps / get_app_activities / get_current_rules / get_app_rule / set_app_rule / delete_app_rule / get_system_rules / validate_rule / diagnose_app / get_crash_log / launch_app / export_rules / get_module_status`

- **MCP 工具更名**（破坏性）：`list_rules→get_current_rules`、`get_rule→get_app_rule`、`set_rule→set_app_rule`、`delete_rule→delete_app_rule`、`get_system_rule→get_system_rules`、`list_activities→get_app_activities`
- MCP `set_app_rule` 改为与 AI 侧一致的**扁平 snake_case 参数**（布尔仅显式传入时覆盖），旧版 `rule` 对象整体覆盖方式保留兼容
- 新增：`validate_rule / diagnose_app / get_crash_log / launch_app / export_rules / get_module_status`

#### 3. MCP 连接性能优化

- **`tcpNoDelay = true`**：禁用 Nagle，消除「小包+延迟 ACK」数百毫秒卡顿（主要延迟源）
- **HTTP/1.1 keep-alive**：同一连接循环处理多请求，客户端（mcp-remote 等）免于每请求重新 TCP 握手；空闲 5 分钟自动断开（`soTimeout`）
- **header+body 单次写出**：分两次 write 会触发 Nagle/延迟 ACK 互等
- **应用列表缓存 60s**：`getInstalledApplications` 在应用多时耗时数百毫秒

#### 4. AI 系统提示词

新增「规则不生效时的排错流程」「应用闪退时的排查流程」两节 debug 工作流与排错速查，工作流增加第 6/7 步（保存后 validate_rule → launch_app 实测闭环）。

**修改文件：**

| 文件 | 变更 |
|------|------|
| `app/.../data/RuleDiagnostics.kt` | 新建：共享校验/诊断/闪退定位/su 白名单 |
| `app/.../ai/AiToolExecutor.kt` | 新增 6 个调试工具定义与实现 |
| `app/.../ai/AiSystemPrompt.kt` | debug 工作流 + 闪退排查流程 |
| `app/.../mcp/McpServer.kt` | 工具更名对齐 + 扁平参数 + keep-alive/tcpNoDelay/缓存 + get_crash_log |

#### 5. 客户端配置 JSON 重复工具修复（追加）

`McpServer.clientConfigJson()` 原先输出 `magic-window` + `magic-window-claude-desktop` 两个条目，整段粘贴到客户端会把全部工具**重复注册两份**。已改为只输出一个 HTTP 直连条目；Claude Desktop 等不支持 headers 的客户端在对话框提示中说明 mcp-remote 手动替换写法（`strings.xml` 的 `mcp_json_hint` 同步更新）。

#### 6. 真机 MCP 冒烟测试与追加修复（同日第四轮，v2.4.2 内）

正式版安装到 pad（`19429f2d`）后通过 MCP 客户端对全部 13 个工具实测：

| 工具 | 结果 |
|------|------|
| get_module_status | ✅ v2.0.0 / 6 规则 / system_rules_loaded=true（无 root 时走 /product/etc 兜底） |
| get_current_rules / search_apps / get_app_activities | ✅（应用列表缓存生效） |
| set_app_rule（扁平参数） | ✅ 新建+局部更新均正常 |
| validate_rule | ✅ 正确检出「forcePortraitActivity 缺包名/类名格式」error，修正后 error 消失 |
| diagnose_app | ✅ issues/conflicts/valid 结构完整 |
| delete_app_rule | ✅ 测试数据已清理 |
| get_crash_log | ✅ 无 root 时优雅降级（提示原因，不报错） |
| export_rules / launch_app | ✅ 导出 JSON 落盘 / 启动应用成功 |

**测试暴露并修复的 2 个问题：**

1. **validate 的 fullRule 误报（RuleDiagnostics）**：AppRule 默认 `fullRule = "nra:cr:rcr:nr"`（非空默认值），导致所有新建 embedding 规则都被判 error。实际 `CloudXmlCodec.embeddingAttrsOf` 根本不写 fullRule（仅 fullScreen 模式写），无真实风险——降为 warning 并更正语义。
2. **MCP 长连接协议错位（McpServer.serve）**：请求体用单次 `reader.read(buf)` 读取，TCP 分段时读不满 contentLength，残留字节被误当下一个请求解析 → 间歇性 `list tools failed` 断连。改为循环读满后再处理。

注：pad 未 root（root_available=false），get_crash_log 的 su 白名单链路需在 root 设备上验证。

---

### v2.4.3（2026-09-15）— AI 对话三项修复：图片显示 / 上下文压缩 / 模型状态同步

**Bug 修复：**

1. **AI 对话上传图片不显示**（灰块 + 「图片加载失败」）：根因是选图后直接对 `content://` URI 解码——临时授权会过期、云图/跨空间相册直接解码不可靠、部分 provider 流不稳定。修复为选取后立即 `importImage()` 转存到 `filesDir/chat_images/`（降采样 ≤1280px，一次解码同时产出本地文件与 base64），缩略图/全屏预览/上传全部改走本地文件；解码失败补 `Log.w` 日志。附件元数据（type/name/localPath，不含 base64）随消息持久化到 `ChatHistory`，历史对话图片可正常回看，加载时后台 `rehydrateImages()` 从本地文件重建 base64（IO 只读、主线程应用变更，与实时会话一致——AI 能继续「看到」历史图片）。删除/清空对话联动删除图片文件，`AiChatActivity.onCreate` 后台 `pruneOrphanImages()` 清理无引用残留文件。

2. **上下文自动压缩**（新功能）：新增 `ai/ContextCompressor.kt`，以模型高级设置中的 `maxInputTokens`（上下文窗口）为上限——token 估算（CJK≈1 token/字、其余≈4 字符/token、图片 1200 tokens/张、工具定义固定开销 2500）超过 **85%** 触发压缩：保留 system 与最近消息（至少 2 条），旧消息由 AI 总结为中文摘要（保留包名/模式/关键参数/待办），压缩后目标降至 **50%**，被压缩区间的旧图片 base64 随之释放。摘要失败降级为直接丢弃旧消息。摘要消息以 `isSummary` 标记：UI 用 AI 气泡样式展示、协议上以 user 角色发送（`[前文摘要]` 前缀），并写入历史使压缩态跨会话保持。每次发请求前检查，压缩过程经 `tvToolStatus` 提示、完成后 Toast 显示压缩前后估算量。

3. **切换模型后主页面不更新**：`MainActivity.onResume()` 补充 `renderAiModelStatus()`，从 AI 对话页切模型返回后「当前模型」即时刷新，无需重启应用。

**顺手修复：** `buildAiMessages()` 跳过「思考中」loading 占位消息（此前会作为空 assistant 消息发给 API）。

**新增/修改文件：**

| 文件 | 变更 |
|------|------|
| `app/.../ai/ContextCompressor.kt` | 新建：token 估算 + 阈值判断 + AI 摘要压缩（降级硬截断） |
| `app/.../ai/AiChatActivity.kt` | 图片转存本地 + 附件持久化/重水化 + 压缩集成（`applyCompression`）+ loading 占位过滤 |
| `app/.../ai/ChatHistory.kt` | `Message` 增加 `attachments`/`isSummary`；删除/清空联动删图片；`pruneOrphanImages()` |
| `app/.../ui/MainActivity.kt` | `onResume()` 增加 `renderAiModelStatus()` |
