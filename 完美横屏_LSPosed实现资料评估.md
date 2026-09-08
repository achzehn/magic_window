# HyperOS 4 平板"完美横屏" LSPosed 实现资料评估报告

> 初版评估：2026-09-04
> 更新 1：2026-09-07（补充 miui-embedding-window.jar 反编译实证，hook 点定案）
> **更新 2：2026-09-07（7 个插件 jar 全部反编译完毕，新增 4.6 / 4.7 章，方案 C 升级为首选）**
> **更新 3：2026-09-07（framework.jar 反编译，新增 4.8 章：总开关前置条件 + 应用进程侧 API + 2 个新暴露的 jar）**
> **更新 4：2026-09-07（miui-framework.jar / miui-framework.autoui.jar / androidx.window.extensions.jar 反编译，新增 4.9 章：规则 POJO 定义、云控 XML schema、★修正 4.6.4「重启不丢」结论、public 注入链）**
> **更新 5：2026-09-07（真机 getprop + 四个真机规则文件到手，新增 4.10 章：属性字典实测、`dataVersion=360816` 门槛、★修正根标签推断、★项目定位由「导入规则」调整为「翻开关 + 差集补规则」；所有缺口关闭）**
> 目标机型：piano（Redmi Pad Pro，HyperOS 4 / 骁龙平台，OS4.0.0.34.XPYCNXM）
> 参照项目：[sothx/mipad-magic-window](https://github.com/sothx/mipad-magic-window)（v3.00.47，3557 commits）— [官网](https://hyper-magic-window.sothx.com/)

---

## 1. 结论

**全部相关 jar（10 个）与全部真机侧文件均已到手并分析完毕，资料缺口已全部关闭，具备开工条件。**

初版评估结论为"资料不够用"，原因是 HyperOS 4 采用 Stub + Loader 插件化架构，魔窗真实实现不在 services.jar 中。该缺口现已完全补齐：从 piano OTA ROM 的 `system_ext` 分区提取到 7 个插件 jar，另加用户补充的 `framework.jar`、`miui-framework.jar`、`miui-framework.autoui.jar`、`androidx.window.extensions.jar`，**平行窗口 / 固定横屏 / 通用规则三套机制的类结构、规则 POJO 定义、规则 XML schema、查询接口方法签名、云控通道、总开关条件全部实测确认**（详见第 4.5 ~ 4.9 节）。

要点：

1. **首选方案是云控注入（方案 C），不是 hook** —— `miui.autoui.MiuiParsingAutoUI.updateAutoUICloudConfigFile(String, Map)` 是 `public static`，可直接反射调用写出 `/data/system/cloudFeature_autoui_list.xml`；再把 `mLastCloudConfigVersion` 抬高即可完全旁路 `/product/etc/autoui_list.xml`。走系统官方写入链，权限与 SELinux 全由系统处理（4.6.3 / 4.6.4）；
2. **★ 修正 4.6.4 的「重启不丢」结论** —— `mLastCloudConfigVersion` 是 `protected static volatile long`，static 块初始化为 0，且云控 XML 根标签不写 `dataVersion`。即**文件持久、版本字段不持久**，每次 system_server 重启都必须重新抬高该字段，方案 C 难度由"低"上调为"中低"（4.9.3）；
3. **方案 C 全链路均为 public API** —— 构造 `PackageRule`（7 参构造，注意 super 参数错位）→ `createCloudAutoUIRule(PackageRule)` 或直接 `updateAutoUICloudConfigFile` 落盘 → `setStaticLongField` 抬版本 → `updateAutoUIConfigFromCloudFile()` 重载（4.9.1 / 4.9.4）；
4. **hook 兜底点已定案** —— `MiuiEmbeddingWindowService.isEmbeddingListedForPackage(String)` 与 `isFixedOrientationListedForPackage(String)` 是最轻量的注入落点（4.5.3）；
5. **★ 存在前置条件，此前被遗漏，现已真机实测通过** —— `ro.config.miui_activity_embedding_enable`（平行窗口）与 `persist.miui.auto_ui_enable`（autoui）必须为 true。真机 getprop 实测**两者出厂即为 true**，故第 0 步由"强制改写"降级为"读值校验 + 兜底"；`ro.build.characteristics` 实测为 `nosdcard`（非 `tablet`），但该字段不 gate 平行窗口，**不应强改**（4.8.4 / 6.3）；
6. **修正初版报告一处错误** —— 用户开关文件在 HyperOS 4 上是 `embedded_setting_config.xml`，旧名 `magic_window_setting_config.xml` 仅作兼容常量保留；
7. **资料缺口已全部关闭** —— 三个规则 XML 实样、`/data/system/users/0/embedded_setting_config.xml`、三个总开关的 `getprop` 值均已取得并解析完毕（4.10）。`mediatek-services.jar` / `unisoc-services.jar` 为小米通用代码的平台分支，piano 是骁龙平台，二者均不存在且无需提取；
8. **★★ 项目定位调整（4.10.6，最重要的一条）** —— 真机实测系统已内置 **8047 条**平行窗口规则、**3890 条**固定横屏规则，与 sothx 规则集量级相当，「导入 sothx 8000+ 规则」的收益被严重高估。一期目标应改为：**① 批量翻 `embedded_setting_config.xml` 的用户开关（8411 条已有条目，仅 4 个属性）；② 覆盖 `fixed_orientation_list.xml` 中 197 条 `disable="true"`；③ autoui 差集补规则（内置仅 186 条，覆盖率最低，正是方案 C 的用武之地）**。

---

## 2. 参照项目原理（sothx 完美横屏）

sothx 模块本体是 **Magisk 模块（EJS + Shell）**，不是 Xposed 模块。横屏能力本身是 HyperOS 系统自带的，模块做的是把 **8000+ 应用的适配规则 XML** 替换/合并进系统规则文件：

| 机制 | 系统内置规则（只读） | 说明 |
|---|---|---|
| 平行窗口（分屏双栏） | `/product/etc/embedded_rules_list.xml` | `fullRule`、`splitPairRule`、`placeholder`、`splitRatio` 等属性 |
| 信箱模式/固定横屏 | `/product/etc/fixed_orientation_list.xml` | `ratio`、`supportModes`、`defaultSettings`（Android 15+） |
| 应用布局优化（缩放） | `/product/etc/autoui_list.xml` | `activityRule`、`optimizeWebView` |
| 用户开关 | ~~`/data/system/users/0/magic_window_setting_config.xml`~~ → **`embedded_setting_config.xml`** | ⚠️ 见下方修正 |

> **修正（2026-09-07 反编译实证）**：HyperOS 4 上用户开关文件名已变更为 `embedded_setting_config.xml`。旧名 `magic_window_setting_config.xml` 在 `MiuiParsingEmbeddedRule` 中仅以 `OLD_SETTING_CONFIG_FILE_NAME` 常量形式保留作兼容读取。目录常量为 `SYSTEM_USERS = "system/users/"`，即实际路径 `/data/system/users/<userId>/embedded_setting_config.xml`。

规则优先级：应用自适配 > 模块自定义规则（`/data/adb/MIUI_MagicWindow+/config/`）> 模块内置规则。修改后执行 `update_rules.sh` 生效。

---

## 3. 现有资料盘点

| 资料 | 大小 | 评估 |
|---|---|---|
| `tmp/平板/services.jar` | 38 MB（4 个 dex，未 odex 化） | **有价值但非主战场**。可确定 AOSP 侧 hook 点与 Stub 加载机制 |
| `tmp/平板/设置_17.apk` | 120 MB | 参考件：设置侧开关写入链路。注：aapt badging 无输出，manifest 完整性待确认 |
| `tmp/平板/浏览器_20.6.970814.apk`、`搜索_10.0.0.96.apk` | 216 MB / 11 MB | 适配测试样本 |
| `magic_window/lsposed_module/` | 工程骨架 | 可复用；但现有 hook 点按老 AOSP 结构猜测，需按真实 jar 重写 |
| `magic_window/jadx/`（jadx 1.5.1）+ SDK（build-tools 34、platforms 35/37） | — | 工具链齐备 |
| **`thumbs.zip`（用户手工提取，7 个插件 jar，dex 格式验讫）** | ~3 MB 压缩 | **核心资料**，见下方清单 |
| **`tmp/jadx-embedding/`（miui-embedding-window.jar 反编译产物）** | 21 个源文件 | **反编译成果**，hook 点定案依据 |

### 3.1 thumbs.zip 插件 jar 清单

| 文件 | 大小 | 功能 | 反编译状态 |
|---|---|---|---|
| `miui-embedding-window.jar` | 2.55 MB | **平行窗口/魔窗核心** | ✅ 已完成（4.5 节） |
| `miui-services.autoui.jar` | 0.05 MB | **应用布局优化（autoui）** | ✅ 已完成（4.6 节） |
| `miui-appcompat.jar` | 0.07 MB | **应用兼容 / 显示能力判定 / 云控** | ✅ 已完成（4.7 节） |
| `miui-appcompat.appcontinuity.jar` | 0.19 MB | 应用接续（16 类） | ✅ 已反编译，与横屏关联弱 |
| `miui-services.hovermode.jar` | 0.08 MB | 悬停模式（10 类） | ✅ 已反编译，非必需 |
| `miui-services.thirdappopt.jar` | 0.05 MB | 三方应用优化（5 类） | ✅ 已反编译，非必需 |
| `miui-services.hyperviewscale.jar` | 0.02 MB | 视图缩放（3 类） | ✅ 已反编译，非必需 |

> 7 个插件 jar 已全部反编译（产物在 `tmp/jadx-*/`）。与"完美横屏"直接相关的是前三个，后四个经查阅确认无规则注入价值。

> 注：`thumbs.zip` 已含全部相关 jar。dex 中虽引用 `mediatek-services.jar`，但那是小米通用代码按平台条件加载的分支，**piano 为骁龙平台，该文件不存在**（同理 `unisoc-services.jar` 也不存在），无需提取。

### magic_window 工程现状注意点

- [MagicWindowHook.kt](lsposed_module/library/libhook/src/main/java/com/github/lsposed/magicwindow/hook/hooks/MagicWindowHook.kt) 中 `CompatModePackages.getInstance()`、`loadPackageConfig(String)` 等签名与真实 AOSP/MIUI 不符（`findMethodExactIfExists` 容错会静默失效）；
- [SystemServerHook.kt](lsposed_module/library/libhook/src/main/java/com/github/lsposed/magicwindow/hook/hooks/SystemServerHook.kt) 的 `MiuiFreeFormActivityStackStub.isFreeFormEnabled()` 在 piano 上类名存在，但方法面待反编译确认；
- 工程方向（兼容缩放/自由窗口）与"平行窗口规则注入"目标不同，需重构 hook 层。

---

## 4. services.jar 实证分析（字节级验证）

### 4.1 基本信息与 ROM 特征

- 39,884,656 字节；`classes.dex`(11.5MB) + `classes2.dex`(9.9MB) + `classes3.dex`(11.8MB) + `classes4.dex`(6.6MB)
- AOSP 新特性字符串：`BinaryTransparencyService`、`BroadcastQueueImpl`、`VirtualDeviceImpl` → Android 15/16 基线（与 HyperOS 4 吻合）

### 4.2 关键字验证结果

| 关键字 | 命中 | 说明 |
|---|---|---|
| `ActivityTaskManagerService` | ✅ 3/0/2/6（dex1~4） | 正常 AOSP 核心 |
| `MiuiFreeFormManagerServiceStub`、`MiuiFreeForm*` | ✅ | 存在但均为 **Stub** |
| `PackageConfigPersister` | ✅ dex4×5 | AOSP 包配置持久化，可用于兜底 hook |
| `FixedOrientation` | ✅ dex4×23 | 信箱模式部分逻辑在 services.jar |
| `fullRule` / `Embedded` / `autoui` | ✅ 1 / 56 / 3 | 有零散引用，主解析不在 |
| `embedded_rules_list`、`fixed_orientation_list`、`autoui_list` | ❌ 全部 0 | **规则文件名不在本 jar** |
| `MiuiMagicWindow`、`MiuiMultiWindowUtils`、`magic_window`、`splitPairRule`、`supportFullSize` | ❌ 全部 0 | 实现在插件 jar / framework.jar |

### 4.3 Stub + Loader 插件化架构（核心发现）

services.jar 中 MIUI 服务全部为存根（27 个类）：

```
MiuiActivityController / MiuiAppCompatLoader / MiuiAppContinuityLoader
MiuiCvwGestureControllerStub / MiuiDisplayReportStub / MiuiDragAndDropStub
MiuiEmbeddingWindowServiceLoader / MiuiEmbeddingWindowServiceStub   ← 平行窗口
MiuiFreeFormActivityStackStub / MiuiFreeFormManagerServiceStub / MiuiFreeformServiceStub
MiuiFreezeStub / MiuiMirrorDragEventStub / MiuiMirrorInputMethodStub
MiuiMultiTaskManagerStub / MiuiMultiWindowServiceStub
MiuiNBIServiceStubHead / MiuiOrientationStub / MiuiPaperContrastOverlayStub
MiuiPipStub / MiuiScreenProjectionServicesUtils / MiuiScreenRotationAnimationStub
MiuiSoScManagerStub / MiuiSplitInputMethodStub / MiuiSplitScreenStub
MiuiTransformation / MiuiWindowMonitorStub
```

### 4.4 dex 内引用的插件 jar 清单（= 需要提取的文件）

| dex | 引用的 `/system_ext/framework/*.jar` | 对应功能 |
|---|---|---|
| classes4 | **miui-embedding-window.jar** | **平行窗口/魔窗核心（必提）** |
| classes4 | **miui-appcompat.jar**、miui-appcompat.appcontinuity.jar | 应用兼容/信箱模式（必提） |
| classes.dex | **miui-services.autoui.jar** | 应用布局优化（必提） |
| classes4 | ~~**mediatek-services.jar**~~ | **MTK 平台服务，piano 为骁龙，不适用** |
| classes2 | miui-services.hovermode.jar、miui-services-pointer-pad.jar | 悬停/触控板 |
| classes3 | miui-services.thirdappopt.jar | 三方应用优化 |
| classes4 | miui-services.hyperviewscale.jar、miui-services.navigationbarimmersive.jar | 视图缩放/导航栏沉浸 |
| classes3 | ~~mediatek-ipo.jar~~、stab-services.jar、ultra-services.jar、unipnp-services.jar | 其他（可选；mediatek-ipo 同为 MTK 专用，piano 无） |
| classes.dex | ~~unisoc-services.jar~~ | 展讯平台（piano 不需要） |
| classes4 | /framework/ActivityExt.jar | 框架扩展（可选） |

---

## 4.5 miui-embedding-window.jar 反编译实证（核心章节，2026-09-07 新增）

反编译命令：

```bash
JAVA_OPTS="-Xmx3g" jadx.bat --no-res --no-debug-info --decompilation-mode simple -j 4 \
  -d tmp/jadx-embedding tmp/thumbs-extract/miui-embedding-window.jar
```

### 4.5.1 类清单（`com.android.server.wm` 包，21 个类）

| 类 | 角色 |
|---|---|
| **`MiuiEmbeddingWindowService`** | **主服务**，对外查询接口全在此（≈5300 行） |
| **`MiuiSystemEmbeddedRule`** | **规则内存表持有者**（平行窗口），`mPackageRules` / `mFullRules` / `mSettingRules` |
| **`MiuiParsingEmbeddedRule`** | **规则 XML 解析器**，所有文件路径与属性名常量在此 |
| **`MiuiFixedOrientationController`** | 固定横屏/信箱模式控制器，持 `mFixOrientationRules` |
| `MiuiParsingFixedOrientationRule` | 固定横屏规则解析器 |
| `MiuiGenericController` / `MiuiGenericRule` / `MiuiParsingGenericRule` | 通用规则（第三档机制） |
| `MiuiActivityEmbeddingController` | Activity 嵌入运行时控制 |
| `MiuiLandscapeFullscreenController`(+`IA`) | 横屏全屏控制 |
| `FixedOrientationRule` / `SettingRule` / `MiuiRelaunchRule` | 规则数据模型（POJO） |
| `FixedOrizationScaleImpl` | 固定横屏缩放实现 |
| `AETaskController` / `TaskFragmentStubImpl` | 任务/TaskFragment 侧 |
| `InstalledPackagesProvider` / `MiuiVersionControlUtils` | 辅助 |
| `MiuiEmbeddingWindowServiceIA` / `...ImplCollector` | Loader 胶水层 |

### 4.5.2 规则文件路径与 schema 常量（`MiuiParsingEmbeddedRule` 实测）

```java
private static final String LOCAL_CONFIG_FILE_PATH        = "/product/etc/";
private static final String PACKAGE_CONFIG_FILE_NAME      = "embedded_rules_list.xml";
public  static final String SETTING_CONFIG_FILE_NAME      = "embedded_setting_config.xml";   // 新
private static final String OLD_SETTING_CONFIG_FILE_NAME  = "magic_window_setting_config.xml"; // 旧，仅兼容
public  static final String SYSTEM_USERS                  = "system/users/";

// ── 云控通道 ──
private static final String CLOUD_CONFIG_FILE_PATH         = "/data/system/";
private static final String CLOUD_PACKAGE_CONFIG_FILE_NAME = "cloudFeature_embedded_rules_list.xml";
private static final String CONTROL_MODULE_NAME            = "embedded_application_config";

// ── XML 属性 ──
private static final String XML_TAG_PACKAGE_RULES         = "packageRules";
private static final String XML_FULL_RULE                 = "fullRule";
private static final String XML_SUPPORT_FULL_SIZE         = "supportFullSize";
private static final String XML_ATTRIBUTE_SETTING_ENABLED = "miuiMagicWinEnabled";
// 另有 activityRule / placeholder / splitPairRule / splitRatio / defaultSettings /
//      relaunch / disableSensor / scaleMode / forcePortraitActivity 等 40+ 属性常量
```

`MiuiSystemEmbeddedRule` 侧补充常量：

```java
public  static final String ACTIVITY_RULE      = "activityRule";
public  static final String ANY                = "*";
public  static final String PLACEHOLDER        = "placeholder";
public  static final String DEFAULT_SPLIT_COLOR= "#E6E6E6:#323232";
public  static final String CURRENT_VERSION    = "ro.build.version.incremental";
public  static final String LAST_VERSION       = "lastVersion";
private static final String EMBEDED            = "embedded";
private static final String LANDSCAPEFORPAD    = "LandscapeForPad";
private static final String SELF_ADAPTION_APPS = "selfAdaptation";
```

> `LANDSCAPEFORPAD` / `SELF_ADAPTION_APPS` 对应 `mLandscapeForPad` / `mSelfAdaptationApp` 两个 `SparseArray<Map<String,Integer>>`（按 userId 分桶），是"平板横屏"用户态开关的存储结构。

### 4.5.3 实测 hook 点（方法签名 + 行号，全部经文件核对）

**A 档 —— 查询接口（推荐，最轻量：只改返回值）**

| 类 | 方法 | 行号 | 语义 |
|---|---|---|---|
| `MiuiEmbeddingWindowService` | `public boolean isEmbeddingListedForPackage(String)` | 4662 | 平行窗口白名单查询（转发给 `mEmbeddedRule`） |
| `MiuiEmbeddingWindowService` | `public boolean isFixedOrientationListedForPackage(String)` | 4726 | 固定横屏白名单查询（转发给 `mFixOriController`） |
| `MiuiEmbeddingWindowService` | `public boolean isSupportCameraPreviewInFixOri(ActivityRecord)` | 5133 | 固定横屏下相机预览 |
| `MiuiEmbeddingWindowService` | `public boolean isSupportCameraPreviewInFixOri(String)` | 5143 | 同上，包名重载 |
| `MiuiSystemEmbeddedRule` | `boolean isEmbeddingListedForPackage(String)`（**包级私有**） | 2248 | 实际实现：`!mFullRules.containsKey(p) && mPackageRules.get(p) != null` |
| `MiuiSystemEmbeddedRule` | `boolean isEmbeddingEnabledForPackage(String)` | 2209 | 含用户开关判定 |
| `MiuiSystemEmbeddedRule` | `public boolean isEmbeddingEnabledForPackageIncludeAdaptApp(String)` | 2230 | 含自适配应用 |
| `MiuiSystemEmbeddedRule` | `boolean queryEmbeddingEnableFormRulesList(String)` | 2665 | 直查规则表 |
| `MiuiFixedOrientationController` | `public boolean isFixedOrientationListedForPackage(String)` | 263 | 实际实现：`mFixOrientationRules.get(p) != null` |

**B 档 —— 规则表直改（一次性注入，效果最彻底）**

| 类 | 目标 | 行号 | 说明 |
|---|---|---|---|
| `MiuiFixedOrientationController` | `public Map<String, FixedOrientationRule> getFixOrientationRules()` | 207 | **返回内部表引用**，拿到后可直接 `put()` 注入 |
| `MiuiSystemEmbeddedRule` | `boolean hasPackageRule(String)` | 2023 | `mPackageRules.containsKey(p)`；`mPackageRules` 需反射取字段 |
| `MiuiSystemEmbeddedRule` | `protected void clearEmbeddedRule(String)` | 1350 | `mPackageRules.remove(p)`，hook 可阻止规则被清 |

**C 档 —— 解析链（在解析后追加自定义规则）**

| 类 | 方法 | 行号 | 说明 |
|---|---|---|---|
| `MiuiParsingEmbeddedRule` | `public boolean parseSinglePackage(String)` | 696 | 真正的 XML 解析入口 |
| `MiuiSystemEmbeddedRule` | `boolean parseSinglePackage(String)` | 2661 | 转发到上一行 |
| `MiuiFixedOrientationController` | `boolean parseSinglePackage(String)` | 370 | 固定横屏侧转发 |
| `MiuiFixedOrientationController` | 构造函数中 `mParsingFixedOrientationRule.loadLocalFixOrientationRuleXML()` | 49 / 694 | 全量加载入口，可 hook after 追加 |
| `MiuiSystemEmbeddedRule` | `public void mutexEmbeddedCompat(String)` | 2509 | 平行窗口与 SizeCompat 互斥仲裁 |
| `MiuiSystemEmbeddedRule` | `void syncSinglePackageSettingRule(String, Map<String, FixedOrientationRule>)` | 2958 | 用户开关与规则同步 |

> ⚠️ `MiuiSystemEmbeddedRule` 与其多数方法为**包级私有**（无 `public`），Xposed 侧必须用 `XposedHelpers.findClass("com.android.server.wm.MiuiSystemEmbeddedRule", pluginClassLoader)` + `findAndHookMethod`，不能靠 `Class.forName` 走 system_server 默认 ClassLoader。

### 4.5.4 懒加载机制（影响 hook 覆盖面）

`MiuiEmbeddingWindowService$EmbeddedPackageMonitor` 内部类：

```java
private class EmbeddedPackageMonitor extends PackageMonitor {
    private void loadSinglePackageRules(String str);   // hasPackageRule(str)==false 时才 parse
    public  void onPackageAdded(String str, int i);
    public  void onPackageRemoved(String str, int i);
    public  void onPackageUpdateFinished(String str, int i);
}
```

规则**按包懒加载**：开机时并非全量装载，而是在应用安装/更新/首次使用时按需解析。因此仅 hook 开机时的全量加载入口是不够的，**必须覆盖 `parseSinglePackage` 或查询接口本身**。这也是推荐 A 档（查询接口）方案的原因 —— 无论何时加载，查询必经此路。

### 4.5.5 三套机制的互斥优先级（`MiuiGenericController` 实证）

```java
public void createGenericRule(MiuiGenericRule miuiGenericRule) {
    String packageName = miuiGenericRule.getPackageName();
    if (this.mEws.isFixedOrientationListedForPackage(packageName)) return;  // ① 固定横屏优先级最高
    if (this.mEws.isEmbeddingListedForPackage(packageName)) return;         // ② 平行窗口次之
    this.mGenericRules.put(packageName, miuiGenericRule);                   // ③ 通用规则兜底
}
```

**优先级：固定横屏（信箱） > 平行窗口 > 通用规则。**

对模块设计的直接影响：如果某应用被注入了固定横屏规则，平行窗口规则将**永不生效**。模块 UI 上这两项必须做成**单选**而非多选，否则会出现"设置了平行窗口但无效"的困惑。

### 4.5.6 云控注入通道（推荐方案，非 hook 路线）

`MiuiEmbeddingWindowService$Inner`（`IMiuiEmbeddingWindow.Stub` 实现）的消息定义：

```java
private static final int MSG_LOAD_CLOUD_SYSTEM_CONFIG_DATA          = 1;
private static final int MSG_LOAD_FIXED_ORIENTATION_CLOUD_CONFIG_DATA = 3;
private static final int MSG_UPDATE_SETTING_SWITCH                  = 5;
private static final int MSG_LOAD_GENERIC_CLOUD_CONFIG_DATA         = 7;
private static final int MSG_LOAD_FULL_SCREEN_CLOUD_CONFIG_DATA     = 8;
private final MiuiSystemEmbeddedRule.CloudConfigChangedListener mCloudConfigChangedListener;
```

解析侧：

```java
private void parseCloudPackageConfig(List<MiuiSettings.SettingsCloudData.CloudData> list);
private void addValidCloudData(List<CloudData> list, List<CloudData> list2);
// 落盘：/data/system/cloudFeature_embedded_rules_list.xml
// 版本字段：mLastCloudConfigVersion（对应 CloudData 的 dataVersion）
```

**这条通道的价值**：小米自己就是通过云控下发新应用适配规则的 —— 也就是说，系统**原生支持**在不修改 `/product` 只读分区的前提下追加规则。模块只需：

1. 构造符合 `embedded_application_config` 模块名的 `CloudData` JSON（`dataVersion` 取大值以覆盖官方）；
2. 写入 `/data/system/cloudFeature_embedded_rules_list.xml`（system_server 域内可写，SELinux 上下文 `system_data_file`）；
3. 或直接 hook `parseCloudPackageConfig` 的入参 list，塞入自造 `CloudData`。

相比 hook 内存表，此方案**走系统自身的数据通路，兼容性和稳定性最好**，且天然支持热更新（`CloudConfigChangedListener`）。缺点是需要精确逆向 CloudData JSON 的字段结构。

---

## 4.6 miui-services.autoui.jar 反编译实证（2026-09-07 新增）

反编译命令同 4.5（`--decompilation-mode simple`），产物 `tmp/jadx-miui-services.autoui/sources/miui/autoui/`。

**这是本轮最重要的发现所在** —— autoui 侧存在**公开的云控文件写入 API**与**版本优先级旁路逻辑**，是三套机制中注入成本最低的一条通路。

### 4.6.1 类清单（`miui.autoui` 包）

| 类 | 角色 |
|---|---|
| **`MiuiParsingAutoUI`** | **解析入口 + 云控读写**，所有路径常量与 `updateAutoUICloudConfigFile` 在此 |
| **`MiuiSystemAutoUIRule`** | **规则内存表持有者**，`mPackageRules` / `mPackageRules2` / `mSettingRules` |
| `MiuiAutoUIServiceImpl` | 服务实现，对外开关 API |
| `MiuiParsingAutoUIRule` | rule1（包/Activity 级）解析 + 用户开关读写 + XML 构建 |
| `MiuiParsingAutoUI2Rule` | rule2（View 级细粒度策略）解析 + XML 构建 |
| `MiuiActivityEmbeddingHelper` | 反查平行窗口状态的桥接层 |

### 4.6.2 路径与 schema 常量（`MiuiParsingAutoUI` 实测）

```java
public  static final String CLOUD_CONFIG_FILE_PATH          = "/data/system/";
public  static final String CLOUD_PACKAGE_CONFIG_FILE_NAME  = "cloudFeature_autoui_list.xml";
public  static final String CLOUD_PACKAGE_CONFIG2_FILE_NAME = "cloudFeature_autoui2_list.xml";
private static final String LOCAL_CONFIG_FILE_PATH          = "/product/etc/";
private static final String PACKAGE_CONFIG_FILE_NAME        = "autoui_list.xml";
private static final String CONTROL_MODULE_NAME             = "autoui_application_config";
private static final String CLOUD_CONTROL_DATA_VERSION      = "dataVersion";
private static final String CLOUD_CONTROL_MODULES           = "modules";
public  static final String CLOUD_TAG_AUTOUI_RULES          = "autoUIRules";
private static final String CLOUD_TAG_AUTOUI_RULES_2        = "autoUIRules2";

// XML 属性
protected static final String XML_ELEMENT_PACKAGE = "package";
protected static final String ACTIVITY = "activity";      ACTIVITY_RULE = "activityRule";
protected static final String SKIPPED_ACTIVITY_RULE = "skippedActivityRule";
protected static final String SKIPPED_APP_CONFIG_CHANGE = "skippedAppConfigChange";
protected static final String OPTIMIZE_WEBVIEW = "optimizeWebView";
protected static final String ENABLE = "enable";          VERSION_CODE = "versionCode";
private   static final String XML_RULE2 = "rule2";
```

`MiuiParsingAutoUIRule` 侧（用户开关存储）：

```java
public  static final String SETTING_CONFIG_FILE_NAME        = "autoui_setting_config.xml";
private static final String SETTING_CONFIG_BACKUP_FILE_NAME = "autoui_setting_config_backup.xml";
public  static final String SYSTEM_USERS                    = "system/users/";
// 实际路径：/data/system/users/<userId>/autoui_setting_config.xml
```

`MiuiParsingAutoUI2Rule` 侧（rule2 = View 级策略）：`viewPolicy` / `policyType` / `viewRule` / `embeddingExpand` / `policy` / `view` / `id` / `path` / `view_policy` / `policytype`。

### 4.6.3 ★ 核心发现 1：公开的云控文件写入 API

`MiuiParsingAutoUI.updateAutoUICloudConfigFile(String, Map)` @L368 —— **`public static`，可直接反射调用**：

```java
public static boolean updateAutoUICloudConfigFile(String str, Map<String, ? extends BasePackageRule> map) {
    File file = new File(CLOUD_CONFIG_FILE_PATH, str);      // /data/system/<str>
    FileOutputStream fos = new FileOutputStream(file);
    BufferedOutputStream bos = new BufferedOutputStream(fos);
    if (CLOUD_PACKAGE_CONFIG_FILE_NAME.equals(str))
        MiuiParsingAutoUIRule.buildCloudFeatureAutoUiListXml(map, bos);   // rule1
    else
        MiuiParsingAutoUI2Rule.buildCloudFeatureAutoUi2ListXml(map, bos); // rule2
    bos.flush();
    FileUtils.sync(fos);
    FileUtils.setPermissions(file.toString(), 432, -1, -1);  // 0660
    return true;
}
```

**意义**：模块不必自己拼 XML、不必处理权限与 SELinux 上下文 —— 在 system_server 内反射调用此方法，传入自造的 `Map<String, PackageRule>`，系统会自己把规则序列化落盘到 `/data/system/cloudFeature_autoui_list.xml`。

### 4.6.4 ★ 核心发现 2：版本优先级旁路（`loadPackage` @L138）

```java
public static void loadPackage(MiuiSystemAutoUIRule rule) {
    File cloud1 = new File(CLOUD_CONFIG_FILE_PATH, CLOUD_PACKAGE_CONFIG_FILE_NAME);   // /data/system/cloudFeature_autoui_list.xml
    File cloud2 = new File(CLOUD_CONFIG_FILE_PATH, CLOUD_PACKAGE_CONFIG2_FILE_NAME);
    File local  = new File(LOCAL_CONFIG_FILE_PATH, PACKAGE_CONFIG_FILE_NAME);         // /product/etc/autoui_list.xml
    mLocalDataVersion = parseLocalDataVersion(local);

    if (mLastCloudConfigVersion > 0 && mLastCloudConfigVersion >= mLocalDataVersion) {
        if (cloud1.exists()) parsePackageXml(rule, cloud1, true);    // ★ 只读云控，完全不读 /product
        if (cloud2.exists()) parsePackageXml(rule, cloud2, false);
        return;
    }
    if (local.exists()) parseLocalPackageXml(rule, local, null);     // 否则走本地
}
```

**推论：只要把 `mLastCloudConfigVersion` 抬高到 ≥ 本地版本，系统就完全改读 `/data/system/cloudFeature_autoui_list.xml`，`/product/etc/autoui_list.xml` 被彻底旁路 —— 无需 hook 任何查询逻辑，效果等同于 Magisk 方案替换 `/product` 文件，但不动只读分区。**

这是与 4.5.6 云控通道相比更具可操作性的一条路径：4.5.6 需要逆向 CloudData JSON 字段，而此处系统直接提供了"写文件 + 抬版本号"的两步法。

> ⚠️ **本节初稿曾断言"持久化且重启不丢"，该结论有误，已在 4.9.3 修正**：`mLastCloudConfigVersion` 是 `static volatile long` 且 static 块初始化为 0，云控 XML 不写 `dataVersion`，因此**只有 XML 文件持久，版本字段每次开机归零**，必须在每次 system_server 启动时重新抬高。

### 4.6.5 实测 hook 点

**A 档 —— 查询接口（autoui 侧唯一对外出口）**

| 类 | 方法 | 行号 | 语义 |
|---|---|---|---|
| `MiuiSystemAutoUIRule` | `public Bundle getSystemAutoUIRules(String)` | 254 | **主 hook 点**，返回 `enable`/`activityRule`/`optimizeWebView` 等全部字段 |
| `MiuiSystemAutoUIRule` | `private boolean isAutoUIEnabledForPackage(BasePackageRule, SettingRule)` | 136 | 核心判定（规则 + 用户开关） |
| `MiuiSystemAutoUIRule` | `public Map<String, Boolean> getAutoUIApps()` | 243 | 已启用应用列表 |
| `MiuiAutoUIServiceImpl` | `public boolean setAutoUIAppEnable(String, boolean, boolean, boolean)` | 433 | **公开开关 API**，可反射直调 |

`getSystemAutoUIRules` 实测逻辑要点：

```java
PackageRule  r1 = mPackageRules.get(pkg);
PackageRule2 r2 = mPackageRules2.get(pkg);
if (r1 == null && r2 == null) { /* 仅 isEnableWebViewAllApp 时给 webview 开关 */ return bundle; }
if (r2 != null) {                                   // rule2 优先
    boolean enable = isAutoUIEnabledForPackage(r2, mSettingRules.get(pkg));
    if (!enable) enable |= MiuiActivityEmbeddingHelper.getInstance()
                              .isEmbeddingEnabledForPackage(pkg);   // ★ 与平行窗口联动
    bundle.putString("autoUIRules", "autoUIRules2"); ...
} else { /* rule1 分支，额外带 activityRule / skippedActivityRule / skippedAppConfigChange */ }
bundle.putLong("cloudVersion", Math.max(MiuiParsingAutoUI.mLastCloudConfigVersion,
                                        MiuiParsingAutoUI.mLocalDataVersion));
```

> **联动发现**：autoui 的 enable 会被平行窗口状态"或"上去 —— 即某应用只要开了平行窗口，autoui 布局优化自动生效。这解释了 sothx 模块中两类规则常成对出现。

**B 档 —— 规则表直改**

| 类 | 目标 | 行号 |
|---|---|---|
| `MiuiSystemAutoUIRule` | 反射字段 `mPackageRules`(L47) / `mPackageRules2`(L48) / `mSettingRules`(L50)（均为 HashMap） | 47-50 |
| `MiuiSystemAutoUIRule` | `boolean createPackage(PackageRule)` / `createPackage2(PackageRule2)` / `void createSetting(String,String)` | 187 / 195 / 203 |
| `MiuiSystemAutoUIRule` | `Map<String,SettingRule> getSettingRules()`（**返回内部表引用**） | 250 |

**C 档 —— 云控通道（本节推荐）**

| 类 | 方法 | 行号 | 用法 |
|---|---|---|---|
| `MiuiParsingAutoUI` | `public static boolean updateAutoUICloudConfigFile(String, Map)` | 368 | 反射直调，落盘自定义规则 |
| `MiuiParsingAutoUI` | `public static void loadPackage(MiuiSystemAutoUIRule)` | 138 | hook before / 改 `mLastCloudConfigVersion` 静态字段 |
| `MiuiParsingAutoUI` | `public static void loadAutoUICloudConfigData(MiuiSystemAutoUIRule, Context)` | 113 | 云控数据装载 |
| `MiuiParsingAutoUI` | `private static void parseAutoUICloudConfigData(..., List<CloudData>)` | 165 | hook 入参 list 塞自造 CloudData |
| `MiuiSystemAutoUIRule` | `public void reloadConfigData()` / `updateAutoUIConfigFromCloudFile()` | 463 / 497 | 写盘后触发热重载 |

### 4.6.6 附带发现：外挂 JS 规则通道

`MiuiAutoUIServiceImpl` 中：

```java
public  static final String AUTOUI_JS_PATH_PROP     = "persist.sys.miui_autoui_js_rule";
public  static final String AUTO_UI_ENABLE_PROP     = "persist.sys.miui_autoui_logging";
private static final String AUTOUI_EXT_PACKAGE      = "com.miui.autoui.ext";
private static final String EXT_DEX_PATH            = "extDexPath";
private static final String EXT_OPTIMIZED_DIRECTORY = "extOptimizedDir";
private String getAutoUIExtPath();          // L329
private void  updateAutoUIExtPath();        // L366
```

autoui 支持通过系统属性 `persist.sys.miui_autoui_js_rule` 指向外部 JS 规则文件，并可从 `com.miui.autoui.ext` 包动态加载扩展 dex。这是一条**官方预留的扩展口**，值得后续单独挖掘（可能比 hook 更稳）。

---

## 4.7 miui-appcompat.jar 反编译实证（2026-09-07 新增）

产物 `tmp/jadx-miui-appcompat/sources/com/android/server/wm/`。此 jar 主要服务于**折叠屏/翻盖机的应用接续与兼容策略**，对平板横屏的直接价值弱于 4.5 / 4.6，但提供了：① 一个与横屏方向直接相关的云控 key；② `getDisplayChangeAbility` 这个尺寸兼容判定；③ 一套结构清晰、可反射直改的静态列表。

### 4.7.1 类结构

```
ApplicationCompatPolicy (abstract, 987行) extends SystemService implements IApplicationCompat
  ├─ ApplicationCompatManager (829行)  ← 实际执行器
  ├─ ApplicationCompatBase / ApplicationCompatRouterImpl
  ├─ AppCompatTaskImpl / AppCompatTaskContainer
  ├─ FoldTabletContinuityShellCommand / MiuiAppCompatImplCollector
  └─ cloudcontrol/
       ├─ AbsDataThread (abstract, 云控读取工具基类)
       ├─ CloudDataThread / FlipCloudDataThread   ← 云控
       ├─ LocalDataThread（空壳：run() 空实现）
       └─ LocalDataController（单例，本地 JSON → 运行时列表）
```

### 4.7.2 本地配置：JSON 而非 XML（与 4.5/4.6 不同）

```java
private static final String LOCAL_JSON_CONFIG_FILE_NAME_1 = "continuity_list.json";
private static final String LOCAL_JSON_CONFIG_FILE_NAME_3 = "new_list_2.json";
static {
    TAG = ApplicationCompatUtilsStub.get().isDialogContinuityEnabled() ? "FlipPolicy" : "FoldPolicy";
    // ★ 路径随设备类型分叉：平板走 /system_ext/etc/，非平板走 /product/etc/
    LOCAL_CONFIG_FILE_PATH = ApplicationCompatUtilsStub.get().isTablet() ? "/system_ext/etc/" : "/product/etc/";
    CORE_WHITE_LIST.add("com.miui.home"); // + com.android.phone / contacts / incallui
}
public static long getLocalVersion();   // L501，读 continuity_list.json 的 "id"，默认 1199910
static void loadConfig(String fileName);// L573，JSON 解析，19 个 key
```

> ⚠️ **piano 是平板** → 本地配置在 `/system_ext/etc/continuity_list.json`，**不在 `/product/etc/`**。第 5.2 节提取命令需相应调整。

### 4.7.3 配置 key 字典（`IApplicationCompat`，265 行全常量接口）

```java
"id"                                      // APP_CONTINUITY_ID_KEY（版本号）
"app_continuity_whitelist" / "app_continuity_blacklist"
"app_restart_blacklist" / "app_relaunch_blacklist"
"app_activity_relaunch_blacklist" / "app_activity_block_blacklist"
"app_activity_notrelaunch_blacklist" / "app_activity_restart_blacklist"
"app_activity_accessibility_notrelaunch_blacklist"
"app_activity_fold_tablet_relaunch_blacklist" / "..._block_blacklist"
"app_activity_fold_tablet_notrelaunch_blacklist" / "..._restart_blacklist"
// ★ 与"方向"直接相关的两个
"app_fold_outer_portrait_orientation_whitelist"
"app_fold_outer_portrait_orientation_excludelist"
"app_intercept_allowlist" / "app_intercept_blacklist"
"app_intercept_component_allowlist" / "app_intercept_component_blacklist"

public static final String MIUI_CONTINUITY_POLICY_PROPERTY = "miui.continuity.policy"; // 应用可声明的 PM Property
public static final int    DEFAULT_CONTINUITY_VERSION      = 1199910;
```

策略枚举：`ALLOW=0, RELAUNCH=1, BLOCK=2, RESTART=3, INTERCEPT=4, ALLOW_START=5, RELAUNCH_BY_BLOCK=6, RESTART_BY_BLOCK=7, INTERCEPT_BY_BLOCK_LIST=8, INTERCEPT_COMPONENT_BY_BLOCK_LIST=9, INTERCEPT_BY_ALLOW_LIST=10, INTERCEPT_COMPONENT_BY_ALLOW_LIST=11`；
尺寸兼容枚举：`FORCED_RESIZEABLE_BY_USER_SETTING=1, FORCED_UNRESIZEABLE_BY_USER_SETTING=2, FORCED_RESIZEABLE_BY_ALLOW_LIST=3, FORCED_UNRESIZEABLE_BY_BLOCK_LIST=4, EXCLUDE_BY_META_DATA=5`。

### 4.7.4 运行时列表（B 档最佳落点）

`IApplicationCompat` 的 static 块中把 19 组列表全部 `new ArrayList()`，`LocalDataController.updatePolicyFromLocal()` @L65 只是把本地 JSON 解析结果 `addAll` 进去：

```
CONTINUITY_ALLOW_LIST / CONTINUITY_BLOCK_LIST / CONTINUITY_RESTART_LIST / CONTINUITY_RELAUNCH_LIST
CONTINUITY_COMPONENT_* ×5 / CONTINUITY_COMPONENT_FOLD_TABLET_* ×4
CONTINUITY_FOLD_OUTER_PORTRAIT_ORIENTATION_LIST / ..._EXCLUDE_LIST
INTERCEPT_LIST / INTERCEPT_COMPONENT_LIST / ALLOW_START_LIST / ALLOW_START_COMPONENT_LIST
```

**这些是接口上的 `public static final List`，Xposed 侧拿到 Class 后可直接 `get(null)` 取引用并 `add()` —— 无需构造任何私有对象，是全篇最简单的注入点。**

### 4.7.5 云控通道（与 autoui 同构但版本判定方向相反）

`cloudcontrol/CloudDataThread`（204 行）：

```java
private long getCloudVersion() {
    return MiuiSettings.SettingsCloudData.getCloudDataInt(
        mContext.getContentResolver(), mModuleName, "id", 0);
}
private boolean updatePolicyFromCloudWithVersionCheck(String reason) {
    long cloud = getCloudVersion();
    if (cloud <= mContinuityVersion) return false;
    if (sLocalController.getLocalVersion() >= cloud) return false;  // ★ 本地版本高 → 用本地
    updateContinuityPolicyFromCloud(reason);
    if (ApplicationCompatUtilsStub.get().isDialogContinuityEnabled())
        updateInterceptPolicyFromCloud(reason);
    return true;
}
```

- 模块名来自 `mResources.getString(286195891)`（资源 ID，需真机 dump 确认字符串值）；
- 启动时机：`ApplicationCompatPolicy.onBootPhase(550)` → `mCloudDataThread.start()`；
- 监听：`registerContentObserver(MiuiSettings.SettingsCloudData.getCloudDataNotifyUri(), ...)`。

`AbsDataThread`（152 行）给出了 **CloudData JSON 的实测结构**，对 4.5.6 / 4.6.4 的方案 C 有直接参考价值：

```java
public List<String> getListFromCloud(String moduleName, String key);
    // getCloudDataString(...) → JSONArray → 逐项 getString
public Map<String, List<String>> getListInMapFromCloud(String moduleName, String key, String k1, String k2) {
    CloudData cd = MiuiSettings.SettingsCloudData.getCloudDataSingle(mContentResolver, moduleName, key, null, false);
    JSONArray arr = cd.json().getJSONArray(key);   // ★ 每项形如 { <k1>: "包名", <k2>: [ ... ] }
}
public Pair<Map<String,String>, List<String>> getMapAndListFromCloud(String moduleName, String key);
```

### 4.7.6 与横屏相关的判定：`getDisplayChangeAbility`

`ApplicationCompatManager.getDisplayChangeAbility(Task)` @L360：

```java
ActivityRecord ar = task.topRunningActivityLocked();
if (ar == null || task.getDisplayId() != 0) return 0;
boolean fixedAspect = ActivityTaskManagerServiceStub.get()
        .isFixedAspectRatioPackage(ar.packageName, task.mUserId);
// 结合 task.mResizeMode(1/2)、task.inMultiWindowMode()、task.isActivityTypeStandard() → 返回 0 / 1 / 3
```

另有三方应用策略扫描（应用可通过 `PackageManager.Property` 自声明）：

```java
private void getApplicationPackageNameProperty(String pkg) {   // L84
    if (getPropertyIntByApplication("miui.continuity.policy", pkg) == 5) ALLOW_START_PACKAGES_SET.add(pkg);
    if (getPropertyIntByApplication("miui.continuity.policy", pkg) == 4) NOT_ALLOW_START_PACKAGES_SET.add(pkg);
}
public void scanThirdPartyApps();              // L799，bg 线程全量扫描
public int  updateThirdPartyAppsProp(String);  // L804，单包更新
```

### 4.7.7 结论

appcompat 侧对**平板横屏主线**（平行窗口/固定横屏/布局优化）**不是必经之路**，其核心场景是折叠屏形态切换。但两点可用：

1. `CONTINUITY_FOLD_OUTER_PORTRAIT_ORIENTATION_LIST` / `..._EXCLUDE_LIST` 是方向相关的静态 List，若后续需要控制方向排除名单，这里是零成本落点；
2. `AbsDataThread` 反编译出的 `CloudData.json()` 结构，可直接复用于 4.5.6 embedding 云控方案的 JSON 构造。

---

## 4.8 framework.jar 反编译实证（2026-09-07 新增）

来源：`D:\Downloads\framework.jar`，51,184,201 字节，6 个 dex。解包产物在 `tmp/framework-extract/`，已反编译 `classes6.dex` → `tmp/jadx-framework6/`、`classes.dex` → `tmp/jadx-framework1/`。

### 4.8.1 价值判断：与原先预期不符（重要修正）

5.1 节原先把 framework.jar 列为「用于确认 `MiuiEmbeddingWindowStub` / `MiuiSizeCompatManager` 接口」，并寄望它能补齐云控方案的两处空白。实证结果：

| 原先期待 | 实测 |
|---|---|
| `MiuiSettings.SettingsCloudData.CloudData` 字段结构 | ❌ **不在此 jar**，应在 `miui-framework.jar` / `miuisystem.jar` |
| `BasePackageRule` / `PackageRule` / `PackageRule2` / `FixedOrientationRule` POJO | ❌ **不在此 jar** |
| `MiuiSizeCompatManager` / `ActivityTaskManagerServiceStub` | ❌ 不在此 jar |
| `MiuiEmbeddingWindowStub` | ✅ 在 `classes6.dex` |

结论：**有解析价值，但价值点是"客户端侧 API 与开关判定"，不是"规则数据结构"**。方案 C（云控注入）仍需 `miui-framework.jar` 才能完全落地。

### 4.8.2 MIUI 扩展集中在 classes6.dex

各 dex 的 MIUI 类分布（字节级统计，去重后的类型描述符计数）：

| dex | `Lmiui/*` | `Lmiui/window/*` | `Lmiui/thirdappadaptation/*` | `Landroid/appcompat/*` |
|---|---|---|---|---|
| classes.dex | 9 | 2 | 2 | **8** |
| classes2.dex | 11 | 1 | 5 | 0 |
| classes3.dex | 6 | 0 | 0 | 0 |
| classes4.dex | 24 | 2 | 8 | 0 |
| classes5.dex | 14 | 1 | 3 | 0 |
| **classes6.dex** | **258** | **7** | **147** | 1 |

其余 5 个 dex 为 AOSP framework 主体，无解析价值。故只反编译了 classes6 + classes（后者含 `android.appcompat` 8 类）。

### 4.8.3 ★ 发现 1：第 8 个插件 jar —— `androidx.window.extensions.jar`

`miui/window/MiuiEmbeddingWindowStub.java` 的 `SingletonHolder.getStub()`：

```java
private static final String STUB_IMPL = "com.miui.window.MiuiEmbeddingWindow";

private static MiuiEmbeddingWindowStub getStub() {
    if (!Config.isEnabled()) return new <default impl>;
    if (!ActivityThread.isEmbedded()) return new <default impl>;
    ClassLoader base = ActivityThread.currentActivityThread().getBaseClassLoader();
    try {
        return (MiuiEmbeddingWindowStub) Class.forName(STUB_IMPL, true, base).newInstance();
    } catch (ClassNotFoundException e) {
        // fallback：从插件 jar 加载
        return (MiuiEmbeddingWindowStub) Class.forName(STUB_IMPL, true,
                new PathClassLoader("/system_ext/framework/androidx.window.extensions.jar", base)
        ).newInstance();
    }
}
```

含义：
- 4.5 节的 `miui-embedding-window.jar` 是 **system_server 侧**（`com.android.server.wm.*`）；
- `androidx.window.extensions.jar` 里的 `com.miui.window.MiuiEmbeddingWindow` 是 **应用进程侧**实现，负责实际的分栏渲染、坐标缩放、方向拦截；
- 这是 3.1 节 7 个 jar 清单**之外**的第 8 个 jar，尚未提取。

`MiuiEmbeddingWindowStub` 中与横屏直接相关的接口（可 hook 的应用进程侧落点）：

| 方法 | 用途 |
|---|---|
| `boolean isEmbeddingEnabledForPackage(String)` | 应用进程侧查询，与 4.6.5 中 `MiuiSystemAutoUIRule` 的联动来源同名 |
| `boolean setEmbeddedEnable(String, boolean)` | 运行时开关写入 |
| `boolean overrideDisplayRotation(DisplayInfo)` | **直接改写 DisplayInfo 旋转** |
| `boolean sandboxDisplayInfo(DisplayInfo, Resources)` | 沙箱化 DisplayInfo（应用看到的屏幕尺寸） |
| `boolean shouldIgnoreOrientationModify(Activity)` | 忽略 Activity 方向修改 |
| `void onActivitySetRequestedOrientation(Activity, int)` | 拦截 `setRequestedOrientation` |
| `boolean isDisableSensor(String)` / `shouldDisableSensor(...)` | 禁用重力感应 |
| `Rect getEmbeddingPortraitBounds()` / `Map getEmbeddedApps()` | 边界与已适配应用表 |
| `void initSystemRules()` / `void reInitSystemRules()` | **规则重载入口（可直接反射触发刷新）** |

常量：`PRIVATE_FLAG_EXT_EMBEDDING_WINDOW = 0x10000`、`PRIVATE_FLAG_EXT_USE_MIUI_SPLIT_EMBEDDING_WINDOW = 0x80000`、`PRIVATE_FLAG_EXT_EMBEDDING_RULE_FOR_SWITCH_DISPLAY = 0x100000`。

### 4.8.4 ★ 发现 2：三个 SystemProperty 总开关（决定所有机制能否生效）

`miui/thirdappadaptation/MiuiAppAdaptationProperties.java` + `MIUIAutoUIManagerStub`：

```java
IS_FOLD   = SystemProperties.getInt("persist.sys.multi_display_type", 1) == 3;
IS_TABLET = "tablet".equals(SystemProperties.get("ro.build.characteristics", ""));
IS_TABLET_WITH_SIDE_CUTOUT = SystemProperties.getBoolean("ro.miui.device.pad_with_side_cutout", false);

// 平行窗口总开关
ActivityEmbeddingProp.isActivityEmbeddingEnabled()
    = SystemProperties.getBoolean("ro.config.miui_activity_embedding_enable", false);

// autoui 总开关
MIUIAutoUIManagerStub.IS_AUTO_UI_ENABLED
    = SystemProperties.getBoolean("persist.miui.auto_ui_enable", false);
```

| 属性 | 控制对象 | 说明 |
|---|---|---|
| `ro.config.miui_activity_embedding_enable` | 平行窗口（4.5 节全部机制） | `ro.` 只读，**不可 setprop**，但 Xposed 可 hook `SystemProperties.getBoolean` 或直接改 `MiuiAppAdaptationProperties.ActivityEmbeddingProp` 的判定 |
| `persist.miui.auto_ui_enable` | 应用布局优化（4.6 节） | `persist.` 可 `setprop` 持久化 |
| `persist.settings.large_screen_opt.enabled` | 设置页 AE 开关显隐，由 `initProperties()` 在 fold/tablet 上写 true | — |
| `ro.build.characteristics` | 决定 4.7.2 中 appcompat 配置路径分叉（`/system_ext/etc/` vs `/product/etc/`） | 已确认 piano = `nosdcard`（国行 MIUI 平板特征，非 `tablet`） |

> **重要发现**：piano 真机 `ro.build.characteristics = nosdcard`，不是 `tablet`。这意味着 `"tablet".equals(...)` 的精确匹配返回 false。但 `MiuiEmbeddingWindowImpl` 与 `MiuiMultiDisplayTypeInfo` 均有 `ro.config.tablet` 作为 fallback（`SystemProperties.getBoolean("ro.config.tablet", false)`），若该属性也为 false 则 `IS_TABLET` 为 false。好消息是：**两个核心开关 `ro.config.miui_activity_embedding_enable` 与 `persist.miui.auto_ui_enable` 在真机上均为 `true`，无需额外 hook**。`nosdcard` 不影响平行窗口/autoui 的核心功能，仅可能影响 appcompat 配置路径分叉（需真机确认 `continuity_list.json` 实际位置）。

**实践意义**：若真机上 `ro.config.miui_activity_embedding_enable=false`，则 4.5 节所有 hook 点全部不会被执行，必须先 hook `MiuiAppAdaptationProperties.ActivityEmbeddingProp.isActivityEmbeddingEnabled()` 返回 true。这是此前所有章节都遗漏的**前置条件**，须在一期骨架第 0 步处理。

**★ 2026-09-07 真机 getprop 实测结果**：

```
[ro.config.miui_activity_embedding_enable]: [true]   ✅ 平行窗口总开关已开
[persist.miui.auto_ui_enable]:             [true]   ✅ autoui 总开关已开
[ro.build.characteristics]:                [nosdcard] ⚠️ 非 "tablet"
```

结论：**两个决定性开关出厂即为 true，第 0 步的 hook 可降级为"读一次做日志确认"，不必强制改写**。仅 `ro.build.characteristics` 需注意 `equals("tablet")` 与 `contains("tablet")` 两种写法在 piano 上均为 false（`miui.os.Build.isTablet()` 用 contains，同样 false），涉及设备类型判定的分支需逐个核对是否落在期望路径上。

### 4.8.5 ★ 发现 3：autoui 应用进程侧执行链（补齐 4.6 章的另一半）

4.6 节只覆盖了 system_server 侧（`miui.autoui.*`）。framework.jar 给出应用进程侧：

```
应用进程                              system_server
─────────────────────────────────────────────────────────
MIUIAutoUIStub                        MiuiSystemAutoUIRule
  ├ preViewMeasure / postViewMeasure    ├ getSystemAutoUIRules(pkg)  ← Bundle
  ├ preViewLayout  / postViewLayout     ├ isAutoUIEnabledForPackage
  ├ preViewDraw    / postViewDraw       └ setAutoUIAppEnable
  ├ getDisplayMetrics(dm, pkg)              ▲
  ├ getDisplayInfoWidth(w, pkg)             │ Binder
  ├ applyConfiguration(cfg, pkg)            │
  └ adjustSizeIfNeeded(Point, pkg)          │
        ▲                                   │
MIUIAutoUIManagerStub ──────────────────────┘
  └ getSystemAutoUIRules(pkg) : Bundle
MiuiAutoUISettingsManager（public static 门面）
  ├ getAutoUIApps() : Map<String,Boolean>
  ├ getSystemAutoUIRules(String) : Bundle
  └ setAutoUIAppEnable(String, boolean, boolean, boolean)
```

关键点：
1. **`MiuiAutoUISettingsManager` 三个方法全部 `public static`**，在应用进程内可直接反射调用，无需 hook —— 用于**验证**云控注入是否生效（读回 `getAutoUIApps()` 看目标包是否出现）。
2. 实现加载：`MiuiAutoUIFrameworkLoader.CLASS_PATH = "/system_ext/framework/miui-framework.autoui.jar"`，收集器 `miui.autoui.MiuiAutoUIFrameworkImplCollector` —— 这是**第 9 个未提取的 jar**（应用进程侧 autoui 实现）。
3. `MIUIAutoUIStub.getDisplayMetrics(DisplayMetrics, String)` / `getDisplayInfoWidth(int, String)` 是应用进程内**直接改写屏幕尺寸**的落点，属于「不改系统规则、只骗单个应用」的备选路线。

### 4.8.6 `ApplicationCompatUtilsStub` 与 `ContinuityManager`（classes.dex）

`android/appcompat/ApplicationCompatUtilsStub.java` —— 4.7.2 节 `isTablet()` / `isDialogContinuityEnabled()` 的定义处，全部为默认返回 false 的 stub，真实实现由 `MiuiStubUtil.getInstance()` 注入（即 `miui-appcompat.jar`）。可 hook 的判定方法：`isTablet()` / `isAppCompatEnabled()` / `isContinuityEnabled()` / `isFullContinuityEnabled()` / `isFoldDeviceOrTabletContinuityEnable()` / `isMiuiOptimization()` / `isMiuiMultiwinEnabled()`。

`android/appcompat/ContinuityManager.java` —— 应用进程侧 Binder 门面（`SERVICE_NAME = "continuity"`），`public` 方法：`getContinuityActivities(String)` / `getContinuityPackages(String)` / `getContinuityVersion()` / `isInterceptActivity(ComponentName)` / `updateContinueUseAfterFoldList(List)` / `updateFlipLauncherIconList(List)`。同 4.7.7 结论，与平板横屏主线弱相关。

清单属性常量：`miui.supportAppContinuity`、`miui.dynamic_dpi_relaunch`（应用 manifest meta-data，可作为伪装适配的注入点）。

### 4.8.7 结论

1. framework.jar **有价值**，但补的是**开关判定 + 应用进程侧 API**，不是规则数据结构；
2. 新暴露 2 个未提取的 jar：`androidx.window.extensions.jar`（平行窗口应用侧）、`miui-framework.autoui.jar`（autoui 应用侧）；
3. 新增关键前置条件：`ro.config.miui_activity_embedding_enable` 与 `persist.miui.auto_ui_enable` 必须为 true，否则所有 hook 无效；
4. `MiuiSettings.SettingsCloudData.CloudData` 与 `BasePackageRule` 系列仍未找到，需补提 `miui-framework.jar`。

---

## 4.9 miui-framework.jar / miui-framework.autoui.jar / androidx.window.extensions.jar 反编译实证（2026-09-07 新增）

三个 jar 已全部反编译，产物：`tmp/jadx-miui-framework/`（877 类）、`tmp/jadx-miui-framework.autoui/`（101 类）、`tmp/jadx-androidx.window.extensions/`。**方案 C 的最后一块拼图到手，全部缺口关闭。**

### 4.9.1 ★ 规则 POJO 完整定义（`miui.autoui.policy.rule.*`）

`BasePackageRule` —— 所有 autoui 规则的基类，`implements Parcelable`，**4 个字段全部是 `String`**：

```java
public class BasePackageRule implements Parcelable {
    public String mPackageName;
    public String mEnable;
    public String mOptimizeWebView;
    public String mVersionCode;

    public BasePackageRule() {}
    public BasePackageRule(String pkg, String enable, String optimizeWebView, String versionCode);

    public boolean isEnable()           { return ParseUtils.parseBoolean(mEnable, false); }
    public boolean needOptimizeWebView(){ return ParseUtils.parseBoolean(mOptimizeWebView, false); }
    public void setEnable(boolean z)    { mEnable = String.valueOf(z); }
}
```

`PackageRule`（对应 `autoui_list.xml` / `cloudFeature_autoui_list.xml`）—— **7 参构造，注意参数顺序与 super 的错位**：

```java
public class PackageRule extends BasePackageRule {
    public String mActivityRule;
    public String mSkippedActivityRule;
    public String mSkippedAppConfigChange;

    // 参数顺序：pkg, enable, activityRule, skippedActivityRule, versionCode, optimizeWebView, skippedAppConfigChange
    public PackageRule(String s1, String s2, String s3, String s4, String s5, String s6, String s7) {
        super(s1, s2, s6, s5);        // ← super 收的是 (pkg, enable, optimizeWebView, versionCode)
        mActivityRule = s3; mSkippedActivityRule = s4; mSkippedAppConfigChange = s7;
    }
}
```

`PackageRule2`（对应 `cloudFeature_autoui2_list.xml`，带 View 级精细规则）：

```java
public class PackageRule2 extends BasePackageRule implements Parcelable {
    private List<ActivityRule> mActivityRules;
    private String mDescribe;
    private String mPackagePolicy;      // "0" = DEFAULT_POLICY

    public PackageRule2();
    public PackageRule2(String pkg, String enable, String optimizeWebView,
                        String describe, List<ActivityRule> rules, String versionCode);

    public static class ActivityRule implements Parcelable {
        private String mActivityName, mActivityPolicy, mEmbeddingExpand;   // ★ mEmbeddingExpand 与平行窗口联动
        private List<ViewRule> mViewRules;
        public ActivityRule(String name, String policy, String embeddingExpand, List<ViewRule> views);
        public ActivityRule(String name, String policy, List<ViewRule> views);   // embeddingExpand = null
        public static boolean isEmbeddingExpandEnabled(String s);   // null / "" → true，否则 Boolean.parseBoolean

        public static class ViewRule implements Parcelable {
            private String mPath, mId, mViewName, mDescribe;
            private List<ViewAttribute> mViewPolicies;
            public ViewRule(String path, String id, String viewName, String describe, List<ViewAttribute> policies);
        }
    }
}
```

> 实测参考构造（取自 `MiuiSystemAutoUIRule.parseRule2Option` L331）：
> `new PackageRule2(pkg, "true", "false", "", new ArrayList(), "")`

### 4.9.2 ★ 云控 XML 的确切 schema

`MiuiParsingAutoUIRule.buildCloudFeatureAutoUiListXml(Map<String, PackageRule>, BufferedOutputStream)` @L39（`protected static`）逐属性写出，**空值属性会被跳过**：

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<autoUIRules>
  <package name="com.example.app"
           enable="true"
           activityRule="..."
           skippedActivityRule="..."
           versionCode="..."
           optimizeWebView="false"
           skippedAppConfigChange="false" />
</autoUIRules>
```

常量：根标签 `CLOUD_TAG_AUTOUI_RULES = "autoUIRules"`，`XML_SETTING_ENABLE = "enable"`。

**注意：云控 XML 根标签上没有 `dataVersion` 属性** —— 这一点直接影响下面 4.9.3 的结论。

### 4.9.3 ★★ 重要修正：4.6.4 的「重启不丢」结论有误

`MiuiParsingAutoUI.parseLocalDataVersion(File)` @L225 只从 **local 文件**（`/product/etc/autoui_list.xml`）的根标签读 `dataVersion` 属性。而 `mLastCloudConfigVersion` 的定义是：

```java
protected static volatile long mLastCloudConfigVersion;
static { mLastCloudConfigVersion = 0; }          // 每次 system_server 启动归零

// L75：真正的赋值来源是云端下发，不是本地文件
String v = MiuiSettings.SettingsCloudData.getCloudDataString(
        cr, CONTROL_MODULE_NAME, "dataVersion", null);
```

因此对方案 C 的准确描述是：

| 项 | 持久性 |
|---|---|
| `/data/system/cloudFeature_autoui_list.xml` 文件本身 | ✅ **持久**，重启保留（权限 0660） |
| `mLastCloudConfigVersion` 内存字段 | ❌ **重启归零**，云端未下发更高版本时恒为 0 |
| 旁路效果（`loadPackage` 走云控分支） | ❌ **重启失效**，会退回读 `/product/etc/autoui_list.xml` |

> **结论修正**：文件可以一次写好长期留着，但**版本字段必须每次开机由模块重新设置**。所以 hook 仍然必需，方案 C 不是"一次性写完就撒手"，而是"开机 hook 设版本 + 复用已落盘的规则文件"。好处依然成立（规则数据不必每次重新构造、走系统官方解析链），但难度评级应从"低"回调到"中低"。

### 4.9.4 ★ 更直接的注入点：`createCloudAutoUIRule` 是 public

`MiuiSystemAutoUIRule` 有两组 map 与两组写入方法，**可见性不同**：

| 方法 | 可见性 | 目标 map | 说明 |
|---|---|---|---|
| `createPackage(PackageRule)` | 包级私有 | `mPackageRules` | 4.6 节记录的生效 map |
| `createPackage2(PackageRule2)` | 包级私有 | `mPackageRules2` | 同上 |
| **`createCloudAutoUIRule(PackageRule)`** | **public** | `mCloudPackageRules` | 暂存区 |
| **`createCloudAutoUIRule(PackageRule2)`** | **public** | `mCloudPackageRules2` | 暂存区 |
| **`updateAutoUIConfigFromCloudFile()`** | **public** | — | 清空并从云控文件重载 |
| **`reloadConfigData()`** | **public** | — | 清空 → `loadSystemConfigData()` → `loadCloudConfigData()` |

`mCloudPackageRules` 是**暂存区不是生效区**，落盘流程为（`lambda$loadCloudConfigData$2` @L160）：

```java
boolean ok = MiuiParsingAutoUI.updateAutoUICloudConfigFile(CLOUD_PACKAGE_CONFIG_FILE_NAME,  mCloudPackageRules)
           | MiuiParsingAutoUI.updateAutoUICloudConfigFile(CLOUD_PACKAGE_CONFIG2_FILE_NAME, mCloudPackageRules2);
mCloudPackageRules.clear();  mCloudPackageRules2.clear();
if (ok) updateAutoUIConfigFromCloudFile();     // 落盘成功后才重新读回生效
```

**由此得到全 public 的注入链（无需任何包级私有访问）**：

```kotlin
// 1. 构造规则并塞入暂存区
rules.forEach { pkg ->
    val rule = PackageRule(pkg, "true", null, null, "1", "false", "false")
    XposedHelpers.callMethod(miuiSystemAutoUIRule, "createCloudAutoUIRule", rule)
}
// 2. 落盘（走系统官方写入链，权限/SELinux 全由系统处理）
XposedHelpers.callStaticMethod(miuiParsingAutoUIClass, "updateAutoUICloudConfigFile",
        "cloudFeature_autoui_list.xml", cloudPackageRulesMap)
// 3. 抬高版本字段（每次开机必做，见 4.9.3）
XposedHelpers.setStaticLongField(miuiParsingAutoUIClass, "mLastCloudConfigVersion", 99999999L)
// 4. 触发重载
XposedHelpers.callMethod(miuiSystemAutoUIRule, "updateAutoUIConfigFromCloudFile")
```

> 注意第 3 步必须早于第 4 步：`updateAutoUIConfigFromCloudFile()` → `loadPackage()` 会比较版本，版本不够就直接去读 `/product/etc/` 了。

### 4.9.5 `MiuiSettings.SettingsCloudData.CloudData` 定义

结构极简，**就是一个 JSON 字符串的惰性解析包装器**，构造函数 `public`：

```java
public static class CloudData implements Parcelable {
    private String data;          // 原始 JSON 字符串
    private JSONObject json;      // 惰性初始化

    public CloudData(String jsonStr);
    public boolean getBoolean(String key, boolean def);
    public int     getInt(String key, int def);
    public long    getLong(String key, long def);
    public String  getString(String key, String def);
    public JSONObject json();
    public String  toString() { return data.toString(); }   // ← 4.6 节里被当作 JSON 源使用
}
```

静态查询入口（`MiuiSettings.SettingsCloudData`，全部 `public static`）：`getCloudDataSingle(cr, module, key, arg, boolean)` / `getCloudDataList(cr, module)` / `getCloudDataString|Int|Long|Boolean(...)` / `getCloudDataNotifyUri()`。

Provider URI：
```
content://com.android.settings.cloud.CloudSettings/cloud_all_data
content://com.android.settings.cloud.CloudSettings/cloud_all_data/single
```

> 这意味着**还有一条更上游的路线**：hook `getCloudDataString/getCloudDataSingle`，在 `CONTROL_MODULE_NAME = "autoui_application_config"` 模块上伪造 `dataVersion` 返回值，可让系统自己把 `mLastCloudConfigVersion` 抬高（走 L75 的正常赋值路径），比直接 `setStaticLongField` 更贴近原生行为。

### 4.9.6 miui-framework.autoui.jar：应用进程侧执行链（101 类）

4.8.5 推断的加载路径已证实。类结构：

```
miui.autoui.MIUIAutoUIImpl              ← MIUIAutoUIStub 的真实实现
miui.autoui.MIUIAutoUIManagerImpl       ← MIUIAutoUIManagerStub 的真实实现
miui.autoui.MiuiAutoUIFrameworkImplCollector   ← 4.8.5 中 Loader 指定的收集器
miui.autoui.PolicyBinder / ViewExtras / AutoPolicyUtils / RangeType
miui.autoui.policy.AutoUIPolicyFactory  ← 按规则分派 Policy
miui.autoui.policy.{activity,view,fsview,padactivity,padview,padtab,padfsview,
                    roundcornerfsview,def,autodpi}.*      ← 10 组 Policy 实现
miui.autoui.policy.rule.adapter.*       ← 15 种 ViewPolicyRule
miui.autoui.utils.FSExtClassLoader      ← 外挂 JS/扩展规则加载器（呼应 4.6.6）
```

`policy.rule.adapter` 下的 15 种规则适配器，即 `PackageRule2.ViewRule.mViewPolicies` 中 `ViewAttribute` 的可用类型：

`SizeViewPolicyRule` / `ScaleViewPolicyRule` / `ClampScaleViewPolicyRule` / `MarginViewPolicyRule` / `PaddingViewPolicyRule` / `WeightViewPolicyRule` / `VisibleViewPolicyRule` / `ColumnViewPolicyRule` / `BannerViewPolicyRule` / `BlurViewPolicyRule` / `ScaleTypeViewPolicyRule` / `LayoutSlidingPolicyRule` / `BiliSizeViewPolicyRule`（哔哩哔哩专用硬编码规则）/ `BaseViewPolicyRule` / `ViewPolicyRule`。

> `BiliSizeViewPolicyRule` 的存在说明小米自己也在为单个 App 写死适配规则 —— 与 sothx 规则库的做法同源。

`policy` 下的 `padactivity` / `padview` / `padtab` / `padfsview` 四组是**平板专用**策略（piano 命中），`autodpi.AutoDpiPolicy` 对应 4.8.6 中的 `miui.dynamic_dpi_relaunch` 清单属性。

### 4.9.7 androidx.window.extensions.jar：平行窗口应用侧

```
com.miui.window.MiuiEmbeddingWindow          ← 4.8.3 中 STUB_IMPL 常量指向的类，已证实
com.miui.window.MiuiEmbeddingWindowImpl
com.miui.window.SplitRuleUtils                ← 分栏规则工具
com.miui.window.anim.MiuiEmbeddingWindowDimmer / MiuiEmbeddingAnimationSpec
                    / DimmerAnimationUtils / PhysicBasedInterpolator
```

`MiuiEmbeddingWindow` 即 4.8.3 表中所有 `MiuiEmbeddingWindowStub` 接口方法（`overrideDisplayRotation` / `sandboxDisplayInfo` / `shouldIgnoreOrientationModify` / `onActivitySetRequestedOrientation` / `initSystemRules` 等）的落地实现，在**应用进程内**可 hook。`SplitRuleUtils` 是分栏比例与边界计算处。

### 4.9.8 结论

1. **所有 jar 缺口已关闭**，方案 C 从构造对象 → 落盘 → 重载的每一步都有 public API，无包级私有障碍；
2. **但 4.6.4 的"重启不丢"结论需修正**（4.9.3）：文件持久、版本字段不持久，开机必须重新 hook；
3. 新增一条更优雅的版本抬高路线：hook `MiuiSettings.SettingsCloudData.getCloudDataString`（4.9.5）；
4. `PackageRule2.ActivityRule.mEmbeddingExpand` 字段是 autoui 与平行窗口的联动开关，与 4.6.5 中 `MiuiSystemAutoUIRule.getSystemAutoUIRules` 里的 `isEmbeddingEnabledForPackage` 呼应；
5. 应用进程侧两条链（`MIUIAutoUIImpl` / `MiuiEmbeddingWindow`）已定位，作为 system_server 侧方案失败时的备选。

---

## 4.10 真机规则文件实证（2026-09-07 新增，piano 实机提取）

四个规则文件已从真机提取到 `tmp/平板/`，这是最后一块缺口。**实样与前述反编译推断整体吻合，但暴露出三处此前推断错误**。

### 4.10.1 文件总览

| 文件 | 来源路径 | 大小 | 条目数 | 根标签 | 条目标签 |
|---|---|---|---|---|---|
| `autoui_list.xml` | `/product/etc/` | 21 KB | **186** | `packageRules` | `package` |
| `embedded_rules_list.xml` | `/product/etc/` | 1.30 MB | **8047** | `package_config` | `package` |
| `fixed_orientation_list.xml` | `/product/etc/` | 461 KB | **3890** | `package_config` | `package` |
| `embedded_setting_config.xml` | `/data/system/users/0/` | 616 KB | **8411** | `setting_rule` | `setting` |

> 8047 条平行窗口规则印证了 sothx 文档"8000+ 应用规则"的说法 —— **系统内置规则量已与 sothx 模块处于同一量级**，这直接影响一期目标定位（见 4.10.6）。

### 4.10.2 ★ 修正 1：根标签与反编译常量不一致，但不影响解析

| 文件 | 反编译常量 | 实样根标签 | 是否冲突 |
|---|---|---|---|
| `autoui_list.xml`（local） | `CLOUD_TAG_AUTOUI_RULES = "autoUIRules"` | **`packageRules`** | 否 |
| `embedded_rules_list.xml`（local） | `XML_TAG_PACKAGE_RULES = "packageRules"` | **`package_config`** | 否 |

原因已查明：`MiuiParsingAutoUI` 的 XML 解析器**只按元素名 `XML_ELEMENT_PACKAGE = "package"` 匹配，从不校验根标签**（`parseLocalPackageXml` @L340、`parsePackageXml` @L283）。`autoUIRules` / `autoUIRules2` 这两个常量只在 `parseAutoUICloudConfigRules` 中作为**JSON 对象的 key** 使用（@L173），不是 XML 标签。

**实践含义**：自造云控 XML 时根标签可任意（沿用 `packageRules` 最安全），只要 `<package>` 元素属性正确即可被解析。4.9.2 中给出的 `<autoUIRules>` 根标签写法虽然也能工作，但与系统 `buildCloudFeatureAutoUiListXml` 的实际输出保持一致更稳妥。

### 4.10.3 ★★ 修正 2：`dataVersion` 实测值 = 360816（方案 C 的硬门槛）

```xml
<packageRules dataVersion="360816">
```

这是 4.9.3 所述 `parseLocalDataVersion` 读取的目标值。**方案 C 中 `mLastCloudConfigVersion` 必须 ≥ 360816 才能旁路本地文件**，此前骨架代码里写的 `99999999L` 满足条件，但现在有了确切基准：

```kotlin
// 实测 /product/etc/autoui_list.xml dataVersion = 360816
// 取一个明显更大且不易与未来 OTA 撞车的值
XposedHelpers.setStaticLongField(parsingAutoUIClass, "mLastCloudConfigVersion", 99999999L)
```

注意 `embedded_rules_list.xml` / `fixed_orientation_list.xml` 的根标签 `package_config` **不带 `dataVersion` 属性**，说明平行窗口侧的版本判定机制与 autoui 不同，4.5.6 的云控方案需单独验证。

### 4.10.4 属性字典（实测频次，可直接作为规则生成的字段白名单）

**`embedded_rules_list.xml`（平行窗口，8047 条）**

| 属性 | 出现次数 | 覆盖率 | 说明 |
|---|---|---|---|
| `name` | 8047 | 100% | 包名 |
| **`skipSelfAdaptive`** | **8047** | **100%** | 恒为 `true`，**必带属性** |
| `supportFullSize` | 6631 | 82% | 支持全屏尺寸 |
| `isShowDivider` | 5612 | 70% | 显示分割线 |
| `supportCameraPreview` | 3306 | 41% | 支持 `*:false` 语法按 Activity 细分 |
| `splitPairRule` | 2041 | 25% | 分屏配对：`ActA:ActB,ActC:*` |
| `scaleMode` | 1172 | 15% | 缩放模式，取值如 `1` |
| `fullRule` | 1065 | 13% | 取值 `*` / `nra` / `nra:cr:rcr` 组合 |
| `defaultSettings` | 529 | 7% | 默认档位 |
| `activityRule` | 481 | 6% | 参与分屏的 Activity |
| `transitionRules` | 445 | 6% | 过渡例外 Activity 列表（逗号分隔） |
| `splitRatio` | 304 | 4% | 分屏比例，如 `0.35` |
| `placeholder` | 184 | 2% | 占位页：`MainActivity:SearchActivity` |
| `relaunch` | 152 | 2% | 切换时是否重启 |
| `splitLineColor` | 53 | — | 如 `#E6E6E6:#323232` |
| `flags` | 49 | — | 如 `forceResumeOnFocus:com.x.MainActivity` |
| `forcePortraitActivity` | 38 | — | 强制竖屏的 Activity |
| `middleRule` | 36 | — | 取值 `*` |
| `procCompat` | 35 | — | 进程级兼容 |
| **`autoUiRule`** | **33** | — | ★ 平行窗口内联 autoui 规则，证实两套机制联动 |
| `clearTop` | 20 | — | — |
| `finishSecondaryWithPrimary` | 10 | — | — |
| `minSupportVersion` | 6 | — | 语法 `1:20230510` |
| `splitMinWidth` | 5 | — | — |
| `sizecompatRule` / `adaptCutout` / `skipCompatMode` | 各 1 | — | 长尾 |

示例：

```xml
<package name="com.zjy.ykt" clearTop="true" isShowDivider="true" supportFullSize="true"
         splitRatio="0.35" activityRule="com.zjy.ykt.MainActivity"
         splitPairRule="com.zjy.ykt.MainActivity:*,com.ykt.app_zjy.app.classes.detail.StudentClassDetailActivity:*"
         skipSelfAdaptive="true"/>
<package name="com.didapinche.booking" scaleMode="1" fullRule="nra:cr:rcr" skipSelfAdaptive="true"/>
<package name="com.meishubao.artaiclass" isShowDivider="true" supportFullSize="true"
         autoUiRule="com.meishubao.artaiclass.SplashActivity:-1;com.meishubao.main.ui.app.MainActivity:-1"
         skipSelfAdaptive="true"/>
```

**`fixed_orientation_list.xml`（固定横屏/信箱，3890 条）**

| 属性 | 次数 | 实测取值 |
|---|---|---|
| `name` | 3890 | 包名 |
| `skipSelfAdaptive` | 3697 | `true` |
| **`supportModes`** | 3694 | **唯一取值 `full,fo`** |
| `defaultSettings` | 1527 | `full`(1065) / `fo`(462) |
| `relaunch` | 794 | — |
| `supportFullSize` | 693 | — |
| `supportCameraPreview` | 610 | `false` 或 `*:false` |
| `compatChange` | 249 | 见下 |
| `disable` | 197 | `true` = 该应用禁用此机制 |
| `isScale` | 125 | — |
| `skipCompatMode` | 74 | — |
| `forcePortraitActivity` | 52 | — |
| `allowEmbInPortrait` | 9 | 竖屏下允许平行窗口 |
| `fullForcePortraitActivity` | 6 | — |
| `forceKillWhenSwitch` | 2 | — |

`compatChange` 取值分布（映射 AOSP `PackageManager` 兼容性开关）：

```
OVERRIDE_MIN_ASPECT_RATIO,OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN,OVERRIDE_MIN_ASPECT_RATIO_MEDIUM  ×195
OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT                                                                        ×19
OVERRIDE_MIN_ASPECT_RATIO,...,OVERRIDE_MIN_ASPECT_RATIO_LARGE                                                     ×18
FORCE_RESIZE_APP                                                                                                  ×9
```

> `full` = 全屏拉伸，`fo` = fixed orientation（信箱模式）。`supportModes="full,fo"` 表示两种都支持，由 `defaultSettings` 指定默认档，用户在设置中可切换 —— 这与 4.5.5 的互斥优先级链吻合。

### 4.10.5 ★ 修正 3：`embedded_setting_config.xml` 是用户态开关，只有 4 个属性

此文件在 `/data/system/users/0/`，**8411 条**，是 4.5.2 中 `SETTING_CONFIG_FILE_NAME` 的实体。属性极简：

| 属性 | 次数 | 语义 |
|---|---|---|
| `name` | 8411 | 包名 |
| `embeddedEnable` | 6902 | 平行窗口开关 |
| `fixedOrientationEnable` | 2622 | 固定横屏开关 |
| `fullScreenEnable` | 1054 | 全屏拉伸开关 |
| `ratio_fullScreenEnable` | 1054 | 比例全屏开关（与上者恒同现） |

属性组合分布（**印证 4.5.5 的三套机制互斥**）：

```
<setting name embeddedEnable/>                              ×4730   仅平行窗口
<setting name embeddedEnable fixedOrientationEnable/>       ×2162   两者并存（一开一关）
<setting name fullScreenEnable ratio_fullScreenEnable/>     ×1044   仅全屏（恒成对出现）
<setting name fixedOrientationEnable/>                      ×460    仅固定横屏
<setting name embeddedEnable fullScreenEnable ratio_.../>   ×10     罕见三者并存
<setting name/>                                             ×5      空配置
```

样本：

```xml
<setting name="com.kkptech.kkpsy" embeddedEnable="true" fixedOrientationEnable="false"/>
<setting name="com.cubic.autohome" fullScreenEnable="true" ratio_fullScreenEnable="true"/>
<setting name="com.fy.cjyxlm.mi" fixedOrientationEnable="true"/>
```

**关键结论**：`fullScreenEnable` 与 `ratio_fullScreenEnable` 出现次数完全相等（1054/1054），说明二者由系统成对写入，模块修改时**必须同时设置**，否则可能落入未定义状态。

### 4.10.6 ★ 对项目定位的实质影响

系统内置已有 **8047 条平行窗口 + 3890 条固定横屏**规则，与 sothx 模块的规则量级相当。这意味着：

1. **"导入 sothx 8000+ 规则"的收益被高估** —— 大部分主流应用系统已内置。原计划的"批量导入"应改为**差集导入**：先 diff sothx 规则集与真机 `embedded_rules_list.xml`，只注入系统未覆盖的部分；
2. **真正的价值点转移到用户态** —— `embedded_setting_config.xml` 中 `embeddedEnable=false` 的应用（8411 条中约 1500 条为 false 或缺失），以及系统标了 `disable="true"` 的 197 条固定横屏规则，才是"完美横屏"要突破的对象；
3. **一期目标应重新定为**：批量把 `embedded_setting_config.xml` 中的开关翻成 true + 覆盖系统 `disable` 标记，而非灌入海量新规则。这比方案 C 的云控注入更直接，且该文件在 `/data/system/users/0/` 下，**system_server 域内可直接读写**；
4. autoui 侧仍是新规则注入的主战场 —— 仅 **186 条**内置规则，覆盖率远低于另两套，方案 C 在这里价值最大。

### 4.10.7 结论

1. 三处推断修正已闭环：根标签不校验（4.10.2）、`dataVersion=360816`（4.10.3）、setting 文件仅 4 属性（4.10.5）；
2. 完整属性字典已建立，可直接作为规则生成器的字段白名单；
3. **项目定位需调整**：从"导入规则"转向"翻开关 + 差集补规则"（4.10.6）；
4. 至此**所有缺口关闭**，jar 侧 10 个、真机侧 4 个文件 + 3 个 getprop 全部到位，可进入编码阶段。

---

## 5. 缺口清单与提取命令

### 5.1 缺口现状（2026-09-07 更新）

| 项目 | 状态 | 备注 |
|---|---|---|
| `miui-embedding-window.jar` | ✅ 已获取并反编译 | 21 个类，hook 点定案（4.5 节） |
| `miui-appcompat.jar` | ✅ 已获取并反编译 | 含 `ApplicationCompatPolicy` / `ApplicationCompatManager`，hook 点定案（4.7 节） |
| `miui-appcompat.appcontinuity.jar` | ✅ 已反编译 | 16 个类，与横屏/方向关联弱 |
| `miui-services.autoui.jar` | ✅ 已获取并反编译 | 含 `MiuiParsingAutoUI` 云控写入 API，3 档 hook 点+云控首选方案（4.6 节） |
| `miui-services.hovermode.jar` | ✅ 已反编译 | 10 个类，悬停模式，非必需 |
| `miui-services.thirdappopt.jar` | ✅ 已反编译 | 5 个类，三方应用优化，非必需 |
| `miui-services.hyperviewscale.jar` | ✅ 已反编译 | 3 个类，视图缩放，非必需 |
| `framework.jar` | ✅ 已获取并反编译 | classes6 + classes 已解析，`MiuiEmbeddingWindowStub` / 总开关属性 / autoui 应用侧定案（4.8 节）；但**不含** CloudData 与 PackageRule POJO |
| ~~`mediatek-services.jar`~~ | ✅ **不适用，已从缺口移除** | piano 为骁龙平台，该文件不存在（dex 引用是通用代码的平台分支） |
| `miui-framework.jar` | ✅ **已获取并反编译** | 877 类。`BasePackageRule`/`PackageRule`/`PackageRule2`/`CloudData` 全部定案（4.9.1 / 4.9.5） |
| `androidx.window.extensions.jar` | ✅ **已获取并反编译** | `com.miui.window.MiuiEmbeddingWindow` 已证实（4.9.7） |
| `miui-framework.autoui.jar` | ✅ **已获取并反编译** | 101 类，autoui 应用进程侧执行链 + 15 种 ViewPolicyRule（4.9.6） |
| `/product/etc/embedded_rules_list.xml` | ✅ **已获取并分析（4.10）** | 8047 条，根标签 `<package_config>`，无 dataVersion |
| `/product/etc/fixed_orientation_list.xml` | ✅ **已获取并分析（4.10）** | 3890 条，根标签 `<package_config>`，`supportModes` 恒为 `full,fo`，197 条 `disable="true"` |
| `/product/etc/autoui_list.xml` | ✅ **已获取并分析（4.10）** | 186 条，根标签 `<packageRules dataVersion="360816">` |
| `/data/system/users/0/embedded_setting_config.xml` | ✅ **已获取并分析（4.10）** | 8411 条 `<setting>`，仅 4 个属性 |
| `getprop` 三个总开关 | ✅ **已真机实测（4.8.4）** | `miui_activity_embedding_enable=true`、`persist.miui.auto_ui_enable=true`、`ro.build.characteristics=nosdcard` |

> **✅ 所有缺口已关闭。** 剩余待办不再是"取资料"，而是"写代码 + 真机验证"（见第 8 节）。

### 5.2 提取命令（存档，第 1~3、5 项已完成）

设备已刷 LSPosed 必然有 root，直接 adb 提取：

```bash
# 1. 插件 jar —— ✅ 已全部到齐（10 个），无需再提取

# 2. 系统内置规则样本 —— ✅ 已完成，见 4.10
adb shell su -c "cat /product/etc/embedded_rules_list.xml"      > embedded_rules_list.xml
adb shell su -c "cat /product/etc/fixed_orientation_list.xml"   > fixed_orientation_list.xml
adb shell su -c "cat /product/etc/autoui_list.xml"              > autoui_list.xml

# 3. 用户开关存储现状 —— ✅ 已完成，见 4.10.5
adb shell su -c "cat /data/system/users/0/embedded_setting_config.xml"

# 4. 云控规则现状（尚未取；模块运行后用于自检写入是否落盘）
adb shell su -c "cat /data/system/cloudFeature_embedded_rules_list.xml"
adb shell su -c "cat /data/system/cloudFeature_autoui_list.xml"
adb shell su -c "cat /data/system/cloudFeature_autoui2_list.xml"

# 4b. appcompat 本地配置（⚠️ 路径取决于 ApplicationCompatUtilsStub.isTablet()，
#     piano 的 ro.build.characteristics=nosdcard → isTablet() 为 false → 实际应在 /product/etc/）
adb shell su -c "cat /product/etc/continuity_list.json"     > continuity_list.json
adb shell su -c "cat /product/etc/new_list_2.json"          > new_list_2.json
adb shell su -c "ls /system_ext/etc/ | grep -E 'continuity|new_list'"   # 两处都查一下

# 5. ROM 版本信息 + 总开关属性 —— ✅ 已实测（2026-09-07）
adb shell getprop ro.build.version.incremental
adb shell getprop ro.config.miui_activity_embedding_enable   # 实测 = true  ✅
adb shell getprop persist.miui.auto_ui_enable                # 实测 = true  ✅
adb shell getprop ro.build.characteristics                   # 实测 = nosdcard（非 tablet；不 gate 平行窗口，勿强改）

# 5b. 待补查：IS_TABLET 的 fallback 分支
adb shell getprop ro.config.tablet                  # 若为 true，则 MiuiEmbeddingWindowImpl.IS_TABLET 仍为 true
adb shell getprop persist.sys.muiltdisplay_type     # ==2 时 WindowExtensionsImpl.IS_RUNNING_NOT_PHONE 为 true

# 6. autoui 外挂 JS 扩展口现状（4.6.6 节）
adb shell getprop persist.sys.miui_autoui_js_rule
adb shell pm list packages | grep autoui.ext
```

真机文件已存放于 `d:\lsp\tmp\平板\`（autoui_list.xml / embedded_rules_list.xml / embedded_setting_config.xml / fixed_orientation_list.xml）。

另外需要 **规则数据来源**：sothx 仓库 `module_src/common/source/*.xml`（8000+ 应用规则）。注意其许可协议：**允许个人非商用，禁止任何商业用途**。⚠️ 按 4.10.6，取用前须先与真机内置规则做 diff，只保留差集。

---

## 6. LSPosed 实现路径（基于实测签名，2026-09-07 定案）

### 6.1 总体架构

```
┌─ 前置层 ── 总开关（4.8.4）：ro.config.miui_activity_embedding_enable=true ✅
│            persist.miui.auto_ui_enable=true ✅（真机实测均已开，仅需校验）
│            ro.build.characteristics=nosdcard（非 tablet，属出厂正常，勿强改）
│
├─ 规则数据层 ── ★ 按 4.10.6 重定位：系统已内置 8047 条平行窗口 + 3890 条固定横屏，
│                 sothx 规则集需先 diff 取差集，不做全量导入；
│                 autoui 仅 186 条，是真正的补规则重点
│                 数据结构对齐三套机制的 schema（POJO 见 4.9.1，属性字典见 4.10.4）
│
├─ 入口层(system_server) ── hook MiuiEmbeddingWindowServiceLoader 或
│                            MiuiEmbeddingWindowServiceImplCollector
│                            → 捕获插件 ClassLoader（关键：包级私有类必须靠它）
│                            注：PackageRule 等 POJO 在 miui-framework.jar，
│                                属 boot classpath，用 system_server CL 即可
│
├─ 注入层 ── 按机制选方案（详见 6.2）
│   ├─ 固定横屏/平行窗口 → 4.5.3 的三档方案，推荐 C（云控版）
│   ├─ 布局优化(autoui)  → 4.6.3 云控文件写入 API，首选 C
│   │                       ★ 每次开机需重抬 mLastCloudConfigVersion（4.9.3）
│   └─ 应用兼容(appcompat) → 4.7.4 静态 List 直改（B 档）
│
├─ 开关层 ── /data/system/users/<uid>/embedded_setting_config.xml
│            （system_server 域内可写）★ 真机 8411 条，仅 4 个属性（4.10.5）
│            —— 一期收益最高的一层：绝大多数应用规则已内置，只是开关没打开
│            autoui 开关：/data/system/users/<uid>/autoui_setting_config.xml
│
└─ 兜底层 ── services.jar AOSP 侧：ActivityStarter.executeRequest
             + PackageConfigPersister（兼容缩放状态持久化）
```

> 注：本模块只作用于 **system_server 进程**。应用进程侧的执行链（`miui-framework.autoui.jar` 的 `MIUIAutoUIImpl` / `androidx.window.extensions.jar` 的 `MiuiEmbeddingWindowImpl`）是另一套代码，规则由 system_server 下发，无需也不应在应用进程重复注入（4.9.6 / 4.9.7）。

### 6.2 三套机制注入方案对比（2026-09-07 更新）

**embedding（平行窗口/固定横屏）**

| | 方案 A：改查询返回值 | 方案 B：直改内存表 | 方案 C：云控通道 |
|---|---|---|---|
| **落点** | `MiuiEmbeddingWindowService.isEmbeddingListedForPackage` (L4662)<br>`.isFixedOrientationListedForPackage` (L4726) | `MiuiFixedOrientationController.getFixOrientationRules()` (L207)<br>+ 反射 `MiuiSystemEmbeddedRule.mPackageRules` | hook `parseCloudPackageConfig` 或写 `/data/system/cloudFeature_embedded_rules_list.xml` |
| **实现难度** | 低 | 中（需构造 `PackageRule`/`FixedOrientationRule` 实例） | 中高（需逆向 CloudData JSON） |
| **效果完整度** | 中 | **高** | **高** |
| **绕过懒加载** | ✅ 天然绕过 | ⚠️ 需处理被覆盖 | ✅ 走系统加载链 |
| **兼容性** | 中（签名跨版本可能漂移） | 中低（字段名依赖强） | **高**（走官方数据通路） |
| **建议** | 一期验证链路 | 二期主力 | **三期终态（与 autoui 共用云控框架）** |

**autoui（布局优化）—— 首选方案 C（云控文件写入）**

| | 方案 A：改查询返回值 | 方案 B：直改内存表 | ★ 方案 C：云控文件写入 |
|---|---|---|---|
| **落点** | `MiuiSystemAutoUIRule.getSystemAutoUIRules` (L254) | 反射 `mPackageRules`/`mPackageRules2` + `getSettingRules()`<br>或 public 的 `createCloudAutoUIRule(PackageRule)` (L180) | **`MiuiParsingAutoUI.updateAutoUICloudConfigFile`** (L368, public static) |
| **实现难度** | 低 | 中 | **中低**（写入 API 全 public，但每次开机需重抬版本字段，见 4.9.3） |
| **效果完整度** | 中（只改返回，不持久化） | 高（写内存表，重启需重注） | **高**（XML 文件持久化，系统加载链完整；但版本字段不持久，开机需重新 hook） |
| **绕过懒加载** | ✅ 天然绕过 | ⚠️ 需处理被覆盖 | ✅ 走系统加载链 |
| **兼容性** | 中 | 中低 | **高**（走官方 `FileUtils.sync` + `setPermissions`，权限 SELinux 全部由系统处理） |
| **注入链可见性** | public | `createCloudAutoUIRule` public<br>（`createPackage`/`createPackage2` 包级私有） | **全链路 public**：`PackageRule`(7 参) → `updateAutoUICloudConfigFile` → `setStaticLongField` → `updateAutoUIConfigFromCloudFile`（4.9.4） |
| **建议** | 不推荐 | 备选 | **首选** |

> **方案 C 的两条硬约束**：
> 1. **版本字段不持久（4.9.3）**：`mLastCloudConfigVersion` 是 `protected static volatile long`，static 块初始化为 0，云控 XML 根标签不含 `dataVersion` 属性，`parseLocalDataVersion` 只读 local 文件。因此**文件持久、版本字段不持久**，每次 system_server 启动都必须重新抬高该字段，否则 `loadPackage` 会退回读 `/product/etc/autoui_list.xml`。
> 2. **★ 版本门槛实测 = 360816（4.10.3）**：真机 `/product/etc/autoui_list.xml` 根标签为 `<packageRules dataVersion="360816">`，抬高值必须 **> 360816**，随便填个小数（如 `1`）会直接失效。建议直接给 `Long.MAX_VALUE / 2` 或 `99999999L`。
>
> 更贴近原生的替代做法：hook `MiuiSettings.SettingsCloudData.getCloudDataString`，在 `module == "autoui_application_config" && key == "dataVersion"` 时返回一个大数，让系统自己在正常路径中给该字段赋值（4.9.5）。

**appcompat（应用兼容）—— 方案 B 即可**

| 落点 | 说明 |
|---|---|
| `IApplicationCompat.CONTINUITY_FOLD_OUTER_PORTRAIT_ORIENTATION_LIST` | `public static final List`，`get(null).add()` 即可 |
| `IApplicationCompat.CONTINUITY_FOLD_OUTER_PORTRAIT_ORIENTATION_EXCLUDE_LIST` | 同上，排除名单 |
| `ApplicationCompatManager.getDisplayChangeAbility(Task)` (L360) | 尺寸兼容判定，需要时 hook after |

### 6.3 推荐的一期实现骨架

> **★ 一期优先级已按 4.10.6 重排**：真机内置规则已相当完整（embedding 8047 / fixedOrientation 3890），一期不做规则大批量导入。落地顺序为
> **第 0 步（校验总开关） → 第一步（捕获 ClassLoader） → 第四步（批量翻用户开关，收益最高） → 第三步（A 档 hook 兜底） → 第二步（autoui 云控注入，覆盖率最低的一档）**。
> 下文小节仍按原编号排列，实施时按上述顺序取用。

#### 第 0 步：总开关确认（4.8.4，真机实测已为 true，降级为校验）

真机 getprop 实测：`ro.config.miui_activity_embedding_enable = true`、`persist.miui.auto_ui_enable = true`，**两个决定性开关出厂即开，无需改写**。第 0 步只需读一次做日志确认，并保留改写代码作为其他机型/刷机后的兜底：

```kotlin
// 校验（正常路径）：两个开关 piano 出厂即为 true
val aeOn = SystemProperties.getBoolean("ro.config.miui_activity_embedding_enable", false)
val autoUiOn = SystemProperties.getBoolean("persist.miui.auto_ui_enable", false)
XposedBridge.log("[MW] AE=$aeOn autoUI=$autoUiOn char=${SystemProperties.get("ro.build.characteristics")}")

// 兜底（仅当上面为 false 时才需要）：ro. 属性无法 setprop，只能 hook
if (!aeOn) {
    XposedHelpers.findAndHookMethod(
        "miui.thirdappadaptation.MiuiAppAdaptationProperties\$ActivityEmbeddingProp",
        systemServerClassLoader, "isActivityEmbeddingEnabled",
        XC_MethodReplacement.returnConstant(true))
}
// autoui 总开关是 persist. 属性，可直接持久化：
// adb shell su -c "setprop persist.miui.auto_ui_enable true"
```

> ⚠️ **不要盲目改写 `IS_TABLET`**：piano 的 `ro.build.characteristics = nosdcard`，`MiuiAppAdaptationProperties.IS_TABLET` 因此为 false，但这是 piano 的**出厂正常状态**，横屏功能在此状态下本就正常工作。强行 `setStaticBooleanField(propsClass, "IS_TABLET", true)` 会让系统走上从未被测试过的折叠屏/平板分支，风险大于收益。仅在确认某个具体判定因它取错分支时再定点改写。

#### 第一步：捕获插件 ClassLoader

```kotlin
// hook MiuiEmbeddingWindowServiceLoader 的加载方法，捕获 ClassLoader
XposedHelpers.findAndHookMethod(
    "com.android.server.wm.MiuiEmbeddingWindowServiceLoader",
    systemServerClassLoader, "onLoad", /* 参数名待确认 */
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val service = param.thisObject
            pluginClassLoader = service.javaClass.classLoader
        }
    })
