package com.github.lsposed.magicwindow.ai

import android.content.Context
import android.content.pm.PackageManager
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 工具执行器：将 AI 的 function call 映射到模块实际操作。
 *
 * 设计原则：
 * - 所有工具调用都在主线程执行（通过 ViewModel 协程调度）
 * - 返回结果为 JSON 字符串，直接作为 tool response 返回给 AI
 * - 错误时返回错误信息而非抛异常
 */
object AiToolExecutor {

    /** AI 可用工具定义（OpenAI function calling 格式） */
    val toolDefinitions: JSONArray = buildToolDefinitions()

    private fun buildToolDefinitions(): JSONArray {
        val arr = JSONArray()

        arr.put(makeTool(
            "search_apps",
            "按关键词搜索已安装的应用。返回应用的包名、名称、是否系统应用。",
            listOf("keyword" to "搜索关键词，可以是应用名或包名的一部分"),
            listOf("limit" to "返回结果数量上限，默认 10")
        ))

        arr.put(makeTool(
            "get_app_activities",
            "获取指定应用的全部 Activity 页面列表。用于确定哪些页面参与分屏。返回的列表中启动页会标记为「★」。",
            listOf("package_name" to "应用包名，如 tv.danmaku.bili")
        ))

        arr.put(makeTool(
            "get_current_rules",
            "查看当前已配置的所有应用规则。返回每个应用的模式和关键设置。",
            emptyList()
        ))

        arr.put(makeTool(
            "get_app_rule",
            "查看某个应用的详细规则配置。返回该应用的所有设置项。",
            listOf("package_name" to "应用包名")
        ))

        arr.put(makeTool(
            "get_system_rules",
            "查看系统内置的规则。返回该应用在系统平行窗口、固定横屏、界面适配三个名单中的原始属性。",
            listOf("package_name" to "应用包名")
        ))

        arr.put(makeTool(
            "set_app_rule",
            "为某个应用设置规则。保存后立即生效，无需重启。这是最核心的工具。",
            listOf(
                "package_name" to "应用包名",
                "mode" to "模式：embedding=平行窗口, fixedOrientation=固定横屏, fullScreen=通用全屏, off=不处理"
            ),
            listOf(
                "activity_rule" to "参与分屏的页面（逗号分隔），仅 embedding",
                "split_pair_rule" to "左右栏配对，格式「左栏:*」，仅 embedding",
                "split_ratio" to "左栏宽度占比 0.1~0.9，仅 embedding",
                "full_rule" to "整屏显示方式，推荐 nra:cr:rcr:nr，仅 fullScreen",
                "fo_default_settings" to "固定横屏档位：full 或 fo",
                "force_portrait_activity" to "始终竖着显示的页面"
            )
        ))

        arr.put(makeTool(
            "delete_app_rule",
            "删除某个应用的规则，恢复系统默认行为。",
            listOf("package_name" to "应用包名")
        ))

        return arr
    }

