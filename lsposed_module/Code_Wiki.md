# Magic Window Adapter — Code Wiki

> 模块包名：`com.github.lsposed.magicwindow`
> 版本：2.4.6（versionCode 20406）
> 许可证：Apache License 2.0

---

## 1. 项目概述

**完美横屏（Magic Window Adapter）** 是一个 LSPosed / Xposed 模块，用于将小米 HyperOS / MIUI 的三套大屏适配机制（平行窗口、固定横屏、通用全屏）以及界面自动适配（autoui）开放给用户自由配置，打破系统仅对内置名单生效的限制。

核心能力：
- 通过 Xposed Hook 注入 `system_server` 进程，把用户指定的应用「加进」系统大屏适配名单
- 提供完整的 Android UI，暴露 45+ 个规则属性供精细调节
- 内置 MCP（Model Context Protocol）调试服务器，支持 AI 模型通过局域网读写规则
- 热更新：App 保存配置后无需重启即可生效（LSPosed 远端首选项变更监听 + 缓存实例重新落盘）
- 框架兼容：**libxposed 现代 API 102**（`io.github.libxposed`），入口/作用域/元数据全部走 `META-INF/xposed/`

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
    │       ├── AndroidManifest.xml    # Activity/权限/框架服务 Provider（由 libxposed service 注入）
    │       ├── resources/META-INF/xposed/  # 现代 API 模块元数据（打包进 APK 根 META-INF/xposed/）
    │       │   ├── java_init.list     # 模块入口类（XposedEntry）
    │       │   ├── module.prop        # minApiVersion/targetApiVersion/staticScope/exceptionMode
    │       │   └── scope.list         # 作用域：system + 模块自身
    │       ├── res/                   # 布局/菜单/字符串/主题
    │       └── java/.../
    │           ├── MagicWindowApp.kt  # Application 初始化
    │           ├── ModuleStatus.kt    # 激活状态探针（读框架服务绑定状态）
    │           ├── ui/                # UI 层（4 个 Activity + 工具类）
    │           ├── data/              # 数据层（配置仓储/系统规则/导入导出/框架服务桥）
    │           └── mcp/               # MCP 调试服务器
    │
    └── library/                       # ── 库模块 ──
        ├── common/                    # 共享数据模型与常量
        │   ├── build.gradle.kts
        │   └── src/main/java/.../
        │       ├── Constants.kt       # 全局常量（类名/文件名/属性名）
        │       └── model/
        │           ├── AppRule.kt     # 单应用规则数据模型（78 字段）
        │           ├── WindowMode.kt  # 主模式枚举（OFF/FULL_SCREEN/EMBEDDING/FIXED_ORIENTATION）
        │           ├── RuleCodec.kt   # 规则集 JSON 编解码
        │           └── CloudXmlCodec.kt # 云控 XML 解析/序列化/合并
        │
        └── libhook/                   # Hook 核心库
            ├── build.gradle.kts
            └── src/main/java/.../
                ├── XposedEntry.kt     # 现代 API 入口（XposedModule）
                ├── SystemServerHooks.kt # system_server 注入编排
                ├── RuleStore.kt       # 配置读取（远端首选项 + 内存快照 + 变更监听）
                ├── Reflect.kt         # 反射辅助（替代已废弃的 XposedHelpers）
                ├── XLog.kt            # 统一日志出口
                └── steps/
                    ├── PropertyGate.kt           # 第 0 步：总开关 property 校验
                    ├── PluginClassLoaderCatcher.kt # 第一步：捕获插件 ClassLoader
                    ├── AutoUiCloudInjector.kt     # 第二步：autoui 云控注入
                    └── EmbeddedFixedCloudInjector.kt # 第二步：embedding/fixed 云控注入
```

---

## 3. 架构设计

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                     用户界面（App 进程）                    │
│  MainActivity → AppListActivity → AppDetailActivity      │
│  McpServer (局域网 HTTP)                                  │
│  XposedServiceBridge ←→ LSPosed 框架服务（XposedProvider） │
└──────────────────┬──────────────────────────────────────┘
                   │ LSPosed 远端首选项 magic_window_config
                   │ （App 侧经 XposedService 写，hook 侧经 XposedInterface 读，
                   │   框架自动推送变更，无需广播）
┌──────────────────▼──────────────────────────────────────┐
│               system_server 进程（libxposed 注入）         │
│                                                          │
│  XposedEntry (XposedModule)                              │
│    ├─ PropertyGate          (总开关兜底)                  │
│    ├─ PluginClassLoaderCatcher (捕获插件 ClassLoader)     │
│    ├─ AutoUiCloudInjector   (autoui 云控落盘 + 热重载)    │
│    ├─ EmbeddedFixedCloudInjector (embedding/fixed 注入)  │
│    │    ├─ doEmbedding()    (写入 embedding 云控 XML)    │
│    │    ├─ doFixed()        (写入 fixed 云控 XML)        │
│    │    └─ applyAppModes()  (翻转应用模式开关)            │
│    └─ RuleStore             (远端首选项 + 内存快照)       │
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
  ├─ 本地 prefs.commit()（MODE_PRIVATE，保证未激活时也能编辑）
  └─ XposedServiceBridge.writeRules() → 远端首选项 commit
       │   服务未就绪时写入失败：仅落本地，待 onBind 时由 MagicWindowApp 补推
       ▼
LSPosed 框架推送变更 → RuleStore 的 OnSharedPreferenceChangeListener
  └─ RuleStore.notifyFromApp()（切到 mw-rulestore 线程）
       ├─ RuleStore.loadNow()  → 重新读取远端首选项 → 更新内存快照
       └─ 触发 onChange 回调
            ├─ AutoUiCloudInjector.injectNow()  → 重新落盘 + 热重载
            └─ EmbeddedFixedCloudInjector.injectNow()
                 ├─ doEmbedding()  → 重新写入 embedding 云控 XML + 热重载
                 ├─ doFixed()      → 重新写入 fixed 云控 XML + 热重载
                 └─ applyAppModes() → 调用 onAppUiModeChanged 翻转模式
```

### 3.3 注入流程（开机时）

```
LSPosed 读取 META-INF/xposed/java_init.list → 实例化 XposedEntry
  │
  ├─ onModuleLoaded()        # XLog.attach(module)
  │
  └─ onSystemServerStarting()
       │
       └─ SystemServerHooks.install(this, classLoader)
            ├─ RuleStore.attach(xposed)       # 绑定远端首选项 + 首读 + 变更监听
            │
            ├─ [第 0 步] PropertyGate.apply() # 校验总开关 property，必要时兜底 hook
            │
            ├─ PluginClassLoaderCatcher.whenReady 注册回调
            │
            ├─ RuleStore.onChange { ... }     # 配置变更时热重载
            │
            └─ PluginClassLoaderCatcher.start()
                 ├─ 策略 1：直接用 system_server CL 试探
                 ├─ 策略 2：hook ImplCollector 全部方法
                 ├─ 策略 3：hook Loader 全部方法
                 └─ 拿到后立即摘钩，触发 whenReady 回调：
                      ├─ AutoUiCloudInjector.apply()       # hook loadPackage → 落盘 + 抬版本
                      └─ EmbeddedFixedCloudInjector.apply() # hook loadPackage/loadLocal...
                           ├─ 拿到 mEmbeddedRule 实例
                           ├─ 拿到 mFixOriController 实例
                           └─ 2 秒延迟后异步注入（落盘 + 翻开关）

App 侧激活判定：XposedServiceHelper.registerListener → onServiceBind 即视为已激活
（现代 API 不再 hook 模块自身，见 ModuleStatus / XposedServiceBridge）
```

---

## 4. 模块职责详解

### 4.1 `library:common` — 共享数据模型

无外部依赖，被 app 和 libhook 两个模块共同依赖。

| 文件 | 职责 |
|------|------|
| `Constants.kt` | 全局常量：包名、类名、方法名、文件名、系统 property 键等，全部来自逆向实证 |
| `AppRule.kt` | 核心数据模型，78 字段覆盖 embedding（25+属性）、fixedOrientation（15+属性）、autoui（6属性）以及比例档（3属性）。含 `toJson()` / `fromJson()` 序列化 |
| `WindowMode.kt` | 四种互斥模式的枚举：`OFF`、`FULL_SCREEN`、`EMBEDDING`、`FIXED_ORIENTATION`，key 与系统 JSON 对齐 |
| `RuleCodec.kt` | 规则集的 JSON 数组编解码，App 与 Hook 两侧共用 |
| `CloudXmlCodec.kt` | 云控 XML 的解析（`parse`）、合并（`mergeWith`）、序列化（`serialize`），以及 `AppRule` → 属性表的转换（`embeddingAttrsOf` / `fixedAttrsOf` / `fullScreenAttrsOf`） |

### 4.2 `library:libhook` — Hook 核心库

依赖 `library:common`，被 app 模块通过 `implementation` 引入。所有注入逻辑运行在 `system_server` 进程。

