package com.github.lsposed.magicwindow.data

import android.content.Context
import android.content.pm.PackageManager
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则格式校验与适配诊断：AI 助手（AiToolExecutor）与 MCP 服务器（McpServer）共用的 debug 逻辑，
 * 保证两侧工具行为完全一致。
 *
 * 校验规则以真机内置规则逆向结论为依据（tmp/AI适配指导文档.md）：
 *   - embedded activityRule：逗号分隔的 Activity 全类名，不带模式码
 *   - forcePortraitActivity 系列：「包名/类名」（ComponentName 风格），逗号分隔
 *   - autoui activityRule：分号分隔的「类名:模式码(:ViewID-数值)」，支持通配符 *
 *   - embedding 模式严禁携带 fullRule（系统会判定「支持全屏、不支持平行窗口」）
 */
object RuleDiagnostics {

    /**
     * 逐条校验一条模块规则的格式，返回问题列表。
     * level：error = 会导致规则失效；warning = 可能异常；info = 建议提示。
     */
    fun validate(context: Context, rule: AppRule): JSONArray {
        val issues = JSONArray()

        fun issue(level: String, field: String, message: String) {
            issues.put(JSONObject().put("level", level).put("field", field).put("message", message))
        }

        val pkg = rule.packageName
        val acts = installedActivities(context, pkg)

        // 页面是否存在：acts 为空说明读不到应用信息（未安装/包名错误），此时跳过存在性检查
        fun exists(fullName: String): Boolean? =
            if (acts.isEmpty()) null else fullName in acts

        fun checkExists(field: String, fullName: String) {
            if (exists(fullName) == false) issue("warning", field, "页面 $fullName 在应用中不存在，请核对类名")
        }

        // 逗号分隔的纯全类名列表（embedded activityRule / transitionRules）
        fun checkPlainList(field: String, raw: String) {
            raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { checkExists(field, it) }
        }

        // 「包名/类名」格式列表（forcePortraitActivity 系列）
        fun checkPkgClassList(field: String, raw: String) {
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { item ->
                if ('/' !in item) {
                    issue("error", field, "「$item」缺少「包名/类名」格式（应为 com.pkg/.ui.Act 或 com.pkg/com.pkg.ui.Act）")
                    return@forEach
                }
                val base = item.substringBefore('/')
                if (base != pkg) issue("warning", field, "「$item」的包名部分 $base 与应用包名 $pkg 不一致")
                checkExists(field, flattenPkgClass(item))
            }
        }

        // —— 互斥约束 ——
        // fullRule 只在通用全屏模式下序列化（CloudXmlCodec.fullScreenAttrsOf），
        // 平行窗口模式不会写入系统文件，内存默认值无实际影响——降为 warning 防止误报
        if (rule.mode == WindowMode.EMBEDDING && rule.fullRule.isNotEmpty()) {
            issue("warning", "fullRule", "fullRule 仅在通用全屏模式下生效，平行窗口模式不会写入系统（无实际影响）；如为误设可忽略")
        }

        // —— 通用数值 ——
        rule.splitRatio.takeIf { it.isNotEmpty() }?.let {
            val v = it.toFloatOrNull()
            when {
                v == null -> issue("error", "splitRatio", "取值 $it 不是数字")
                v < 0.1f || v > 0.9f -> issue("error", "splitRatio", "取值 $it 超出 0.1~0.9 范围")
            }
        }

        // —— embedding 页面字段 ——
        if (rule.mode == WindowMode.EMBEDDING) {
            checkPlainList("activityRule", rule.activityRule)
            checkPlainList("transitionRules", rule.transitionRules)
            checkPkgClassList("forcePortraitActivity", rule.forcePortraitActivity)

            val pairLefts = mutableListOf<String>()
            rule.splitPairRule.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
                val left = pair.substringBefore(':').trim()
                val right = pair.substringAfter(':', "").trim()
                if (left.isEmpty() || right.isEmpty()) {
                    issue("error", "splitPairRule", "配对「$pair」格式错误，应为「左栏页面类名:右栏页面类名或*」")
                    return@forEach
                }
                pairLefts.add(left)
                checkExists("splitPairRule", left)
                if (right != "*") checkExists("splitPairRule", right)
            }

            rule.placeholder.takeIf { it.isNotEmpty() }?.let { ph ->
                val parts = ph.split(':')
                if (parts.size != 2 || parts.any { it.isBlank() }) {
                    issue("error", "placeholder", "「$ph」格式错误，应为「主页面全类名:占位页面全类名」，且冒号左边必须是主页面")
                } else {
                    val main = parts[0].trim()
                    val placeholder = parts[1].trim()
                    checkExists("placeholder", main)
                    checkExists("placeholder", placeholder)
                    if (pairLefts.isNotEmpty() && main !in pairLefts) {
                        issue("info", "placeholder", "主页面 $main 不在 splitPairRule 左栏列表中，占位页可能不会被打开")
                    }
                }
            }
        }

