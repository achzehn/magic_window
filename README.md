# Magic Window Adapter (魔窗适配模块)

LSPosed 模块，用于自动适配应用到 MIUI 魔窗/自由形式模式。

## 功能特性

- ✅ 自动适配应用到魔窗/自由形式模式
- ✅ 可调节每个应用的缩放比例（30% - 90%）
- ✅ 安全模式保护，防止应用崩溃
- ✅ 自动应用配置（应用安装时自动生效）
- ✅ 基于 LSPosed API 102 开发

## 技术实现

### 核心 Hook 点

1. **PackageConfigPersister** - 包配置持久化
   - Hook `loadPackageConfig()` - 加载包配置
   - Hook `savePackageConfig()` - 保存包配置

2. **CompatModePackages** - 兼容模式包管理
   - Hook `setAppScale()` - 设置应用缩放比例
   - Hook `isAppInCompatMode()` - 检查是否在兼容模式

3. **AppCompatSizeCompatModePolicy** - 尺寸兼容模式策略
   - Hook `getScaleForPackage()` - 获取应用缩放比例

4. **MiuiFreeFormActivityStackStub** - 自由形式 Stub
   - Hook `isFreeFormEnabled()` - 强制启用自由形式

### 缩放比例支持

| 配置值 | 缩放比例 | 常量名 |
|--------|---------|--------|
| 1 | 30% | DOWNSCALE_30 |
| 2 | 40% | DOWNSCALE_40 |
| 3 | 50% | DOWNSCALE_50 |
| 4 | 60% | DOWNSCALE_60 |
| 5 | 70% | DOWNSCALE_70 |
| 6 | 80% | DOWNSCALE_80 |
| 7 | 90% | DOWNSCALE_90 |

## 项目结构

```
lsposed_module/
├── app/                          # 应用模块
│   ├── src/main/
│   │   ├── java/com/github/lsposed/magicwindow/
│   │   │   ├── MagicWindowApplication.kt
│   │   │   └── ui/               # UI 相关
│   │   │       ├── MainActivity.kt
│   │   │       ├── AppListActivity.kt
│   │   │       └── SafeModeActivity.kt
│   │   └── res/                  # 资源文件
│   └── build.gradle.kts
│
├── library/
│   ├── common/                   # 通用工具
│   │   ├── base/
│   │   │   ├── BaseHook.kt
│   │   │   ├── BaseLoad.kt
│   │   │   └── XposedInitEntry.kt
│   │   ├── annotation/
│   │   │   └── HookBase.kt
│   │   └── utils/
│   │       └── PrefsBridge.kt
│   │
│   ├── libhook/                  # Hook 核心
│   │   ├── hooks/
│   │   │   ├── MagicWindowHook.kt
│   │   │   └── SystemServerHook.kt
│   │   ├── utils/
│   │   │   └── HookPrefs.kt
│   │   └── SystemServerInit.kt
│   │
│   ├── core/                     # 核心功能
│   └── processor/                # 注解处理器
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

- JDK 21+
- Android SDK 37
- Gradle 9.5
- LSPosed 环境

## 构建说明

### 1. 配置国内镜像（推荐）

项目已配置国内镜像源，无需额外配置。如需手动配置：

**~/.gradle/init.gradle**
```groovy
allprojects {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public/' }
        maven { url 'https://maven.aliyun.com/repository/google/' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin/' }
        maven { url 'https://jitpack.io' }
        mavenCentral()
        google()
    }
}
```

### 2. 构建命令

```bash
# 清理并构建 Release 版本
.\gradlew clean assembleRelease

# 构建 Debug 版本
.\gradlew clean assembleDebug

# 检查依赖
.\gradlew dependencies
```

### 3. 签名配置（可选）

创建 `signing.properties` 文件：

```properties
storeFile=<path-to-keystore>
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

## 安装使用

### 1. 安装模块

```bash
# 使用 ADB 安装
adb install -r app/build/outputs/apk/release/app-release.apk

# 或在 Android Studio 中直接运行
```

### 2. 激活模块

1. 打开 LSPosed Manager
2. 找到 "Magic Window Adapter"
3. 启用模块
4. 重启 System Server（或重启手机）

### 3. 配置应用

1. 打开 Magic Window Adapter 应用
2. 点击 "应用列表"
3. 选择要适配的应用
4. 勾选 "启用魔窗适配"
5. 拖动滑块调整缩放比例（30% - 90%）

### 4. 安全模式

如果某个应用频繁崩溃：

1. 打开 "安全模式"
2. 启用安全模式
3. 该应用将自动禁用魔窗适配

## 日志查看

模块日志可以通过以下方式查看：

```bash
# 查看完整日志
adb logcat | grep -E "MagicWindow|Xposed"

# 查看特定模块日志
adb logcat | grep MagicWindow
```

## 开发指南

### 添加新的 Hook

1. 在 `library/libhook/src/main/java/.../hooks/` 创建新的 Hook 类

```kotlin
class MyNewHook : BaseHook() {
    override fun init() {
        // Hook 逻辑
    }
}
```

2. 在 `SystemServerInit` 中注册

```kotlin
class SystemServerInit : BaseHook() {
    private val myNewHook = MyNewHook()
    
    override fun init() {
        initHook(myNewHook)
    }
}
```

### 偏好设置键名规范

- 启用魔窗：`enable_magic_window_<package_name>`
- 缩放比例：`magic_window_scale_<package_name>`
- 兼容模式：`compat_mode_<package_name>`
- 安全模式：`safe_mode_enabled`

## 已知问题

1. 部分应用可能需要重启才能生效
2. 某些 MIUI 版本可能存在兼容性问题
3. 缩放比例过小时可能导致 UI 显示异常

## 许可证

Apache License 2.0

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [libxposed](https://github.com/LSPosed/libxposed)
- [DexKit](https://github.com/LuckyPray/DexKit)
- [EzXHelper](https://github.com/KyuubiRan/EzXHelper)

## 相关资源

- [MIUI 魔窗分析项目](../..)
- [Xposed 文档](https://api.xposed.info/)
- [LSPosed 文档](https://lsposed.org/)