```

#### 第二步：autoui 云控注入（首选方案 C，已按 4.9 节实证修正）

```kotlin
val parsingAutoUIClass = XposedHelpers.findClass(
    "miui.autoui.MiuiParsingAutoUI", pluginClassLoader)
// PackageRule 在 miui-framework.jar 中，属于 boot classpath，用 system_server 的 CL 即可
val packageRuleClass = XposedHelpers.findClass(
    "miui.autoui.policy.rule.PackageRule", systemServerClassLoader)

// 1. 构造规则 Map<String, PackageRule>
//    7 参构造：pkg, enable, activityRule, skippedActivityRule,
//              versionCode, optimizeWebView, skippedAppConfigChange
val rulesMap = HashMap<String, Any>()
targetPackages.forEach { pkg ->
    rulesMap[pkg] = XposedHelpers.newInstance(packageRuleClass,
        pkg, "true", null, null, "1", "false", "false")
}

// 2. 落盘：走系统官方写入链，权限/SELinux/fsync 全由系统处理
//    写出 /data/system/cloudFeature_autoui_list.xml（0660）
XposedHelpers.callStaticMethod(parsingAutoUIClass,
    "updateAutoUICloudConfigFile",
    "cloudFeature_autoui_list.xml", rulesMap)

// 3. ★ 抬高版本字段 —— 必须早于第 4 步，且每次开机都要做（4.9.3）
//    字段类型是 long，不是 int
//    ★ 门槛实测：/product/etc/autoui_list.xml 的 dataVersion = 360816（4.10.3）
//       赋值必须 > 360816，否则 loadPackage 仍会退回读 local 文件
XposedHelpers.setStaticLongField(parsingAutoUIClass,
    "mLastCloudConfigVersion", 99999999L)