        // —— fixed 侧字段 ——
        if (rule.mode == WindowMode.FIXED_ORIENTATION || rule.foSupportModes.isNotEmpty()) {
            rule.foSupportModes.takeIf { it.isNotEmpty() && it != "full,fo" }?.let {
                issue("warning", "foSupportModes", "取值 $it 不是系统惯例的 full,fo")
            }
            rule.foDefaultSettings.takeIf { it.isNotEmpty() && it != "full" && it != "fo" }?.let {
                issue("error", "foDefaultSettings", "取值 $it 无效，只能为 full 或 fo")
            }
            rule.foRatio.takeIf { it.isNotEmpty() }?.let {
                if (it.toFloatOrNull() == null) issue("error", "foRatio", "取值 $it 不是数字")
            }
            checkPkgClassList("foForcePortraitActivity", rule.foForcePortraitActivity)
            checkPkgClassList("foFullForcePortraitActivity", rule.foFullForcePortraitActivity)
        }

        // —— autoui 叠加字段 ——
        if (rule.autoUiEnable) {
            rule.autoUiActivityRule.takeIf { it.isNotEmpty() }?.let { raw ->
                raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { item ->
                    val cls = item.substringBefore(':').trim()
                    val rest = item.substringAfter(':', "").trim()
                    if (rest.isNotEmpty()) {
                        val code = rest.substringBefore(':').trim()
                        if (code.toIntOrNull() == null) {
                            issue("error", "autoUiActivityRule", "「$item」模式码 $code 不是数字")
                        } else if (code !in listOf("0", "1", "2", "6", "7", "8", "-1")) {
                            issue("info", "autoUiActivityRule", "「$item」模式码 $code 不是常见值（0/1/2/6/7/8/-1）")
                        }
                    } else {
                        issue("info", "autoUiActivityRule", "「$item」未带模式码，系统按默认模式处理")
                    }
                    if (cls != "*") checkExists("autoUiActivityRule", cls)
                }
            }
        } else if (rule.autoUiActivityRule.isNotEmpty() || rule.autoUiSkippedActivityRule.isNotEmpty()) {
            issue("info", "autoUiActivityRule", "已填写界面适配规则但 autoUiEnable 未开启，规则不会生效")
        }

