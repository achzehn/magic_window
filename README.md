# 完美横屏（Magic Window Adapter）

[![Build and Release](https://github.com/achzehn/magic_window/actions/workflows/build-and-release.yml/badge.svg)](https://github.com/achzehn/magic_window/actions/workflows/build-and-release.yml)
[![Release](https://img.shields.io/github/v/release/achzehn/magic_window)](https://github.com/achzehn/magic_window/releases)
[![Downloads](https://img.shields.io/github/downloads/achzehn/magic_window/total)](https://github.com/achzehn/magic_window/releases)
[![License](https://img.shields.io/github/license/achzehn/magic_window)](https://github.com/achzehn/magic_window/blob/main/LICENSE)

LSPosed 模块，用于将小米 HyperOS / MIUI 的三套大屏适配机制（平行窗口、固定横屏、通用全屏）以及界面自动适配（autoui）开放给用户自由配置，打破系统仅对内置名单生效的限制。

## 功能特性

- ✅ 将应用加入系统大屏适配名单（平行窗口/固定横屏/通用全屏）
- ✅ 支持 45+ 个规则属性精细调节
- ✅ 内置 AI 助手，支持多模型管理和智能配置
- ✅ 内置 MCP 调试服务器，支持 AI 模型通过局域网读写规则
- ✅ 热更新：配置修改后无需重启即可生效
- ✅ 支持 Magisk / KernelSU / APatch Root 权限
- ✅ HyperOS 4 风格界面，支持手机/平板自适应
- ✅ 基于 LSPosed API 102 开发

## 核心能力

通过 Xposed Hook 注入 `system_server` 进程，把用户指定的应用「加进」系统大屏适配名单：

1. **平行窗口（Embedding）** - 支持分屏、配对、占位等 25+ 属性
2. **固定横屏（Fixed Orientation）** - 支持全屏拉伸、信箱模式等 15+ 属性
3. **界面自动适配（AutoUI）** - 自动适配横竖屏切换，6+ 属性
4. **通用全屏** - 整屏显示方式配置

## 技术实现

### 核心 Hook 流程

```
XposedEntry.handleLoadPackage("android")
  │
  ├─ SystemServerHooks.install(classLoader)
  │    ├─ RuleStore.loadNow()               # 同步读配置
  │    ├─ RuleStore.startWatching()         # 启动配置监听
  │    ├─ ConfigSyncReceiver.register()     # 注册热更新广播
  │    │
  │    ├─ [第 0 步] PropertyGate.apply()    # 校验总开关 property
  │    │
  │    ├─ PluginClassLoaderCatcher.start()  # [第一步] 捕获插件 ClassLoader
  │    │    ├─ 策略 1：直接用 system_server CL 试探
  │    │    ├─ 策略 2：hook ImplCollector 全部方法
  │    │    └─ 策略 3：hook Loader 全部方法
  │    │
  │    ├─ whenReady 回调:
  │    │    ├─ AutoUiCloudInjector.apply()   # 落盘 autoui 云控
  │    │    └─ EmbeddedFixedCloudInjector.apply() # 落盘 embedding/fixed 云控
  │    │
  │    └─ RuleStore.onChange { ... }        # 配置变更热重载
  │
  └─ ModuleStatus.isModuleActive() → hook 返回 true
```

### 云控注入原理

1. 系统读取云控文件时「存在即全量生效、内置文件被忽略」
2. 模块读取系统当前全量规则（云控优先，没有读本地名单）
3. 按包名覆盖用户配置的应用规则
4. 写回 `/data/system/cloudFeature_*.xml`
5. 调用系统方法触发热重载
6. 通过 `onAppUiModeChanged` 翻转每个应用的当前模式

### 系统规则文件

| 类型 | 云控目录 | 本地目录 |
|------|----------|----------|
| embedding | `/data/system/cloudFeature_embedded_rules_list*.xml` | `/product/etc/embedded_rules_list*.xml` |
| fixed | `/data/system/cloudFeature_fixed_orientation_list*.xml` | `/product/etc/fixed_orientation_list*.xml` |
| autoui | `/data/system/cloudFeature_autoui_list.xml` | `/product/etc/autoui_list.xml` |

## 项目结构

```
lsposed_module/
├── app/                           # 应用模块
│   ├── src/main/
│   │   ├── AndroidManifest.xml    # Activity/LSPosed 元数据/权限
│   │   ├── assets/xposed_init     # Xposed 入口类声明
│   │   ├── res/                   # 布局/菜单/字符串/主题
│   │   └── java/.../
│   │       ├── MagicWindowApp.kt  # Application 初始化
│   │       ├── ModuleStatus.kt    # 激活状态探针
│   │       ├── ui/                # UI 层（4 个 Activity + 工具类）
│   │       ├── data/              # 数据层（配置仓储/系统规则/导入导出）
│   │       ├── ai/                # AI 助手（多模型管理/对话/工具执行）
│   │       └── mcp/               # MCP 调试服务器
│   │
│   └── build.gradle.kts
│
├── library/
│   ├── common/                    # 通用数据模型
│   │   ├── src/main/java/.../
│   │   │   ├── Constants.kt       # 全局常量
│   │   │   ├── model/
│   │   │   │   ├── AppRule.kt     # 核心数据模型（120+ 字段）
│   │   │   │   ├── WindowMode.kt  # 模式枚举
│   │   │   │   ├── RuleCodec.kt   # JSON 编解码
│   │   │   │   └── CloudXmlCodec.kt # 云控 XML 解析
│   │   │
│   │
│   └── libhook/                   # Hook 核心库
│       ├── src/main/java/.../
│       │   ├── XposedEntry.kt     # Xposed 入口
│       │   ├── SystemServerHooks.kt # 注入编排
│       │   ├── RuleStore.kt       # 配置读取
│       │   ├── XLog.kt            # 统一日志
│       │   └── steps/
│       │       ├── PropertyGate.kt           # 总开关校验
│       │       ├── PluginClassLoaderCatcher.kt # ClassLoader 捕获
│       │       ├── AutoUiCloudInjector.kt    # autoui 注入
│       │       ├── EmbeddedFixedCloudInjector.kt # embedding/fixed 注入
│       │       └── ConfigSyncReceiver.kt     # 热更新广播
│       │
│       └── build.gradle.kts
│
├── gradle/
│   ├── libs.versions.toml        # 依赖管理
│   └── wrapper/
│       └── gradle-wrapper.properties
│
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 环境要求

- JDK 17+
- Android SDK 35（compileSdk）
- Gradle 8.10
- LSPosed 环境（API 102+）
- Root 权限（Magisk / KernelSU / APatch，用于读取系统云控文件）

## 构建说明

### 本地构建

```bash
cd lsposed_module

# 清理并构建 Debug 版本
.\gradlew clean assembleDebug

# 构建 Release 版本
.\gradlew clean assembleRelease

# 检查依赖
.\gradlew dependencies
```

### GitHub Actions 自动构建

项目已配置 GitHub Actions 自动构建，支持两种方式：

#### 1. 打标签触发（推荐）

```bash
# 创建并推送标签
git tag v2.4.0
git push origin v2.4.0
```

推送标签后会自动触发构建并发布 Release。

#### 2. 手动触发

进入 Actions 标签页，选择 "Build and Release" 工作流，点击 "Run workflow"：
- 输入版本号（如 `2.4.0`）
- 选择发布类型：
  - **Draft**：创建草稿，需要手动发布
  - **Release**：直接发布

#### 配置签名密钥

首次使用前需要配置签名密钥：

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
     -dname "CN=MagicWindow, OU=Development, O=LSPosed, L=Unknown, ST=Unknown, C=CN"
   ```

2. 转换为 Base64：
   ```bash
   # Windows PowerShell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("lsposed_module\magicwindow.keystore"))
   ```

3. 添加到 GitHub Secrets：
   - 进入仓库：https://github.com/achzehn/magic_window/settings/secrets/actions
   - 添加 Secret：
     - Name: `KEYSTORE_BASE64`
     - Value: 粘贴 Base64 字符串

详细配置请参考 [.github/KEYSTORE_SETUP.md](.github/KEYSTORE_SETUP.md)

### 3. 安装

```bash
# 使用 ADB 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 安装使用

### 1. 激活模块

1. 打开 LSPosed Manager
2. 找到 "完美横屏（Magic Window Adapter）"
3. 启用模块
4. **作用域只勾「系统框架」（Android Framework）**
5. 重启手机

### 2. 配置应用

1. 打开完美横屏应用
2. 点击「应用列表」
3. 选择要适配的应用
4. 选择模式（平行窗口/固定横屏/通用全屏/关闭）
5. 精细调节 45+ 个规则属性
6. 点击保存按钮生效

### 3. AI 助手（可选）

1. 在主界面点击「AI 助手」
2. 配置 AI 模型（支持多模型管理，兼容 OpenAI 协议）
3. 使用 AI 推荐配置、页面抓取补全等功能

### 4. MCP 调试服务器（可选）

1. 在主界面开启 MCP 服务器
2. 配置端口和令牌
3. 通过局域网 JSON-RPC 2.0 协议访问
4. 支持 8 个工具：规则读写、系统规则读取、应用搜索等

## 日志查看

模块日志可以通过以下方式查看：

```bash
# 查看完整日志
adb logcat | findstr "MagicWindow"

# 查看 Xposed 日志
adb logcat | findstr "Xposed"
```

## 性能优化

- **热路径零 IO**：`RuleStore` 读侧全在内存快照，热路径不碰磁盘
- **Hook 即摘**：`PluginClassLoaderCatcher` 拿到 ClassLoader 后立即摘除所有捕获钩子
- **指纹去重**：云控注入维护内容指纹，内容没变不重写文件
- **异步注入**：`EmbeddedFixedCloudInjector` 用后台线程延迟 2 秒注入，不阻塞系统启动
- **批量 su 调用**：一次 `su -c` 读取所有云控文件，避免反复弹授权

## 已知限制

1. 依赖 HyperOS / MIUI 私有实现，非小米系统不适用
2. 换机型/大版本升级后可能因内部类名变化而失效
3. 部分应用可能需要重启才能生效
4. MCP 服务器应用退到后台后可能被系统暂停

## 版本历史

### v2.3.0（2026-09-13）— HyperOS 4 风格界面改版 + 手机/平板适配

- 纯 View/XML 实现 HyperOS 4 设计系统（无 Compose）
- 手机/平板自适应布局（1/2/3 列网格）
- 修复 MCP 客户端配置 JSON 闪退问题

### v2.2.0（2026-09-13）— AI 助手与 Root 适配

- 新增多模型 AI 助手（Kimi 风格 UI）
- Root 权限检测重构（Magisk/KernelSU/APatch）
- 页面抓取 AI 推荐与中文说明补全
- placeholder 专用配对选择器

### v2.1.0（2026-09-13）

- 修复模式切换未保存问题
- 引入模板系统
- 补充 page picker 交互
- 全局震动反馈

## 许可证

Apache License 2.0

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [libxposed](https://github.com/LSPosed/libxposed)
- [HyperOS 分析](../..)

## 相关资源

- [完整使用文档](lsposed_module/完美横屏_使用文档.md)
- [技术实现资料](lsposed_module/完美横屏_LSPosed 实现资料评估.md)
- [代码 Wiki](lsposed_module/Code_Wiki.md)
- [Xposed 文档](https://api.xposed.info/)
- [LSPosed 文档](https://lsposed.org/)