// 4. 触发重载：用 updateAutoUIConfigFromCloudFile 而非 reloadConfigData
//    后者会先 loadSystemConfigData() 再 loadCloudConfigData()，多绕一圈且可能触发云端查询
XposedHelpers.callMethod(autoUIRuleInstance, "updateAutoUIConfigFromCloudFile")
```

> **两处易错点**（均已在 4.9 节实证）：
> 1. `mLastCloudConfigVersion` 是 `protected static volatile long`，static 块初始化为 0，**每次 system_server 重启归零** —— 第 3 步不是一次性操作，必须每次开机执行；
> 2. 若图省事，可跳过第 1、2 步直接复用上次已落盘的 XML，只做第 3、4 步即可恢复生效。
>
> **更贴近原生的替代做法**（4.9.5）：hook `MiuiSettings.SettingsCloudData.getCloudDataString`，当 `module == "autoui_application_config"` 且 `key == "dataVersion"` 时返回一个大数，让系统自己走 L75 正常路径把版本写进去，避免直接改私有静态字段。

#### 第三步：平行窗口/固定横屏查询 hook（A 档兜底）

```kotlin
val ewsClass = XposedHelpers.findClass(
    "com.android.server.wm.MiuiEmbeddingWindowService", pluginClassLoader)

XposedHelpers.findAndHookMethod(ewsClass, "isEmbeddingListedForPackage",
    String::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val pkg = param.args[0] as String
            if (RuleStore.hasEmbeddingRule(pkg)) param.result = true
        }
    })