| 文件 | 职责 |
|------|------|
| `XposedEntry.kt` | 现代 API 入口（`io.github.libxposed.api.XposedModule`）。作用域仅 `system`（system_server，见 `scope.list`）；`onSystemServerStarting` 先校验 `PROP_CAP_SYSTEM`；未声明 `autoHotReload`，因此不实现热重载回调 |
| `SystemServerHooks.kt` | 注入编排器：绑定远端首选项 → 第 0 步 → 捕获 ClassLoader → autoui 注入 → embedding/fixed 注入，并注册配置变更热重载 |
| `RuleStore.kt` | 配置读取层：`XposedInterface.getRemotePreferences` + 变更监听驱动热更新，内存快照供热路径零 IO 读取 |
| `Reflect.kt` | 反射辅助（`field` / `call` 等），替代现代 API 已移除的 `XposedHelpers`；`method` / `field` / `constructor` 结果按（类, 名称, 参数数）缓存，避免重复扫描继承链 |
| `XLog.kt` | 统一日志：写入 libxposed `XposedInterface.log`（LSPosed 模块日志），仅 info / error 两级。**tag 必须传 `null`**，自定义 tag 会被框架过滤、管理器看不到输出 |

**steps/ 子包（注入步骤）：**

| 文件 | 步骤 | 职责 |
|------|------|------|
| `PropertyGate.kt` | 第 0 步 | 校验三个 `SystemProperty` 总开关（AE/AutoUI/Characteristics），为 false 时 hook 兜底（`ro.` 属性无法 setprop） |
| `PluginClassLoaderCatcher.kt` | 第一步 | 三级降级策略捕获 HyperOS Stub 插件的 ClassLoader。拿到后**立即摘钩**，不影响系统热路径。只 hook `declaredMethods`（API 102 下构造方法 `thisObject` 为 null，挂钩拿不到实例且纯亏开销）；同一 ClassLoader 的探测结果缓存，避免重复 `Class.forName` 与异常构造 |
| `AutoUiCloudInjector.kt` | 第二步 | hook `MiuiParsingAutoUI.loadPackage`，在 before 中落盘云控文件 + 抬高版本号，after 中触发热重载。支持指纹去重避免重复写文件；`loadPackage` 按方法名取重载（优先带 `MiuiSystemAutoUIRule` 参数者），不硬编码参数个数；`PackageRule` 类与云控基表（按文件长度 + 修改时间）均缓存，避免每次调用都 `Class.forName` + 读盘 + 解析 XML |
| `EmbeddedFixedCloudInjector.kt` | 第二步 | hook `MiuiParsingEmbeddedRule.loadPackage` / `MiuiParsingFixedOrientationRule.loadLocalFixOrientationRuleXML`，拿到宿主实例后缓存并**立即摘除各自的捕获钩子**；异步执行「读全量 → 按包覆盖 → 写回 → 热重载 → 翻应用模式」。两者 `loadPackage` / `loadLocalFixOrientationRuleXML` 的参数个数随 ROM 版本变化（旧版 1 参、Android 17 为无参），必须按方法名取参数最少的重载，不可硬编码参数个数 |

### 4.3 `app` — 用户界面