        return issues
    }

    /**
     * 综合诊断：模块规则 + 系统内置规则 + 格式校验 + 互斥冲突，一次性给出适配调试所需全部信息。
     */
    fun diagnose(context: Context, pkg: String): JSONObject {
        val rule = ConfigRepository.rule(pkg)
        val issues = rule?.let { validate(context, it) } ?: JSONArray()

        var errorCount = issues.length().let { n ->
            (0 until n).count { issues.optJSONObject(it)?.optString("level") == "error" }
        }
        val conflicts = JSONArray()
        fun conflict(level: String, message: String) {
            if (level == "error") errorCount++
            conflicts.put(JSONObject().put("level", level).put("message", message))
        }

        when {
            rule == null -> conflict("info", "尚未为该应用配置模块规则")
            !rule.enabled -> conflict("warning", "该应用的模块规则处于停用状态，不会生效")
            else -> {
                when (rule.mode) {
                    WindowMode.EMBEDDING -> {
                        if (SystemRuleSource.has(SystemRuleSource.Kind.FIXED, pkg) && !SystemRuleSource.isFixedDisabled(pkg)) {
                            conflict("warning", "系统已内置该应用的固定横屏规则（固定横屏优先级高于平行窗口），自定义平行窗口可能被覆盖")
                        }
                    }
                    WindowMode.FIXED_ORIENTATION -> {
                        if (SystemRuleSource.has(SystemRuleSource.Kind.EMBEDDING, pkg)) {
                            conflict("info", "系统已内置该应用的平行窗口规则；固定横屏与平行窗口互斥，以固定横屏为准")
                        }
                        if (SystemRuleSource.isFixedDisabled(pkg) && !rule.foDisable) {
                            conflict("info", "系统将此应用标记为禁用信箱模式，模块会覆盖该标记；若不生效请确认 LSPosed 已激活模块")
                        }
                    }
                    WindowMode.FULL_SCREEN -> {
                        if (SystemRuleSource.has(SystemRuleSource.Kind.FIXED, pkg)) {
                            conflict("info", "系统已内置固定横屏规则，优先级高于通用全屏")
                        }
                    }
                    else -> Unit
                }
                if (rule.autoUiEnable && SystemRuleSource.has(SystemRuleSource.Kind.AUTO_UI, pkg)) {
                    conflict("info", "系统已内置该应用的界面适配规则，模块规则将按包名覆盖")
                }
            }
        }
        if (!SystemRuleSource.loaded) {
            conflict("warning", "系统内置规则尚未加载完成或读取失败${SystemRuleSource.errorMessage?.let { "：$it" } ?: ""}，诊断可能不完整")
        }

        return JSONObject()
            .put("package_name", pkg)
            .put("has_module_rule", rule != null)
            .put("builtin_kinds", JSONArray(SystemRuleSource.kindsOf(pkg).map { it.name }))
            .put("module_rule", rule?.toJson() ?: JSONObject.NULL)
            .put("system_rule", JSONObject()
                .put("fixed_disabled", SystemRuleSource.isFixedDisabled(pkg))
                .put("embedding", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.EMBEDDING, pkg)))
                .put("fixed_orientation", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.FIXED, pkg)))
                .put("auto_ui", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.AUTO_UI, pkg))))
            .put("conflicts", conflicts)
            .put("issues", issues)
            .put("valid", errorCount == 0)
    }

    /** 模块整体状态：版本、规则统计、系统规则源读取情况（排查 root/加载问题的第一入口） */
    fun moduleStatus(context: Context): JSONObject {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
        val all = ConfigRepository.allRules()
        return JSONObject()
            .put("module_version", version)
            .put("rules_configured", all.size)
            .put("rules_enabled", all.values.count { it.enabled })
            .put("auto_ui_enabled", all.values.count { it.enabled && it.autoUiEnable })
            .put("system_rules_loaded", SystemRuleSource.loaded)
            .put("root_available", SystemRuleSource.rootAvailable)
            .put("system_rules_error", SystemRuleSource.errorMessage ?: JSONObject.NULL)
    }

    /**
     * 应用打开就闪退时的定位入口：通过 root 读取系统 crash 缓冲区（logcat -b crash），
     * 过滤出目标应用的崩溃事件，并结合规则内容给出针对性分析。
     *
     * 典型定位链路：ClassNotFoundException → 提取崩溃类名 → 与规则里填写的页面类名比对，
     * 直接指出是哪个规则字段写错导致闪退。
     */
    fun crashReport(context: Context, pkg: String, maxEvents: Int = 5): JSONObject {
        val analysis = JSONArray()
        fun hint(level: String, message: String) {
            analysis.put(JSONObject().put("level", level).put("message", message))
        }

        val rule = ConfigRepository.rule(pkg)
        val events = readCrashEvents(pkg)
        val shown = events.takeLast(maxEvents.coerceIn(1, 20))

        if (shown.isEmpty()) {
            hint("info", "crash 缓冲区中没有 $pkg 的崩溃记录：可能闪退未复现、记录已被清理，或 root 读取失败（root_available=${SystemRuleSource.rootAvailable}）。可先打开该应用触发崩溃后再调用")
            return JSONObject()
                .put("package_name", pkg)
                .put("found", false)
                .put("events", JSONArray())
                .put("analysis", analysis)
        }

        // 结合规则内容分析每条崩溃堆栈
        shown.forEach { event ->
            val trace = event.optString("trace")
            val crashClass = Regex("(?:ClassNotFoundException|NoClassDefFoundError)[^\\n]*?([\\w.$]+\\.[\\w$]+)")
                .find(trace)?.groupValues?.get(1)
            if (crashClass != null) {
                val fields = rule?.let {
                    listOf(
                        "activityRule" to it.activityRule,
                        "splitPairRule" to it.splitPairRule,
                        "placeholder" to it.placeholder,
                        "transitionRules" to it.transitionRules,
                        "forcePortraitActivity" to it.forcePortraitActivity
                    )
                }?.filter { (_, raw) -> raw.split(',', ';', ':').map { v -> v.trim() }.contains(crashClass) }
                if (fields != null && fields.isNotEmpty()) {
                    hint("error", "崩溃类 $crashClass 正是规则字段 ${fields.joinToString("、") { it.first }} 里填写的页面：类名不存在或写错，这就是闪退原因，请核对后修正")
                } else {
                    hint("info", "崩溃类 $crashClass 不在规则字段里，可能是应用自身或系统注入层问题")
                }
            }
            if (Regex("MiuiEmbedded|MiuiMultiWindow|MiuiFreeForm|embedded.*Exception", RegexOption.IGNORE_CASE).containsMatchIn(trace)) {
                hint("warning", "堆栈命中系统平行窗口注入层（Miui 嵌入框架）。建议先 delete_app_rule 关闭该应用的规则验证：若关闭后不闪退则确认是规则引起，可尝试减少 activityRule 中的页面数量或去掉 placeholder")
            }
            if ("Resources\$NotFoundException" in trace) {
                hint("info", "资源找不到异常，常见于界面适配（autoui）或全屏拉伸场景，可尝试关闭 autoui_enable 或改用信箱模式")
            }
        }
        if (analysis.length() == 0) {
            hint("info", "堆栈中没有命中规则相关的典型特征（类名不存在/系统嵌入层/资源缺失），请把 events 里的堆栈原样反馈给 AI 人工分析")
        }

        return JSONObject()
            .put("package_name", pkg)
            .put("found", true)
            .put("total_events", events.size)
            .put("events", JSONArray().apply { shown.forEach { put(it) } })
            .put("analysis", analysis)
    }

    /** 读 crash 缓冲区并按事件分组，只保留与目标应用相关的崩溃（进程行含包名或进程名匹配） */
    private fun readCrashEvents(pkg: String): List<JSONObject> {
        val text = runSu("logcat -d -b crash -t 400") ?: return emptyList()
        // logcat 行头：MM-dd HH:mm:ss.mmm PID TID LEVEL TAG: msg
        val header = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+)")
        val events = mutableListOf<JSONObject>()
        var current: StringBuilder? = null
        fun flush() {
            val trace = current?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: return
            // 只保留该应用的崩溃：进程行「Process: pkg,」或任意行含完整包名（包名做正则元字符转义）
            val escaped = Regex.escape(pkg)
            if (Regex("Process: $escaped[,\\s]").containsMatchIn(trace) || trace.contains(pkg)) {
                events.add(JSONObject().put("trace", trace))
            }
        }
        text.lineSequence().forEach { line ->
            if (header.containsMatchIn(line)) {
                flush()
                current = StringBuilder(line).append('\n')
            } else {
                current?.append(line)?.append('\n')
            }
        }
        flush()
        return events
    }

    /**
     * su 执行的安全边界（严格遵守，防止宿主设备受到不可逆损坏）：
     * 1. 命令白名单：只允许执行下列只读命令，其他一律拒绝——
     *    本模块的 su 仅用于「读取」日志/规则文件，绝不执行写入、删除、权限修改、
     *    挂载、重启等任何有副作用的操作
     * 2. 外部输入（包名、类名等）永不拼入 shell 命令行，只用于本地正则/字符串比对
     * 3. 超时必须严格：命令最长 10s，防止卡死或被恶意提示器挂起
     * 4. 失败静默返回 null，绝不重试执行非白名单命令
     */
    private val SU_READ_ONLY_WHITELIST = setOf(
        "logcat -d -b crash -t 400",   // 读取崩溃缓冲区（只读，-d dump 后立即退出）
    )

    /** 通过 su 执行白名单内的只读命令并返回 stdout（与 SystemRuleSource 同款调用方式） */
    private fun runSu(cmd: String): String? {
        if (cmd !in SU_READ_ONLY_WHITELIST) return null
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val errThread = Thread { runCatching { process.errorStream.bufferedReader().readText() } }.apply { isDaemon = true }
            errThread.start()
            val ok = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            val out = if (ok) process.inputStream.bufferedReader().readText() else null
            process.destroy()
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** 「包名/类名」还原为全类名（兼容 .相对类名 / 包名+全类名 / 纯全类名三种写法） */
    private fun flattenPkgClass(value: String): String =
        if ('/' in value) {
            value.substringBefore('/') + "." + value.substringAfter('/').removePrefix(".")
        } else value

    private fun installedActivities(context: Context, pkg: String): Set<String> =
        runCatching {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                .activities?.mapNotNull { it.name }?.toSet()
        }.getOrNull() ?: emptySet()
}