XposedHelpers.findAndHookMethod(ewsClass, "isFixedOrientationListedForPackage",
    String::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val pkg = param.args[0] as String
            if (RuleStore.hasFixedOrientationRule(pkg)) param.result = true
        }
    })
```

#### 第四步：批量翻用户开关（★ 一期收益最高，依据 4.10.5 / 4.10.6）

真机 `/data/system/users/0/embedded_setting_config.xml` 已有 **8411 条** `<setting>`，说明规则侧基本齐备，缺的只是"开关没打开"。该文件位于 `system_data_file`，system_server 域内可写，**不需要云控通道**。

```kotlin
// 元素名 <setting>，仅 4 个属性（4.10.5）：
//   embeddedEnable / fixedOrientationEnable / fullScreenEnable / ratio_fullScreenEnable
// ⚠️ fullScreenEnable 与 ratio_fullScreenEnable 在真机上恒等（各 1054 次），必须成对写
// ⚠️ embeddedEnable 与 fixedOrientationEnable 互斥（4.5.5），二选一

// 落点：hook MiuiParsingEmbeddedRule 的 setting 写入/解析方法，
//       在解析完成后按模块规则表覆盖对应条目的属性值，再触发一次持久化。
//       避免直接改文件——系统内存中已有一份，直接改文件会被下次写回覆盖。
```

同时需要处理 `fixed_orientation_list.xml` 中的 **197 条 `disable="true"`**：这些是被小米主动禁用的应用，翻开关无效，必须走 A 档 hook（第三步）覆盖 `isFixedOrientationListedForPackage` 的返回值。

### 6.4 关键注意事项

1. **必须用插件 ClassLoader**：`MiuiSystemEmbeddedRule` / `MiuiSystemAutoUIRule` 等类为包级私有且由 Loader 动态加载，`system_server` 的默认 ClassLoader 中**找不到**这些类；
2. **互斥约束**：固定横屏与平行窗口互斥（见 4.5.5），模块 UI 必须做单选；autoui 的 enable 与平行窗口通过 `isEmbeddingEnabledForPackage` 联动（见 4.6.5），不需额外处理；
3. **懒加载**：方案 B/C 需处理 `parseSinglePackage` 触发后自定义规则被系统数据覆盖的问题，可同时 hook `clearEmbeddedRule` 阻止清理；
4. **与 Magisk 方案的本质差异**：`/product` 只读，LSPosed 走内存 hook 或 `/data` 云控文件，**不需要也不能**替换 `/product/etc/*.xml`；
5. **autoui 云控写入 API 的最佳时机**：需要在 `MiuiSystemAutoUIRule` 实例已创建后调用（`onBootPhase 500+` 或 `MiuiAutoUIServiceImpl.init` after hook），否则 `loadPackage` 执行时读不到自定义云控文件；
6. **appcompat 静态 List 注入时机**：`LocalDataController.updatePolicyFromLocal` 在 boot 时执行一次，之后 `ApplicationCompatPolicy.onBootPhase(550)` 启动云控。模块应在 `onBootPhase(500)` 之前完成注入，确保开机即生效。

---

## 7. 风险与注意事项

1. **版本匹配**：插件 jar 必须与目标设备同 ROM 版本提取，跨版本类名/方法签名可能漂移；
2. **schema 变化**：HyperOS 4（Android 16 基线）的规则格式可能相对 sothx 文档（Android 15+）有增量属性，以实际 jar 反编译 + 系统内置 XML 为准；
3. **签名校验**：MiuiSystem 插件可能带完整性校验，但 LSPosed 只改内存不落盘，不受影响；若模块涉及修改 `/data/system` 下 XML，注意 SELinux 上下文（`system_data_file`）；
4. **jadx 内存**：反编译 services.jar 全量需限制 JVM 堆，否则崩（本机实测 5.5GB mmap 失败）：
   ```bash
   JAVA_OPTS="-Xmx3g" jadx.bat --no-res --no-debug-info --decompilation-mode simple -j 4 -d out classes*.dex
   ```
5. **Grep 二进制陷阱**：ripgrep 默认跳过含 NUL 的 dex 文件，字节级验证需 `grep -a`；
6. **临时产物清理清单**（占用较大，确认不需要后可删）：
   - `tmp/piano-rom-extract/`（payload.bin 约 9.68 GB）
   - `tmp/piano-imgs/system_ext.img`（785 MB）
   - `tmp/services_extract/`、`tmp/services_src/`（services.jar 解压与 jadx 中断产物）
   - `tmp/dump_payload.py`、`tmp/extract_system_ext.py`（一次性脚本）
   - 保留：`tmp/thumbs-extract/`（插件 jar）、`tmp/jadx-embedding/`（反编译源码）
7. **包级私有类的 ClassLoader 陷阱**（新增）：`MiuiSystemEmbeddedRule` 等核心类不在 system_server 默认 ClassLoader 中，直接 `Class.forName` 必然 `ClassNotFoundException`，必须先捕获插件 ClassLoader；
8. **jadx 反编译质量**（新增）：`--decompilation-mode simple` 产出的代码含 `goto Lxx` 标签，不可直接编译，仅用于读签名与常量，不要照抄逻辑。
9. **★ 总开关是硬前置**（4.8.4 新增）：`ro.config.miui_activity_embedding_enable` 为 `ro.` 只读属性，无法 `setprop`；`MiuiAppAdaptationProperties.IS_FOLD` / `IS_TABLET` / `MiuiEmbeddingWindowStub.ENABLED` 等均为 `static final` 常量，在类初始化时一次性固化。hook 方法返回值不会改变已固化的常量，必须用 `setStaticBooleanField` 一并改写，且要早于目标类的首次使用；
10. **应用进程侧与 system_server 侧是两套代码**（4.8.3 / 4.8.5 新增）：`MiuiEmbeddingWindowStub` / `MIUIAutoUIStub` 走 `ActivityThread.currentActivityThread().getBaseClassLoader()`，只在应用进程内可见。若模块只 hook system_server，这一侧完全触及不到；反之亦然。作用域必须明确划分。
11. **★ `fullScreenEnable` 必须与 `ratio_fullScreenEnable` 成对写**（4.10.5 新增）：真机 `embedded_setting_config.xml` 中两者出现次数完全相同（各 1054），未见任何只写其一的条目。只写一个大概率导致该应用的全屏比例设置不生效或状态显示错乱。
12. **★ 不要按反编译推断的根标签生成 XML**（4.10.2 新增）：解析器 `parsePackageXml` / `parseLocalPackageXml` 只按元素名 `package` 匹配，**不校验根标签**，但真机实样与此前从常量名推断的结果不一致（autoui 实为 `packageRules`，embedded/fixedOrientation 实为 `package_config`）。生成文件时照抄真机实样最安全。
13. **★ 规则量级已接近饱和，避免重复造轮子**（4.10.6 新增）：内置 8047 条平行窗口 + 3890 条固定横屏与 sothx 规则集量级相当。盲目全量导入 sothx 规则不仅收益低，还可能覆盖掉小米针对本机型调优过的参数（如 `splitRatio` / `scaleMode` / `minSupportVersion`）。必须先 diff 取差集。

---

## 8. 下一步行动清单

- [x] 下载并解包 piano OTA ROM，定位 `system_ext/framework/*.jar`
- [x] 提取核心插件 jar（thumbs.zip，7 个）
- [x] **反编译 `miui-embedding-window.jar`，定位规则解析/查询接口（hook 点已定案，见 4.5.3）**
- [x] 反编译 `miui-services.autoui.jar`、`miui-appcompat.jar`（autoui 云控 API 发现 + appcompat 定案，见 4.6 / 4.7）
- [x] **反编译 `framework.jar`（classes6 + classes，总开关属性 + 应用进程侧 API，见 4.8）**
- [x] **反编译 `miui-framework.jar`（规则 POJO + CloudData 定义，方案 C 全链路打通，见 4.9.1 / 4.9.5）**
- [x] 反编译 `miui-framework.autoui.jar`（应用进程侧执行链，101 类，见 4.9.6）
- [x] 反编译 `androidx.window.extensions.jar`（平行窗口应用侧，69 类，见 4.9.7）
- [x] 逆向云控数据结构：`CloudData` = JSON 字符串惰性包装器；云控 XML schema 已确定（4.9.2 / 4.9.5）
- [x] **jar 层面缺口全部关闭（10 个）；`mediatek-services.jar` / `unisoc-services.jar` 为非骁龙平台分支，不适用**
- [x] **补提真机侧文件：4 个规则/开关 XML + 3 个 `getprop` 总开关，全部到手并解析（4.10 / 4.8.4）—— 资料缺口清零**
- [ ] **diff sothx 规则集与真机 `embedded_rules_list.xml`（8047 条）/ `fixed_orientation_list.xml`（3890 条），产出差集清单**（4.10.6，取代原"全量导入"计划）
- [ ] 设计"规则注入"数据结构，对齐 4.10.4 的实测属性字典（注意 `skipSelfAdaptive` 是必带属性）
- [ ] 重构 magic_window 工程 hook 层：按 6.3 骨架实现（第 0 步校验 → 捕获 CL → 第四步批量翻开关 → A 档 hook 兜底 → autoui 云控注入）
- [ ] 实现插件 ClassLoader 捕获（`MiuiEmbeddingWindowServiceLoader` 加载方法签名待确认）
- [ ] 补查 `getprop ro.config.tablet` / `persist.sys.muiltdisplay_type`，确认 `IS_TABLET` fallback 状态（5.2 第 5b 项）
- [ ] 在真机上验证最小闭环：单个应用通过模块触发 autoui 云控注入 / 平行窗口
- [ ] 清理临时产物（第 7 节第 6 条清单）