| 文件 | 职责 |
|------|------|
| `MagicWindowApp.kt` | Application：初始化 `ConfigRepository` 与 `XposedServiceBridge`（载入上次确认的激活状态）、注册 `XposedServiceBridge.onBind` 补推规则（覆盖「保存时服务未就绪」与框架重启后的失同步）；不再预热 `SystemRuleSource`，进程被后台拉起时零 su/IO 开销（v2.3.0 起移除 DynamicColors，固定使用品牌色板） |
| `ModuleStatus.kt` | 激活状态探针：`isModuleActive()` 读 `XposedServiceBridge.isActive`（框架服务是否已绑定）。现代 API 下模块 App 不再被 hook，不能再用 hook 回显；绑定是异步投递的，首帧判定见 `MainActivity.renderStatus()` |
| **ui/** | |
| `MainActivity.kt` (322行) | 主界面：模块状态显示、应用列表入口、MCP 服务器开关/配置（端口/令牌/客户端 JSON）、隐藏桌面图标、配置导入导出 |
| `AppListActivity.kt` (211行) | 应用列表：搜索/筛选（全部/已设置/被系统禁用/已安装/系统应用）、长按进入多选批量操作（套用模式/清除设置）。被系统禁用的应用只在「被系统禁用」分组出现，且不参与多选/批量修改 |
| `AppDetailActivity.kt` (979行) | 应用详情：完整的 45+ 配置项表单，支持简单/高级切换、模式互斥联动、页面抓取填充、内置规则继承、单规则导出导入。被系统禁用的应用整页只读（模式/开关/保存/恢复默认/导入全部禁用，不再提供强制修改入口） |
| `AppAdapter.kt` (145行) | RecyclerView 适配器：异步图标加载（LIFO 队列 + 缓存）、内置徽标/模式标签、多选模式 |
| `UiKit.kt` | 动态表单工具：`section`（分组卡片）、`chipBox/chip`（可勾选标签）、`textRow`（文本输入）、`dropdownRow`（下拉选择）、`note`（说明文字）、`sliderRow`（滑块） |
| `ModeUi.kt` | 模式文案与配色映射 |
| `BuiltinUi.kt` | 「系统已内置」徽标文案生成 |
| **data/** | |
| `ConfigRepository.kt` | 配置仓储：本地 SharedPreferences（`MODE_PRIVATE`）读写规则 CRUD；保存成功后经 `pushToHook()` 写入远端首选项；远端未就绪时仅落本地，待服务绑定后补推 |
| `XposedServiceBridge.kt` | 框架服务桥：`XposedServiceHelper` 监听绑定状态（激活探针）+ 通过 `XposedService.getRemotePreferences` 写配置；`onStateChanged` 通知界面重绘激活状态；`lastKnownActive` 持久化上次确认结果（`null`=从未确认），供冷启动首帧区分「检测中/未生效」；`writeRules` 内容一致时不重写，避免触发 hook 侧重载 |
| `AppItem.kt` | 应用列表项模型 + `AppLoader`（按包名/标签排序加载已安装应用） |
| `SystemRuleSource.kt` (343行) | 系统规则读取器：通过 root 读取 `/data/system/` 云控文件 + `/product/etc/` 本地名单，按 `dataVersion` 比较选择生效版本；提供 `applyDefaults()` 用系统内置值填充 AppRule |
| `MainPageResolver.kt` | 主页面（左栏）判定：为 `splitPairRule` / `activityRule` 提供候选类名。判定顺序 系统内置规则反查（`splitPairRule` 首个左栏）> 类名关键词（Main/Home/Index > List/Search/Tab）> launcher 兜底。排除词按驼峰词元整词匹配（避免 `readassistant` 误命中 `Ad`），启动页词元 `Splash/Launch` 降权而非硬排除。统计依据：真机 3850 个左栏中 `Main` ×1331 / `Home` ×580。`roleOf()` 供工具返回页面角色 |
| `RulePresets.kt` | 平行窗口最小配置策略（AI/MCP 写规则专用）：`entryActivity()` 取启动器入口 Activity；`minimizeEmbedding()` 把 `activityRule` / `splitPairRule` 收敛为「入口 Activity」与「入口 Activity:*」，并清空 `placeholder` / `transitionRules` / `splitRatio`。UI 手动编辑不经过此收敛 |
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
    fun attach(xposed: XposedInterface)     // 绑定远端首选项 + 首读 + 注册变更监听（入口调用一次）
    fun loadNow()                           // 阻塞读一次配置（仅内部/首读调用）
    fun notifyFromApp()                     // 切到 mw-rulestore 线程：重读 + 通知回调
    fun onChange(listener: () -> Unit)      // 注册配置变更回调
    fun activeRules(): List<AppRule>        // 读取内存快照（热路径安全）
    fun autoUiRules(): List<AppRule>        // 过滤出 autoUiEnable=true 的规则
}
```

性能设计：读侧全在内存（`@Volatile snapshot`），由 LSPosed 远端首选项的变更推送（`registerOnSharedPreferenceChangeListener`，仅监听 `KEY_RULES`）驱动热更新，热路径零 IO。LSPosed 的 `LSPosedRemotePreferences` 会在 App 侧写入后按 key 回调监听器，因此不再需要广播、`MODE_WORLD_READABLE` 或 `getSentFromPackage` 校验。**监听器必须用字段强引用持有**：`SharedPreferences` 内部以 `WeakHashMap` 存监听器，不留引用会被 GC，热更新随之静默失效。工作线程 `mw-rulestore` 首次收到变更才懒启动，不再常驻空线程。

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

**关键设计**：拿到 ClassLoader 后立即 `unhook()` 摘除所有捕获钩子，避免拖慢系统热路径。只 hook `declaredMethods`：API 102 规定构造方法的 `thisObject` 为 null、返回值被框架忽略，挂钩只会白付开销。`probe` 结果按 ClassLoader 缓存（`WeakHashMap`），避免捕获前每次拦截都重复 `Class.forName` 并构造 `ClassNotFoundException`。

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

捕获钩子只用于首次拿实例：`mEmbeddedRule` / `mFixOriController` 一旦缓存，立即通过各自的 `HookHandle` 摘钩（embedding 与 fixed 各自独立），此后读规则方法不再有拦截开销。

### 5.6 `McpServer` — MCP 调试服务器

位置：`app/src/.../mcp/McpServer.kt`

提供 13 个 MCP 工具（与 `AiToolExecutor` 同名同义，但两侧实现各自独立、输出结构存在差异）：

| 工具 | 说明 |
|------|------|
| `search_apps` | 按关键词搜索已安装应用（含命中的系统内置规则名单，60s 缓存；带 `systemDisabled` 标记） |
| `get_app_activities` | 列出某个应用的全部 Activity（返回 `launcher` / `entry_activity` / `recommended_main` 与 `system_disabled`） |
| `get_current_rules` | 列出本模块已配置的全部应用规则（完整 toJson） |
| `get_app_rule` | 读取某个应用的规则 |
| `get_system_rules` | 读取系统内置规则（三个名单的原始属性） |
| `set_app_rule` | 创建或更新规则（增量更新，保存后立即热生效）。被系统禁用的应用直接拒绝；平行窗口经 `RulePresets` 收敛为入口 Activity 最小配置，返回 `minimal_embedding` |
| `delete_app_rule` | 删除规则 |
| `launch_app` | 启动应用 |
| `export_rules` | 导出规则为本地 JSON 文件（v2 格式） |
| `validate_rule` | 校验规则格式 |
| `diagnose_app` | 综合诊断某个应用的适配状态 |
| `get_crash_log` | 读取崩溃日志（需 root） |
| `get_module_status` | 模块整体状态 |

接入方式：`POST http://<IP>:8765/mcp`，JSON-RPC 2.0 协议。

### 5.7 平行窗口最小配置策略（`RulePresets`）

AI 助手与 MCP 的 `set_app_rule` 共用同一套规则生成策略：平行窗口规则只保留启动器入口 Activity 一对。

| 项 | 规则 |
|------|------|
| `entryActivity(context, pkg)` | 取 `PackageManager.getLaunchIntentForPackage()` 的入口 Activity；无启动入口返回 null |
| `minimizeEmbedding(context, rule, entryOverride)` | 入口 Activity 已是 `activityRule` 且是 `splitPairRule` 左栏时原样返回 false；否则改写为 `activityRule=入口`、`splitPairRule=入口:*`，并清空 `placeholder` / `transitionRules` / `splitRatio`，返回 true |
| 触发范围 | 仅 `mode=embedding`；UI 手动编辑、导入的既有规则不经过收敛 |
| 校验联动 | `RuleDiagnostics.validate` 对 embedding 规则检查「入口 Activity 是否同时出现在 activityRule 与 splitPairRule 左栏」，缺失时给 warning |

**为什么最小化**：真机实测 AI 填多页面 / 用 `recommended_main` 代替入口时，规则能落盘但系统匹配不到用户实际打开的页面，平行窗口不触发。
`get_app_activities` 因此显式返回 `launcher` / `entry_activity`（入口）与 `recommended_main`（仅兜底）。

---

## 6. 依赖关系

### 6.1 模块依赖

```
app ──→ library:common
    ──→ library:libhook ──→ library:common
    ──→ AndroidX + Material
```

（`xposed-api (compileOnly)` 仅 `library:libhook` 声明。）

### 6.2 外部依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| AGP | 8.7.0 | Android 构建插件 |
| Kotlin | 2.0.0 | 语言 |
| Xposed API | 82 | Xposed Hook 框架接口（compileOnly，仅 libhook） |
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
| 模块规则（本地副本） | SharedPreferences `magic_window_config`（键 `app_rules`），`MODE_PRIVATE` | App 侧真相源，未激活时也能编辑 |
| 模块规则（框架通道） | LSPosed 远端首选项 `magic_window_config`（键 `app_rules`） | App 侧经 `XposedService.getRemotePreferences` 写，system_server 侧经 `XposedInterface.getRemotePreferences` 读，框架负责变更推送 |
| 页面抓取记录 | SharedPreferences `magic_window_capture`（键 `records`） | 格式 `包名|类名` |
| MCP 设置 | SharedPreferences `mcp_settings`（端口 + 令牌） | App 私有 |
| 云控规则文件 | `/data/system/cloudFeature_*.xml` | system_server 运行时写入，模块不会修改系统分区 |

---

## 10. 性能优化

- **热路径零 IO**：`RuleStore` 读侧全在内存快照，热路径不碰磁盘
- **Hook 即摘**：`PluginClassLoaderCatcher` 拿到 ClassLoader 后、`EmbeddedFixedCloudInjector` 拿到宿主实例后，都立即摘除对应捕获钩子；只 hook 方法，不 hook 构造方法
- **指纹去重**：`AutoUiCloudInjector` 和 `EmbeddedFixedCloudInjector` 都维护内容指纹，内容没变不重写文件
- **解析结果缓存**：`Reflect` 按（类, 名称, 参数数）缓存 Method/Field/Constructor；`AutoUiCloudInjector` 缓存 `PackageRule` 类与云控基表（文件长度 + 修改时间不变则不读盘、不解析 XML）；`PluginClassLoaderCatcher.probe` 按 ClassLoader 缓存结果
- **异步注入**：`EmbeddedFixedCloudInjector` 用后台线程延迟 2 秒注入，不阻塞系统启动
- **配置写入异步化**：`ConfigRepository` 的 `commit`（本地 + 跨进程）统一在单线程 `mw-config-io` 上按序执行，不阻塞 UI
- **LIFO 图标加载**：`AppAdapter` 用 LIFO 队列让当前可见项优先加载图标
- **批量 su 调用**：`SystemRuleSource` 一次 `su -c` 读取所有云控文件，避免反复弹授权

---

## 11. 构建与运行

### 11.1 环境准备

- JDK 17+
- Android SDK（compileSdk 35）
- Gradle（通过 wrapper 自动下载）

### 11.2 本地构建命令

```bash
cd lsposed_module

# Debug 构建
.\gradlew assembleDebug

# Release 构建
.\gradlew assembleRelease

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 11.3 GitHub Actions 自动构建（v2.4.4 起）

项目已配置 GitHub Actions 自动构建流程，支持两种触发方式：

#### 打标签自动触发

```bash
# 创建并推送版本标签
git tag v2.4.4
git push origin v2.4.4
```

推送标签后会自动触发：
- 构建 Debug 和 Release APK
- 创建 Release 草稿
- 自动附加 APK 文件到 Release

#### 手动触发

访问：https://github.com/achzehn/magic_window/actions/workflows/build-and-release.yml

1. 点击 "Run workflow"
2. 输入版本号（如 `2.4.4`）
3. 选择发布类型：
   - **Draft**：创建草稿，需要手动发布
   - **Release**：直接发布

#### 签名密钥配置

首次使用前需要在 GitHub Secrets 配置签名密钥：

1. 生成 keystore（如果还没有）：
   ```bash
   keytool -genkey -v \
     -keystore lsposed_module/magicwindow.keystore \
     -alias magicwindow \
     -keyalg RSA \
     -keysize 2048 \
     -validity 10000 \
     -storepass magicwindow123 \
     -keypass magicwindow123 \
     -dname "CN=MagicWindow, OU=Development, O=LSPosed, L=Shanghai, ST=Shanghai, C=CN"
   ```

2. 转换为 Base64：
   ```powershell
   # Windows PowerShell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("lsposed_module\magicwindow.keystore"))
   ```

3. 添加到 GitHub Secrets：
   - 访问：https://github.com/achzehn/magic_window/settings/secrets/actions
   - Name: `KEYSTORE_BASE64`
   - Value: 粘贴 Base64 字符串

详细配置参考：
- [.github/KEYSTORE_SETUP.md](../.github/KEYSTORE_SETUP.md)
- [README.md 构建说明](../README.md#github-actions-自动构建)

**构建产物：**
- Debug APK: `MagicWindow-Debug-v{version}.apk`
- Release APK: `MagicWindow-Release-v{version}.apk`

**工作流文件：** [.github/workflows/build-and-release.yml](../.github/workflows/build-and-release.yml)

### 11.4 安装与激活

1. 安装 APK
2. 打开 LSPosed Manager → 模块 → 完美横屏，启用模块
3. 作用域**只勾「系统框架」（Android Framework）**
4. 重启手机
5. 打开模块界面，顶部应显示「模块已生效」

---

## 12. libxposed 模块声明（现代 API 102）

所有框架元数据都在 `app/src/main/resources/META-INF/xposed/`，打包后位于 APK 根 `META-INF/xposed/`：

| 文件 | 内容 | 说明 |
|------|------|------|
| `java_init.list` | `com.github.lsposed.magicwindow.hook.XposedEntry` | 模块入口类，LSPosed 据此实例化 |
| `module.prop` | `minApiVersion=102` / `targetApiVersion=102` / `staticScope=true` / `exceptionMode=protective` | 最低与目标 API、静态作用域、异常保护模式；**不声明 `autoHotReload`**（system_server 热重载会让钩子与缓存实例错位） |
| `scope.list` | `system` | 仅 `system`（即 system_server；现代 API 下其包名为 `system`，不是 `android`）。模块自身不再列入作用域：激活状态由框架服务绑定判定，列进去只会让框架额外向模块 App 进程注入一次 |

要点：
- **不再有** `assets/xposed_init`、Manifest 里的 `xposedmodule` / `xposedminversion` / `xposedscope` 等 meta-data；模块名取 `android:label`，描述取 `android:description`
- `MainActivity` 保留 `de.robv.android.xposed.category.MODULE_SETTINGS` category —— LSPosed 管理器据此定位模块设置页，与现代 API 不冲突；桌面图标仍由 `LauncherAlias` 提供
- 激活状态不再靠 hook 回显：`libxposed:service` 的 `XposedProvider` 会绑定框架服务，`XposedServiceHelper.onServiceBind` 即「已激活」；绑定是异步投递的，冷启动首帧用持久化的上次结果判定（见 14 章 2026-11-12）
- 配置通道为远端首选项，App 侧可写、hook 侧只读，框架负责变更推送
- 模块本身不勾选任何被适配的应用——所有注入都发生在 `system_server` 里

---

## 13. 已知限制与风险

- 依赖 HyperOS / MIUI 私有实现，非小米系统不适用
- 换机型 / 大版本升级后可能因内部类名变化而失效
- 「直接写进系统规则表」依赖内部结构，异常时优先关掉
- MCP 服务器应用退到后台后可能被系统暂停
- `libxposed api/service` 102 声明 `minCompileSdk=37`，当前 AGP 8.13 仅测到 36.1，构建会打印兼容提示（已在 `gradle.properties` 用 `android.suppressUnsupportedCompileSdk=37.0` 抑制）
- 仅支持实现了 libxposed API 102 的框架（新版 LSPosed / Vector 等），**不再兼容只有传统 Xposed API 的旧框架**

---

## 14. 近期修改记录

### v2.4.6（2026-11-12）— 界面重设计 + API 102 合规与性能修复

**版本：** v2.4.6（versionCode 20406），本批次自 v2.4.5 起的改动汇总：

1. **界面重设计**：清爽明亮设计令牌（浅底 + 白卡片 + 细描边 + 大留白）+ Material 3 转场/按压/列表动效，触摸热区满足 48dp。
2. **API 102 合规与确定性修复**：日志 tag 传 `null`（自定义 tag 会被过滤）、静态作用域只保留 `system`、`loadPackage` / `loadLocalFixOrientationRuleXML` 按方法名取重载不硬编码参数个数、不 hook 构造方法、`SharedPreferences` 监听器字段强引用、热路径缓存解析结果。
3. **平行窗口最小配置**：新增 `RulePresets`，AI/MCP 在 embedding 模式下把 `activityRule` / `splitPairRule` 收敛为入口 Activity 一对，避免能落盘但不触发分屏；`RuleDiagnostics` 补充对应 warning。
4. **系统禁用应用只读**：`set_app_rule` 直接拒绝、列表只在「被系统禁用」分组出现、详情页整页只读；`search_apps` / `get_app_activities` 增加 `system_disabled` 标记。
5. **激活状态首帧判定**：三态渲染（检测中 → 已生效 / 未生效），消除冷启动红色失败闪动；宽限期仅前台计时，后台不跑 su、不读盘。
6. **移除模型 Top K**：`top_k` 仅部分端点支持，官方 OpenAI 端点严格校验会 400，字段与请求体一并移除。

### 平行窗口最小化与系统禁用应用只读（2026-11-12）

**问题**：AI 推荐的平行窗口配置（多页面 activityRule + recommended_main 作 splitPairRule 左栏）保存成功却不触发分屏；系统禁用的应用仍会出现在其他筛选分组并可被改写。

1. 新增 `data/RulePresets.kt`：`entryActivity()` + `minimizeEmbedding()`，AI/MCP 的 `set_app_rule` 在 embedding 模式下自动把 `activityRule` / `splitPairRule` 收敛为启动器入口 Activity 一对，并清空 `placeholder` / `transitionRules` / `splitRatio`；返回体带 `minimal_embedding`。
2. `AiToolExecutor` / `McpServer`：`set_app_rule` 对 `SystemRuleSource.isFixedDisabled(pkg)` 直接拒绝；`search_apps` / `get_app_activities` 增加 `system_disabled`（MCP 侧 `systemDisabled`），后者增加 `entry_activity`。
3. `AiSystemPrompt`：补充模块机制与优先级（固定横屏 > 平行窗口 > 通用全屏）、ROM 预置文件只读、系统禁用应用不可修改、平行窗口最小配置与「避免过度修改」原则。
4. `AppListActivity`：被系统禁用应用只在「被系统禁用」分组出现（ALL/CONFIGURED/USER/SYSTEM 全部排除），不参与长按多选与批量套用/清除。
5. `AppDetailActivity`：系统禁用应用的「强制修改」入口下线，整页只读（保存/恢复默认/导入一并禁用）。
6. `RuleDiagnostics`：embedding 规则缺少「入口 Activity 作左栏」时给出 warning，提示改回最小配置。

### 激活状态首帧判定与后台零开销（2026-11-12）

**问题：** 框架服务绑定由 LSPosed 异步投递，晚于 `MainActivity` 首帧；`renderStatus()` 首帧读到 `isActive=false`，于是每次冷启动都会把红色「模块还没生效…」闪一下再跳成绿色，观感像「失败瞬间成功」。

**改法：**

1. `XposedServiceBridge` 新增 `lastKnownActive`（`null` = 从未确认，持久化在 `Constants.PREFS_NAME` 的 `module_last_active`），`onServiceBind` / `onServiceDied` 时更新；`confirmInactive()` 供界面宽限期到点后落盘「未生效」。
2. `MainActivity.renderStatus()` 改为三态：已绑定 → 绿色「模块已生效」；未绑定且历史状态非「未生效」→ 灰色「正在检测模块状态…」；否则 → 红色失败文案。宽限期 `STATUS_GRACE_MS = 2500` 只在 `onResume` 排程、`onPause` 取消，页面不可见时计时器销毁，后台不保留任何回调。
3. `writeRules()` 先比对远端当前值，内容一致直接返回，不再每次进程启动都触发 hook 侧重载与云控文件重算。
4. `SystemRuleSource.preload()` 从 `MagicWindowApp.onCreate` 移到 `MainActivity.onCreate`：只有真正打开界面才拉 su 读云控，进程被框架/系统在后台拉起时不产生 su 与读盘开销。

**效果：** 正常状态进入只有「检测中 → 已生效」的过渡，不再出现红色失败闪动；无界面时模块 App 不起线程、不跑 su、不占用计时器。

### 移除模型 Top K 设置（2026-11-11）

**动机：** `top_k` 仅部分端点支持，官方 OpenAI 端点严格校验时会直接 400；该参数在高级设置中可配置性低但风险高，整体移除。

1. `dialog_ai_model.xml` 删除 Top K 输入框（`etTopK`），`strings.xml` 删除 `ai_model_top_k`。
2. `MainActivity.showModelConfigDialog()` 删除 `etTopK` 的查找、回填与保存逻辑。
3. `ModelManager.ModelConfig` 删除 `topK` 字段及 JSON 读写；旧配置中的 `topK` 键读取时被忽略，无需迁移。
4. `AiClient` 请求体不再发送 `top_k`。

### 性能与 API 102 合规性修复（2026-11-11）

**日志与入口：**

1. `XposedEntry.onModuleLoaded` 的 `log` 第二参改为 `null`（自定义 tag 会被 libxposed 过滤，管理器看不到输出）。
2. `onSystemServerStarting` 增加 `PROP_CAP_SYSTEM` 能力校验；删除无用的 `onHotReloading` 覆写（`module.prop` 未声明 `autoHotReload`，回调不会触发）。
3. `scope.list` 移除模块自身包名，静态作用域只保留 `system`。

**确定性缺陷：**

4. `AutoUiCloudInjector` 不再硬编码 `loadPackage` 参数个数（OS4.x 已改为无参，原写法会让 autoui 钩子被外层 `runCatching` 静默吞掉、永远不安装），改为按方法名取重载并优先带 `MiuiSystemAutoUIRule` 参数者；`loadLocalFixOrientationRuleXML` 同步改为按名取重载。
5. `PluginClassLoaderCatcher` 不再 hook 构造方法（API 102 下构造方法 `thisObject` 为 null、返回值被忽略，取不到实例，纯开销）。
6. `RuleStore` 变更监听器改为字段强引用持有（`SharedPreferences` 内部是 `WeakHashMap`，不留引用会被 GC，热更新静默失效）。

**性能：**

7. `PluginClassLoaderCatcher.probe` 按 ClassLoader 缓存结果，避免捕获前重复 `Class.forName` 与异常构造。
8. `AutoUiCloudInjector` 缓存 `PackageRule` 类与云控基表解析结果（文件长度 + 修改时间不变则跳过读盘与 XML 解析）。
9. `Reflect` 的 `method` / `field` / `constructor` 按（类, 名称, 参数数）缓存，避免每次沿继承链扫描。
10. `EmbeddedFixedCloudInjector` 与 Catcher 一致：拿到宿主实例后立即摘除各自的捕获钩子（embedding / fixed 独立）。
11. `RuleStore` 工作线程改为首次变更时懒启动；`ConfigRepository` 的持久化与推送统一到后台单线程 `mw-config-io`，避免主线程 `commit` 阻塞。

### v2.4.5 迁移到 libxposed 现代 API 102（2026-10-22）

**动机：** LSPosed 主推 libxposed 现代 API，传统 `de.robv.android.xposed` 仅由兼容层维持。本次把入口、Hook 模型、配置通道、激活检测整体迁移，参考 `D:\lsp\hongguo` 的实现方式。

**入口与构建：**

1. 新增 `app/src/main/resources/META-INF/xposed/{java_init.list,module.prop,scope.list}`，删除 `assets/xposed_init` 与 Manifest 中的 `xposedmodule` / `xposedminversion` / `xposedscope` meta-data。
2. `XposedEntry` 从 `IXposedHookLoadPackage` 改为 `XposedModule`：`onModuleLoaded` → `onSystemServerStarting` 安装钩子；`onHotReloading` 返回 false（不支持热重载）。
3. 依赖改为 `io.github.libxposed:api`（`compileOnly`，由框架注入）+ `io.github.libxposed:service`（`implementation`，随 APK 打包）。两者声明 `minCompileSdk=37`，故 compileSdk 提到 37、AGP 8.13.0、Gradle 8.14.5，并在 `gradle.properties` 抑制 compileSdk 版本提示。
4. `packaging.resources.merges` 增加 `META-INF/xposed/*`，保证元数据原样进 APK；`proguard-rules.pro` / `consumer-rules.pro` 改为 keep `XposedModule` 子类 + hook 包，并 `-adaptresourcefilecontents META-INF/xposed/java_init.list`。

**Hook 模型：**

5. 删除 `de.robv.android.xposed.*` 全部用法：`XposedHelpers` → 新增 `Reflect.kt`（`field` / `call`）；`XposedBridge.log` → `XposedInterface.log`（`XLog` 同步改）；`findAndHookMethod` → `xposed.hook(Executable).intercept { chain -> ... }`，摘钩统一用 `HookHandle.unhook()`。
6. 移除 `ActivityThread.getSystemContext` 依赖（现代 API 无此入口，且已无消费方）。

**配置与激活：**

7. 配置通道：`XSharedPreferences` + `MODE_WORLD_READABLE` + `ACTION_CONFIG_CHANGED` 广播 + `getSentFromPackage` 校验 → LSPosed 远端首选项（App 侧 `XposedService.getRemotePreferences` 写，hook 侧 `XposedInterface.getRemotePreferences` 读），删除 `ConfigSyncReceiver.kt`。热更新改由 `RuleStore.attach` 注册的 `OnSharedPreferenceChangeListener` 驱动。
8. 新增 `app/.../data/XposedServiceBridge.kt`：`XposedServiceHelper` 监听框架服务绑定，同时承担「激活探针」与「配置写入通道」；服务绑定/断开时通过 `onStateChanged` 通知 `MainActivity` 重绘状态。
9. `ModuleStatus.isModuleActive()` 改读 `XposedServiceBridge.isActive`（现代 API 下模块 App 不再被 hook，旧回显方式失效）。
10. `ConfigRepository`：本地 `MODE_PRIVATE` 为真相源，保存成功后 `pushToHook()` 写远端；远端未就绪只落本地，`MagicWindowApp` 注册 `XposedServiceBridge.onBind` 在绑定后补推，覆盖框架重启后的失同步。

**清理：**

11. 删除死常量 `Constants.SYSTEM_PACKAGE` / `ACTION_CONFIG_CHANGED` / `CLASS_MODULE_STATUS` / `M_IS_MODULE_ACTIVE`。
12. `res/values/arrays.xml` 的 `xposed_scope` 展示文案与 `scope.list` 对齐（`system`，不再是 `android`）。
13. `MainActivity` 的 `MODULE_SETTINGS` category **保留**：LSPosed 管理器「打开」按钮依赖它定位模块设置页。

**验证：** `:app:assembleDebug` BUILD SUCCESSFUL；APK 内 `META-INF/xposed/` 三件套内容正确，Manifest 已由 `libxposed:service` 注入 `XposedProvider`（authorities `com.github.lsposed.magicwindow.XposedService`）。真机运行（注入、热更新、激活显示）需回归。

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

1. **多模型管理**（`ai/ModelManager.kt`）：替代旧的单模型 `AiSettings`，支持多模型增删改查与全局切换。`ModelConfig` 含 apiBase/modelId/apiKey、token 上限、temperature/topP、toolRounds、`enabled` 启停字段；SharedPreferences（`ai_models`）持久化。`getCurrent()` 只返回已启用模型，停用/删除当前模型时自动回退。

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
   - `SystemRuleSource.rootAvailable` 预读成功（预读在 `MainActivity.onCreate` 触发）也作为放行依据。

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

---

### v2.4.4（2026-09-15）— GitHub Actions 自动构建流程

**新增 CI/CD 能力：**

1. **GitHub Actions 工作流**（`.github/workflows/build-and-release.yml`）：
   - 支持打标签自动触发：推送 `v*` 格式标签自动构建并发布
   - 支持手动触发：在 Actions 页面选择工作流手动运行
   - 自动构建 Debug 和 Release 两个版本
   - 自动创建 Release 草稿或直接发布
   - 自动附加 APK 文件到 Release

2. **签名密钥管理**（`.github/KEYSTORE_SETUP.md`）：
   - 提供完整的 keystore 生成和配置指南
   - 通过 GitHub Secrets 安全存储 Base64 编码的 keystore
   - 工作流自动解码并使用签名密钥

3. **项目文档更新**：
   - README.md 添加构建状态徽章和自动构建说明
   - Code_Wiki.md 补充 CI/CD 配置和使用指南
   - 提供详细的签名密钥配置步骤

**新增文件：**

| 文件 | 说明 |
|------|------|
| `.github/workflows/build-and-release.yml` | GitHub Actions 工作流：自动构建 + 发布 |
| `.github/KEYSTORE_SETUP.md` | 签名密钥配置指南 |

**修改文件：**

| 文件 | 变更 |
|------|------|
| `README.md` | 添加构建状态徽章、自动构建说明、签名配置步骤 |
| `Code_Wiki.md` | 新增 11.3 节：GitHub Actions 自动构建完整文档 |

**仓库地址：** https://github.com/achzehn/magic_window

**工作流链接：** https://github.com/achzehn/magic_window/actions/workflows/build-and-release.yml

---

### 代码简化批次（2026-10-08）— 死代码清理 + 单一路径收敛

**版本：** v2.4.5（versionCode 20405，首次正式发布本批次改动）

**删除死代码（全仓零消费者，均经全局搜索验证）：**

| 删除项 | 位置 | 说明 |
|------|------|------|
| `RuleStore` FileObserver + 轮询兜底路径 | `libhook/.../RuleStore.kt` | system_server 收不到 LSPosed prefs 目录事件（SELinux），热更新统一走 App 广播；删除 `startWatching`/`startFileObserver`/`startFallbackPolling`/`resolvePrefFile` 及 HandlerThread |
| `AiClient.chat()` | `app/.../ai/AiClient.kt` | 被 `chatStream` 取代的非流式+工具循环重复实现，零调用 |
| `UiKit.switchRow` + `row_switch.xml` | `app/.../ui/UiKit.kt` | 孤儿开关行实现 + 专用布局，零调用 |
| `AiToolExecutor.toolDef` | `app/.../ai/AiToolExecutor.kt` | 从 McpServer 复制残留的 schema 构造器（AI 侧用 `makeTool`） |
| `ModelManager.currentDisplayName` | `app/.../ai/ModelManager.kt` | 零调用，UI 侧已内联同款表达式 |
| `quickAction` / 空体 `showWelcome` | `app/.../ai/AiChatActivity.kt` | 布局已移除对应控件 |
| `ActivityLabelCache.get/put/clearPackage` | `app/.../data/ActivityLabelCache.kt` | 仅 `getAllForPackage`/`putAll` 被使用 |
| `Constants.LOG_TAG` / `M_CREATE_CLOUD_AUTO_UI_RULE` / `PERMISSION_CONFIG_CHANGED` / `SYSTEM_PACKAGE` | `common/.../Constants.kt` | 死常量（PERMISSION 随广播通道整体废弃；`SYSTEM_PACKAGE` 随经典 API 入口移除而失效） |
| 构建配置：jitpack 空仓库 / app 与 common 的 `compileOnly(xposed-api)` | 构建文件 | 无消费者的声明（`suppressUnsupportedCompileSdk=37` 现已启用，不再是死配置） |
| `AppDetailActivity` 死局部 `contextHint` | `app/.../ui/AppDetailActivity.kt:877` | 赋值后从未读取（消费点在 `buildContextAwarePrompt`） |

**合并与收敛：**

- `flattenPkgClass` 双份实现收敛为 `RuleDiagnostics.flattenPkgClass`（internal），`AppDetailActivity` 改为调用方
- `ConfigExporter` 导出 JSON 委托 `McpServer.buildRulesJson`（同一 v2 格式，统一 Locale），删除自有 `toJson` 与 `ExportData`
- `ConfigRepository.reload()` 内联进 `init`
- `MainActivity` 消除同函数内重复求值（`ModelManager.getCurrent` ×2、`McpServer.isRunning` ×2）
- `AiSystemPrompt` 修正 3 处失实描述：移除工具 schema 不支持的 `skipSelfAdaptive`/`foCompatChange` 参数指引；「需重启手机」改为与热重载一致的表述
- 注释修正：`Constants` / `ConfigRepository` 中「签名权限校验」改为实际的 `Intent.getSentFromPackage` 校验描述

**行为变化：** 无（除 AI 提示词文案修正外，全部为零行为差异的删除/等价合并）。

**已知遗留（本轮未处理，留档）：**

- AI 侧与 MCP 侧 13 个工具业务实现各自独立，输出结构已出现差异（`get_current_rules` 裁剪字段 vs 完整 toJson 等），需要时再做统一业务层
- `AppRule` 78 字段在声明 / `toJson` / `fromJson` 三处平行维护
- `AppDetailActivity` 两个页面选择器（`showPagePicker` / `showPlaceholderPairPicker`）存在约 60 行可抽取的公共段
- `SystemRuleSource.parseContent` 与 `CloudXmlCodec.parse` 为两套同构 XML 解析（app 侧额外解析 dataVersion）

---

### 功能缺陷修复批次（2026-10-09）— 全仓审查举证 + 修复

四域并行审查（libhook+common / data+mcp / ai / ui）产出约 60 条带代码证据的候选，复核后按下述清单修复，全部通过 `assembleDebug assembleRelease`（含 lintVital）闸门。

**MCP 协议（`McpServer.kt`）：**

| 缺陷 | 修复 |
|------|------|
| 请求体按字符读、Content-Length 按字节——含中文的请求读不满而挂死至 300s 超时 | header 逐字节读到空行，body 按字节数精确读满再 UTF-8 解码；另加 4MB content-Length 上限 |
| OPTIONS 预检在鉴权之后——启用令牌后浏览器类客户端（mcp-inspector）被 401 拦截 | OPTIONS 分支前移到鉴权之前 |
| `stop()` 只关监听套接字——keep-alive 旧连接在「已停止」后仍可调用工具 | 维护活跃连接集合并逐一断开；连接循环检查 `running` |
| JSON-RPC 通知（无 id）回 `id:null` 响应体 | 无 id 请求回空串 → HTTP 202 空体 |
| 工具在主线程执行（get_crash_log 的 su 最长 10s → ANR）；latch 超时 10s | 迁移到专用后台单线程 executor 串行执行，latch 15s；`dispatchOnMain` 更名 `dispatchTool` |
| 布尔参数 `optBoolean(key, true)` 把 `"0"`/`"yes"` 等静默置 true | `strictBool` 严格解析，非法值返回参数错误；两侧（MCP/AI）同修 |
| MCP `set_app_rule` 无法重新启用停用的规则（AI 侧反而无条件强制启用） | 两侧统一新增可选 `enabled` 参数：仅显式传入时覆盖，新规则默认启用；AI 侧深拷贝后修改 |
| `get_system_rules` 未标注加载状态，未加载时空表被误读为「系统无内置规则」 | 返回体新增 `loaded` 字段 |

**诊断与导出（`RuleDiagnostics.kt` / `ConfigExporter.kt`）：**

- `runSu` 先 `waitFor` 再读 stdout——日志超过 64KB 管道缓冲即挂死超时 → 改为 stdout/stderr 并发排空（与 `SystemRuleSource` 同款）
- `get_crash_log` 按 threadtime 行头分组——每行都带时间戳导致事件被切碎 → 命令改 `logcat -v long`，头行正则匹配 `[ 日期…`；白名单同步更新
- `flattenPkgClass` 对「包名/全类名」二次拼包名（`com.a/com.a.ui.Main` → `com.a.com.a.ui.Main`）→ 三分支还原，修 validate 误报与页面选择器勾选丢失
- `checkPlainList` 同时按 `,`/`;` 拆分导致分号误用漏报 → 只按逗号拆 + 显式 warning；`splitRatio` 数值校验门控到 embedding 模式；diagnose 补 mode=off 提示
- 导出 `openOutputStream` 返回 null 时仍报成功 → 改为返回 false

**详情页状态（`AppDetailActivity.kt`）：**

- 表单直改仓储共享 `AppRule` 实例——未保存的编辑立即泄漏到列表/MCP/导出并被其他保存连带持久化 → 进入页面深拷贝
- `onSystemRulesReady` 用会话级 `userSaved` 判断——上次会话保存的规则被系统内置值覆盖 → 新增 `isNewRule` 守卫
- 程序化 `modeGroup.check()` 复用用户监听器 → 导入值/恢复默认值被 `applyModeDefaults` 样例覆盖 → 新增 `suppressModeSideEffects`，恢复默认对 builtin 规则跳过样例套用
- 页面选择器回显/写回丢后缀（`页面:模式码`、`左栏:右栏`、`包名/类名`）→ 多候选匹配 + 旧条目原样写回、仅新增项用默认格式
- 导出用旧 `rule.mode` → 导出前同步 `pendingMode`；「恢复默认」迟到回调加 dirty/userSaved 快照守卫
- AI 推荐 `fieldContext` 键不匹配（`relunchRule` 死分支、`sizeCompatRule` 大小写、缺 `embRelaunchRule`/`foRelaunchRule`/`foForcePortraitActivity`）→ 键与 txt() 实际 key 对齐
- `disableCameraPreview` 无编辑入口（导入值不可见不可改）→ embedding 分区补开关

**AI 对话（`AiChatActivity` / `ChatHistory` / `ContextCompressor` / `AiClient` / `ModelManager`）：**

- 生成期间可切换/新建/清空会话（下标越界或跨会话污染）→ 统一生成中守卫并提示
- 「清空历史」不重置当前会话 → 下一条消息复活已删对话与图片路径；「清空对话」不清会话引用 → 同类复活 → 均重置 `currentConversation`
- 压缩按条数双删历史，失败/停止消息只在 UI 侧 → 条数不齐时错删较新的历史消息 → `applyCompression` 改为按 UI 列表整体重建历史
- `ContextCompressor` 的 `runCatching` 吞 `CancellationException` → 压缩期间停止仍执行删除 → CE 重抛 + 压缩后 `ensureActive()`；降级摘要占位三处（请求/UI/历史）统一为 `FALLBACK_SUMMARY` 常量
- 重试取「整个列表最后一条 user」→ 改取错误消息之前的提问；`finally` 补 `updateToolStatus(null)`（停止/异常后状态栏残留）
- 模型切换 `toolbar.menu.clear()` 把「新对话/历史/清空」三项清掉 → 重灌功能菜单，菜单点击统一走 `onOptionsItemSelected`（模型切换同时刷新底部按钮）
- `ChatHistory`/`ModelManager` JSON 零容错解析 → 整体损坏按空处理、单条损坏跳过、`optString` 兜底
- 文本附件内容不持久化（重启后模型看不到文件内容）→ `AttachmentMeta` 增加 `content` 持久化/恢复；附件读取移 IO 线程并按 60k 字符截断（原主线程 `readText` 大文件会卡死/OOM）
- `rehydrateImages` 在 IO 线程遍历主线程可变列表、按下标回写 → 主线程取快照 + 写回前引用校验
- 首条消息失败留下零消息幽灵会话 → 会话延迟到首次成功保存时创建
- tool name 追加拼接（网关重复携带全名时拼成 `search_appssearch_apps`）→ 首段优先；非 SSE 响应丢弃 `tool_calls` → 补组装

**工具执行器与数据层（`AiToolExecutor` / `ConfigRepository` / `AppAdapter` / `AppListActivity`）：**

- 提示词/工具结果为 camelCase、schema 为 snake_case → AI 传 camelCase 被静默丢弃但返回 success → `execute` 入口键名归一化
- `ConfigRepository` 裸 `LinkedHashMap` 被 AI(IO)/MCP(后台)/UI 三线程并发读写（CME 风险）→ 全部结构性访问加锁、`allRules()` 返回快照；`commit()` 失败不再发热更新广播
- `AppAdapter` 图标线程池 core 线程不超时 → 经匿名队列永久持有 Activity（每次进列表泄漏）→ `allowCoreThreadTimeOut(30s)`；徽标缓存不再缓存「规则表未加载」的 null
- `AppListActivity` 网格列数只在 onCreate 计算 → 折叠/分屏跨 sw 阈值不重算 → `onConfigurationChanged` 重算

**libhook（`RuleStore` / `RuleCodec` / `CloudXmlCodec` / `EmbeddedFixedCloudInjector` / `AutoUiCloudInjector`）：**

- `RuleCodec.decodeRules` 吞异常返回空/半截表 → 配置损坏时把「读取失败」伪装成「配置被清空」并覆盖 hook 侧正常快照 → 解析失败抛出，`RuleStore.loadNow` 保留旧快照并打错误日志，App 侧 init 兜底空表
- `CloudXmlCodec.parse` 吞异常 → 云控文件损坏时回落本地名单再写回，会丢云控里的系统条目 → 解析失败返回 null，`readBaseTable` 中止本轮注入
- `RuleStore.notifyFromApp` 在 system_server 主线程做 prefs IO + 全量注入 → 迁移到专用 HandlerThread；`loadNow` 加锁
- 旧广播通道 `ConfigSyncReceiver` 60s 后放弃注册导致热更新永久失效 → 已整体废弃：改用 LSPosed 远端首选项的变更监听，不存在注册超时
- `ConfigRepository.commit()` 失败不再发热更新广播 → 现为「本地 commit 失败则直接跳过推送 hook 侧」；远端未就绪时由 `XposedServiceBridge.onBind` 在绑定后补推
- `injectAsync` 的 check-then-act 竞态与触发丢失 → `injectLock` + `injectPending` 补跑；`injectNow` 加 `@Synchronized`
- 规则改为「不处理(OFF)」后 `embedded_setting` 残留旧模式值、应用继续按旧模式跑 → `applyAppModes` 对 OFF 翻回 0
- autoui 规则全部关闭后旧云控文件不清理（同开机周期内禁用无效）→ 本开机注入过时删除云控文件并热重载；删除恒假的版本门槛死代码（`version <= LOCAL` 永不成立）
- `embeddingAttrsOf` 的 splitRatio 无数值过滤（非数字直接进系统文件）→ 与 scaleMode/adaptCutout 同款过滤
- `AppRule.fromJson` 三个比例档可同时为 true → 按 applyAppModes 同一优先级（4:3>16:9>全屏）归一
- `AutoUiCloudInjector` 直接全量覆盖 autoui 云控文件，`updateAutoUICloudConfigFile` 内部无合并（反编译证实为 `new FileOutputStream` 截断写入）→ 注入前读回云控文件已有条目、重建 `PackageRule` 作合并底，再叠加模块条目；base 键参与指纹，云控被外部改写时不再被跳过
- Android 17（OS4.x）`MiuiParsingEmbeddedRule.loadPackage` 由旧版 1 参改为**无参**，`Reflect.method(..., "loadPackage", 1)` 抛 `NoSuchMethodException` 被 `runCatching` 吞掉 → `embeddedRule` 恒为 null，表现为「平行窗口/全屏规则保存后不落盘、应用模式也不翻转」；fixed 侧 `loadLocalFixOrientationRuleXML()` 本身无参不受影响，故只有 embedding 分支失效 → 改为 `Reflect.findMethods` 按方法名取参数最少的重载
- `XLog` 曾用自定义 tag，被 libxposed 日志框架过滤，LSPosed 管理器里看不到任何模块日志（排查时误判为「hook 没运行」）→ tag 一律传 `null`

**真机取证（2026-10-09，实体机 `93fb88b`，adb + MT MCP 只读）：**

- fixed 侧确认为**合并写回**：`/data/system/cloudFeature_fixed_orientation_list.xml` 保留 31 条带 `source` 字段的系统条目，模块的 `tv.danmaku.bili`、`com.xiaomi.scanner` 追加在尾部且无 `source`——`mergeWith` 生效
- autoui 侧确认为**未合并的全量覆盖**：`/data/system/cloudFeature_autoui_list.xml` 实测仅 179B / 1 条；固件 `/product/etc/autoui_list.xml` 为 5070B、根标签 `packageRules`、含 bili/知乎/快手多条带 `<activity>/<view>` 子树的规则，均未出现在云控文件中
- `MiuiParsingAutoUI.loadPackage`（反编译 `/system_ext/framework/miui-services.autoui.jar`）两条路径**互斥 return**：`mLastCloudConfigVersion > 0 && >= mLocalDataVersion` 时只读云控且直接返回，固件被整体跳过；云控路径 `parsePackageXml` 只解析 6 个平铺属性，**无法表达固件那种 `<activity>/<view>` 子树**（该能力在 `PackageRule2`/`rule2` 段），故固件条目在云控生效期间不可恢复
- `/data/system/` 无 `cloudFeature_embedded_rules_list.xml`（当前无 embedding 规则）；反射目标 `mEmbeddedRule`/`mFixOriController`/`createSetting`/`onAppUiModeChanged`/`updatePackageConfigFromCloud`/`updateFixedOrientationFromCloud`/`isProjection`/`mIsProjection` 在真机 jar 中全部存在
- 运行时日志通道：`XLog` 走 libxposed `XposedInterface.log`（LSPosed 模块日志页面可见），`logcat` 抓不到

**行为变化：** 布尔参数非法值由「静默置 true」改为报错；AI `set_app_rule` 不再强制启用已有规则；MCP 通知改回 202；OFF 模式会显式翻转系统 mode=0；其余为错误路径修复，正常路径行为不变。

**验证：** `gradlew assembleDebug assembleRelease` BUILD SUCCESSFUL（含 lintVitalRelease）；改动面 21 文件 +653/-257；被改名/删除符号全仓无残留引用。运行时行为（MCP 抓包、真机注入、热更新链路）需真机回归——见下方未修复项。

**未修复（留档，需真机验证或产品决策）：**

- 删除/停用（enabled=false）规则的云控条目清理与 mode 复位：fixed 侧已证伪（合并写回保留带 `source` 的系统条目，模块条目可经 `removals` 精确移除）；「恢复系统原始值」语义与 autoui 侧条目清除仍需产品决策（OFF→0 已修，mode=0 压制效果待真机确认）
- ~~`AutoUiCloudInjector` 全量覆盖~~ → **已验证并修复**：`updateAutoUICloudConfigFile` 为截断写入、内部无合并（反编译实证），已补 base 合并（读回云控已有条目重建 `PackageRule` 作底）。**遗留**：固件 `/product/etc/autoui_list.xml` 里带 `<activity>/<view>` 子树的规则在云控生效期间仍不可恢复——云控扁平属性格式本就无法表达该结构（系统自身云控推送同理），非模块可修复项
- ~~指纹只覆盖模块增量~~ → **已修复**：base 键纳入指纹，云控被外部改写时模块重写而非跳过
- `SystemRuleSource` 加载失败（首启拒绝 su）后本进程内不重试——需要 UI 重试入口的产品决策
- 详情页/AI 对话无 `onSaveInstanceState`，manifest 未含 `uiMode`——进程回收或夜间模式切换丢草稿（涉及全部 Activity 的状态保存设计）
- ~~请求体恒发 `top_k`（严格校验的官方 OpenAI 端点可能 400）~~ → **已修复（2026-11-11）**：删除 Top K 设置，请求体不再发送 `top_k`
- AI 侧与 MCP 侧工具输出结构差异、`AppRule` 三处平行维护、选择器公共段（沿袭上一批遗留）
- ~~`ActivityLabelCache`/`AiSuggestCache` 无淘汰机制（慢性增长）~~ → **已修复**：三者统一继承 `PrefixedLruCache`，2000/3000/2000 条上限 + 时间戳近似 LRU 淘汰（见下方「LLM token 优化」）
- 聊天缩略图 bind 同步解码（性能而非功能）

---

## LLM token 优化与 UI 美化（2026-10-09）

**改动动机：** AI 参与的功能存在两处结构性 token 浪费——① `showPlaceholderPairPicker`（占位页配对）的角色判定**每次打开都全量重算**，结果只存活于对话框生命周期，关闭即丢；② 三个 AI 缓存无容量上限且各写一份前缀扫描。同时 UI 层存在系统图标混用、硬编码文案等问题。

**数据层：缓存复用与淘汰**

- **新增 `data/PrefixedLruCache.kt`**：抽象基类，收敛「包名前缀 + 条目」结构的 SharedPreferences 缓存。key = `包名|维度|名称`，value 为短字符串；`ts:` 前缀记录访问时间。
  - 淘汰：写入后条目数超 `maxEntries` 时，按访问时间从旧到新删除直到回落上限。
  - 读取：整批刷新一次访问时间（O(n) 而非每 key 一次写入）。
- **三类缓存统一为其子类**（对外 API 仅 `putAll` → `putForPackage` 改名，避免与基类同名遮蔽）：
  | 类 | prefs | 上限 | 维度 |
  |---|---|---|---|
  | `ActivityLabelCache` | `activity_label_cache` | 2000 | 包名 |
  | `AiSuggestCache` | `ai_suggest_cache` | 3000 | 包名 + fieldContext |
  | **`PageRoleCache`（新增）** | `page_role_cache` | 2000 | 包名 + `placeholderRole` |
- **新增 `data/PageRoleCache.kt`**：持久化 AI 对 Activity 的角色判定（主页面/占位页面/其他）。配对选择器打开时预加载 → 仅对 `roleMap[name].isNullOrEmpty()` 的页面发起增量请求 → 全部命中时直接提示「已从缓存加载 N 个页面角色」并跳过请求 → 请求结束落盘。**同一应用第二次打开该选择器为 0 次 LLM 请求。**

**提示词精简（`ai/AiSystemPrompt.kt`）**

`SYSTEM_PROMPT` 整段压缩约 30%（164 → 113 行，12 → 10 章节）：删除「效果：/适合：」冗长描述、合并重复约束、`场景速查` 由多行小标题压成单行列表、`排错速查` 去引号、`安全规则` 5 条合 3 条。系统提示词每轮固定计入 token 成本，此压缩直接降低每轮基线开销。

**配对选择器增量改造（`ui/AppDetailActivity.kt`）**

- 「🤖 AI 推荐配对」由 `names = items.map { it.name }`（全量）改为 `items.filter { roleMap[it.name].isNullOrEmpty() }`（增量）。
- 新增类级方法 `buildPairRoleMessages(pkg, batch)` 承载角色标注 prompt（原内联 15 行）。
- 新增两个 `showPlaceholderPairPicker` 内局部函数（`PairItem` 为函数内 data class，helper 必须同作用域）：`parsePairRoleReply(reply)` 解析并回填 `roleMap`/`zhDescMap`；`applyPairSelection(): Pair<String?, String?>` 汇总角色结果写回两段选择（缓存命中与增量请求两路共用）。

**UI 美化（仅复用 `themes.xml` 既有样式 + `colors.xml` 既有语义色，零新增样式）**

- `ai/AiChatActivity.kt` + `item_ai_message.xml`：工具状态改用外层容器（品牌色 2dp 竖线 + `tvToolCall`）；失败消息气泡改用 `conflict_error` 红色描边，与正常回复一眼可分；加载态改「进度圈 + `bg_chip` 胶囊」。
- `activity_ai_chat.xml`：输入区调整为「附件 / 输入框 / 发送 FAB」主行聚焦，`btnModelSwitch` 下沉为独立一行（高度 32→28dp，图标 `ic_chevron_down` → `ic_tune`）；`tvToolStatus` 改居中 `wrap_content`。
- `ui/MainActivity.kt`：`showModelConfigDialog` 高级折叠改为箭头 `rotation` 动画（-90° ↔ 0°，150ms）；`showModelManagerDialog` 行高 56→62dp、当前模型高亮（`card_bg_high` + `brand_primary` 1dp 描边）、停用模型 `alpha=0.55f`、「当前」标记改胶囊（`ok_green` / `ok_green_bg`）、图标换自有 vector 40→38dp、删除确认文案改字符串资源。
- **新增 vector**：`ic_edit.xml`、`ic_delete.xml`（替换 `android.R.drawable.ic_menu_edit`/`ic_menu_delete`）。
- **新增字符串**（`strings.xml`）：`ai_model_delete`、`ai_model_delete_confirm`、`ai_model_input_tokens`、`ai_model_output_tokens`、`ai_model_temperature`、`ai_model_top_p`、`ai_model_top_k`（`ai_model_top_k` 已于 2026-11-11 随 Top K 设置移除）。

**变更文件：**

| 文件 | 变更 |
|------|------|
| `app/.../data/PrefixedLruCache.kt` | **新建**：前缀缓存基类 + 近似 LRU 淘汰 |
| `app/.../data/PageRoleCache.kt` | **新建**：页面角色缓存 |
| `app/.../data/ActivityLabelCache.kt` | 改为基类子类，补上限与淘汰 |
| `app/.../data/AiSuggestCache.kt` | 改为基类子类，补上限与淘汰 |
| `app/.../ai/AiSystemPrompt.kt` | `SYSTEM_PROMPT` 压缩约 30% |
| `app/.../ui/AppDetailActivity.kt` | 配对选择器增量请求 + `buildPairRoleMessages` + 2 局部 helper + 缓存落盘 |
| `app/.../ui/MainActivity.kt` | 模型管理/配置对话框美化 |
| `app/.../ai/AiChatActivity.kt` | 失败气泡描边 + 工具状态容器 + `ContextCompat` 导入 |
| `res/layout/item_ai_message.xml`、`activity_ai_chat.xml`、`dialog_ai_model.xml` | 美化 |
| `res/drawable/ic_edit.xml`、`ic_delete.xml`、`res/values/strings.xml` | 新增 |

**行为变化：** 无功能语义变化，仅缓存命中时不再发起等价 LLM 请求、UI 呈现调整。

**验证：** `gradlew assembleDebug assembleRelease` BUILD SUCCESSFUL（含 `lintVitalAnalyzeRelease`）。首次构建暴露 8 处编译错误并已修复：① 三个缓存子类在构造参数位置前向引用 `MAX_ENTRIES` → 常量提为文件级 `private const val`；② 子类 `putAll`/原名与基类同名遮蔽 → 改 `putForPackage`（6 处调用点同步）；③ `AiChatActivity` 缺 `androidx.core.content.ContextCompat` 导入。

**遗留：** 缓存无失效策略（应用更新后 Activity 集合变化时旧条目仍在，直到被 LRU 淘汰）；聊天缩略图 bind 同步解码（性能项，未处理）。

---

### 本地模型接入支持（2026-10-22）— 允许明文 HTTP 与免密钥

**背景：** 本地/局域网模型服务（Ollama、LM Studio、vLLM 等）通常只提供 `http://` 明文接口且不校验密钥；此前 `targetSdk=35` 默认禁明文、UI 强制密钥非空，导致本地模型无法接入。

**修改：**

| 文件 | 变更 |
|------|------|
| `app/src/main/AndroidManifest.xml` | `<application>` 增加 `android:usesCleartextTraffic="true"`，放行 `http://` 请求（否则抛 `Cleartext HTTP traffic not permitted`） |
| `app/.../ai/AiClient.kt` | `openPost` 仅在密钥非空时设置 `Authorization` 头；`testConnection` 移除「API 密钥为空」前置校验（仅校验地址与模型 ID） |
| `app/.../ui/MainActivity.kt` | `showModelConfigDialog` 的测试与保存门禁均只校验 API 地址 + 模型 ID，密钥可留空 |
| `res/values/strings.xml` | `ai_model_api_base_hint` 补充本地地址示例（`http://192.168.1.5:11434/v1`）；`ai_model_key_hint` 标注「本地模型可留空」；`ai_model_test_empty` 文案同步去掉密钥 |

**行为变化：** 云端模型仍需填密钥才能正常鉴权（请求头逻辑未变）；本地服务可零密钥接入。安全性上明文流量全局放开，属于本地调试场景的取舍。

**验证：** `gradlew :app:compileDebugKotlin --offline` 通过。

---

### 界面重设计：清爽明亮 + Material 3 动效体系（2026-11-11）

**目标：** 全量刷新模块 UI 视觉语言，统一「清爽明亮」的设计语言（浅色底 + 白卡片 + 细描边 + 大留白），补齐 150–300ms 的页面/点击/状态过渡，并满足 Material 3 触摸热区要求。

**设计令牌（`res/values`）：**

| 令牌 | 取值 |
|------|------|
| 背景 / 卡片 / 柔和面 | `app_bg #F4F7FB` / `card_bg #FFFFFF` / `surface_soft #F0F5FA` |
| 描边 / 分割线 | `stroke #E2E9F2` / `divider #E6ECF4` |
| 文字层级 | `text_primary #111820` / `text_secondary #5A6A7C` / `text_tertiary #8D99A8` |
| 品牌色 | 主 `#2F6BFF`，辅助青绿 `#0E9C87`（暗色模式自动降饱和） |
| 圆角 | 卡片 24dp、首页状态卡 28dp、按钮/列表 16dp、Chip 12dp |
| 间距 | 4/8/12/16/20/24 刻度；页面左右 20dp；卡片间距 14dp |
| 触摸热区 | `min_touch 48dp`；列表行 `row_height 72dp`；Chip 40dp 高度 + `ensureMinTouchTargetSize` 扩到 48dp |

**动效资源（`res/anim`）：** `activity_open_enter/exit`、`activity_close_enter/exit`（220/200ms，淡入位移 + 0.98 缩放）、`dialog_enter/exit`（180/150ms 缩放淡入）、`item_enter + layout_app_list`（列表逐项 200ms、6% 错峰）。Activity 转场挂在 `Theme.MagicWindow` 的 `android:windowAnimationStyle`，弹窗转场挂在 `ThemeOverlay.MagicWindow.Dialog`。

**交互反馈：** 新增 `ui/Motion.kt`（`pressScale` 150ms 按压 0.97 缩放、`staggerIn` 首屏 40ms 错峰入场、`playListEnterAnimation` 列表入场）。已接入首页卡片（`MainActivity.setupUi`）、应用列表（加载完成后一次性播放）、应用行（`AppAdapter.onCreateViewHolder`）、详情页表单（`buildForm` 后错峰）。

**布局改造：** `activity_main` 改为「渐变状态卡 + 分组标题 + 留白卡片」结构；`item_app` 改为 72dp 圆角卡片行并新增右箭头（多选模式自动隐藏）；`activity_app_list` 搜索框改 20dp 圆角浅填充、Chip 过滤条、批量面板卡片化；`activity_app_detail` 模式选择四等分、底部操作栏圆角上浮 + 48dp 按钮；`activity_ai_chat` 输入区卡片化、发送/附件 48dp；`item_ai_message` 气泡 20/22dp 圆角、错误气泡描边、重试按钮 48dp；`card_section` 增加品牌色竖条标题。平板适配沿用 `values-sw600dp / sw840dp`（列表 2/3 列、页面留白 40/128dp）。

**验证：** `gradlew :app:assembleDebug :app:lintDebug` BUILD SUCCESSFUL；Lint 无 `TouchTargetSizeCheck` / `ClickableViewAccessibility` 新增问题，`SmallSp`、`RtlSymmetry`、`UseCompatTextViewDrawableXml` 已清零。**限制：** 本机无连接设备与可用 AVD，运行时多机型截图验证未执行，需在真机 / 模拟器上补做。