    private fun makeTool(
        name: String,
        desc: String,
        required: List<Pair<String, String>>,
        optional: List<Pair<String, String>> = emptyList()
    ): JSONObject {
        val props = JSONObject()
        (required + optional).forEach { (key, desc_) ->
            props.put(key, JSONObject().put("type", "string").put("description", desc_))
        }
        val reqArr = JSONArray()
        required.forEach { reqArr.put(it.first) }

        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("required", reqArr)
                    .put("properties", props)))
    }

    /**
     * 执行 AI 工具调用。
     * @param context Android Context
     * @param name 工具名称
     * @param arguments 工具参数
     * @return JSON 格式的执行结果
     */
    fun execute(context: Context, name: String, arguments: JSONObject): String {
        return try {
            when (name) {
                "search_apps" -> searchApps(context, arguments)
                "get_app_activities" -> getAppActivities(context, arguments)
                "get_current_rules" -> getCurrentRules()
                "get_app_rule" -> getAppRule(arguments)
                "get_system_rules" -> getSystemRules(context, arguments)
                "set_app_rule" -> setAppRule(context, arguments)
                "delete_app_rule" -> deleteAppRule(arguments)
                else -> errorResult("未知工具: $name")
            }
        } catch (e: Exception) {
            errorResult("工具执行失败: ${e.message}")
        }
    }

    // ── 工具实现 ──────────────────────────────────────────

    private fun searchApps(context: Context, args: JSONObject): String {
        val keyword = args.optString("keyword", "").lowercase()
        val limit = args.optInt("limit", 10)
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(appInfo.packageName)
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val builtin = SystemRuleSource.kindsOf(appInfo.packageName).map { k -> k.name }
                Triple(appInfo.packageName, label, isSystem) to builtin
            }
            .filter { (triple, _) ->
                keyword.isEmpty() ||
                triple.second.lowercase().contains(keyword) ||
                triple.first.lowercase().contains(keyword)
            }
            .take(limit)
            .toList()

        val arr = JSONArray()
        apps.forEach { (triple, builtin) ->
            arr.put(JSONObject()
                .put("package_name", triple.first)
                .put("label", triple.second)
                .put("is_system", triple.third)
                .put("builtin_rules", JSONArray(builtin)))
        }
        return JSONObject()
            .put("count", apps.size)
            .put("apps", arr)
            .toString()
    }

    private fun getAppActivities(context: Context, args: JSONObject): String {
        val pkg = args.optString("package_name", "")
        if (pkg.isEmpty()) return errorResult("package_name 不能为空")

        val pm = context.packageManager
        val acts = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
        }.getOrNull()
        if (acts.isNullOrEmpty()) return errorResult("没有找到 $pkg 的页面信息")

        val launcher = runCatching {
            pm.getLaunchIntentForPackage(pkg)?.component?.className
        }.getOrNull()

        val names = acts.mapNotNull { it.name }.distinct()
            .sortedWith(compareByDescending<String> { it == launcher }.thenBy { it })

        val arr = JSONArray()
        names.forEach { name ->
            val label = runCatching {
                val cn = android.content.ComponentName(pkg, name)
                val ai = pm.getActivityInfo(cn, PackageManager.GET_META_DATA)
                ai.loadLabel(pm).toString()
            }.getOrDefault("")
            arr.put(JSONObject()
                .put("name", name)
                .put("label", label)
                .put("is_launcher", name == launcher))
        }

        return JSONObject()
            .put("package_name", pkg)
            .put("launcher", launcher ?: JSONObject.NULL)
            .put("total", names.size)
            .put("activities", arr)
            .toString()
    }

    private fun getCurrentRules(): String {
        val rules = ConfigRepository.allRules()
        val arr = JSONArray()
        rules.values.forEach { rule ->
            arr.put(JSONObject()
                .put("package_name", rule.packageName)
                .put("enabled", rule.enabled)
                .put("mode", rule.mode.key))
        }
        return JSONObject()
            .put("count", rules.size)
            .put("rules", arr)
            .toString()
    }

    private fun getAppRule(args: JSONObject): String {
        val pkg = args.optString("package_name", "")
        if (pkg.isEmpty()) return errorResult("package_name 不能为空")
        val rule = ConfigRepository.rule(pkg)
            ?: return JSONObject().put("package_name", pkg).put("exists", false).toString()
        return JSONObject()
            .put("package_name", pkg)
            .put("exists", true)
            .put("rule", rule.toJson())
            .toString()
    }

    private fun getSystemRules(context: Context, args: JSONObject): String {
        val pkg = args.optString("package_name", "")
        if (pkg.isEmpty()) return errorResult("package_name 不能为空")
        return JSONObject()
            .put("package_name", pkg)
            .put("fixed_disabled", SystemRuleSource.isFixedDisabled(pkg))
            .put("embedding", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.EMBEDDING, pkg)))
            .put("fixed_orientation", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.FIXED, pkg)))
            .put("auto_ui", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.AUTO_UI, pkg)))
            .toString()
    }

    private fun setAppRule(context: Context, args: JSONObject): String {
        val pkg = args.optString("package_name", "")
        if (pkg.isEmpty()) return errorResult("package_name 不能为空")

        val modeStr = args.optString("mode", "")
        val mode = when (modeStr) {
            "embedding" -> WindowMode.EMBEDDING
            "fixedOrientation" -> WindowMode.FIXED_ORIENTATION
            "fullScreen" -> WindowMode.FULL_SCREEN
            "off" -> WindowMode.OFF
            else -> return errorResult("无效的 mode: $modeStr")
        }

        val existing = ConfigRepository.rule(pkg) ?: AppRule(pkg)
        existing.enabled = true
        existing.mode = mode

        // 按模式应用参数
        args.optString("activity_rule").takeIf { it.isNotEmpty() }?.let { existing.activityRule = it }
        args.optString("split_pair_rule").takeIf { it.isNotEmpty() }?.let { existing.splitPairRule = it }
        args.optString("split_ratio").takeIf { it.isNotEmpty() }?.let { existing.splitRatio = it }
        args.optString("full_rule").takeIf { it.isNotEmpty() }?.let { existing.fullRule = it }
        args.optString("fo_default_settings").takeIf { it.isNotEmpty() }?.let { existing.foDefaultSettings = it }
        args.optString("force_portrait_activity").takeIf { it.isNotEmpty() }?.let { existing.forcePortraitActivity = it }

        ConfigRepository.saveRule(existing)

        return JSONObject()
            .put("success", true)
            .put("package_name", pkg)
            .put("mode", mode.key)
            .put("message", "已保存 ${pkg} 的规则，模式为 ${mode.key}")
            .toString()
    }

    private fun deleteAppRule(args: JSONObject): String {
        val pkg = args.optString("package_name", "")
        if (pkg.isEmpty()) return errorResult("package_name 不能为空")
        val existed = ConfigRepository.rule(pkg) != null
        ConfigRepository.removeRule(pkg)
        return JSONObject()
            .put("success", true)
            .put("package_name", pkg)
            .put("deleted", existed)
            .toString()
    }

    // ── 辅助 ──────────────────────────────────────────

    private fun toolDef(name: String, desc: String, schema: JSONObject): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", schema))

    private fun errorResult(msg: String): String =
        JSONObject().put("error", msg).toString()
}
