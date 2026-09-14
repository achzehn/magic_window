package com.github.lsposed.magicwindow.mcp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.RuleDiagnostics
import com.github.lsposed.magicwindow.data.SystemRuleSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 MCP（Model Context Protocol）调试服务器，走 Streamable-HTTP 风格的 JSON-RPC 2.0。
 *
 * 目的：让电脑上的 AI 模型 / MCP 客户端通过局域网读写本模块的规则，
 * 辅助调试应用的横屏适配（查看内置规则 → 生成规则 → 写回 → 立即生效）。
 *
 * 接入方式（Postman / mcp-inspector / 任意 MCP HTTP 客户端）：
 *   POST http://<平板IP>:8765/mcp
 *   Header: Authorization: Bearer magic-window
 *   Body:   JSON-RPC 2.0（initialize / tools/list / tools/call）
 *
 * 提供的工具（与 AI 助手侧 AiToolExecutor 完全同名同义，共用 RuleDiagnostics 诊断逻辑）：
 *   search_apps / get_app_activities / get_current_rules / get_app_rule
 *   set_app_rule / delete_app_rule / get_system_rules
 *   validate_rule / diagnose_app / launch_app / export_rules / get_module_status
 */
object McpServer {

    const val DEFAULT_PORT = 8765
    private const val PREFS = "mcp_settings"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = running.get()

    // ── 令牌 / 端口设置（持久化到 SharedPreferences） ─────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前端口（未启动时也按已保存的配置返回） */
    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    /** 当前令牌，空串表示不启用鉴权 */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "") ?: ""

    /** 随机生成 32 位十六进制令牌 */
    fun randomToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 设置令牌，服务运行中则热更新（下次请求生效） */
    fun setToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOKEN, value.trim()).apply()
    }

    /** 设置端口，服务运行中会自动重启换端口；重启失败返回 false（服务停止） */
    fun setPort(context: Context, value: Int): Boolean {
        prefs(context).edit().putInt(KEY_PORT, value).apply()
        if (running.get()) {
            stop()
            return start(context.applicationContext)
        }
        return true
    }

    private fun curPort(): Int =
        if (::appContext.isInitialized) port(appContext) else DEFAULT_PORT

    fun start(context: Context): Boolean {
        if (running.get()) return true
        appContext = context.applicationContext
        return try {
            val socket = ServerSocket(port(appContext))
            serverSocket = socket
            running.set(true)
            acceptThread = Thread({
                while (running.get() && !socket.isClosed) {
                    runCatching { socket.accept().let(::serve) }
                }
            }, "MagicWindow-MCP").apply {
                isDaemon = true
                start()
            }
            true
        } catch (t: Throwable) {
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** 本机访问地址（设备自身访问），例如 http://127.0.0.1:8765/mcp */
    fun loopbackUrl(): String = "http://127.0.0.1:${curPort()}/mcp"

    /**
     * 生成 MCP 客户端接入配置 JSON。
     *
     * ⚠️ 只输出一个服务器条目（HTTP 直连）：粘贴两个条目会让客户端注册两份重复工具。
     * Claude Desktop 等不支持 headers 字段的客户端，请手动把该条目替换为 mcp-remote 中转写法：
     *   { "command": "npx", "args": ["-y", "mcp-remote", "<局域网URL>", "--header", "Bearer <令牌>"] }
     */
    fun clientConfigJson(context: Context): String {
        val lan = localUrl()
        val auth = "Bearer ${token(context.applicationContext)}"
        val direct = JSONObject().put("url", lan)
        if (auth != "Bearer ") direct.put("headers", JSONObject().put("Authorization", auth))
        return JSONObject()
            .put("mcpServers", JSONObject().put("magic-window", direct))
            .toString(2)
    }

    /** 局域网访问地址提示，例如 http://192.168.20.250:8765/mcp */
    fun localUrl(): String {
        val ip = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
                .filter { it.isUp }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
        return "http://$ip:${curPort()}/mcp"
    }

    // ── 连接处理 ─────────────────────────────────────────────

    private fun serve(socket: Socket) {
        Thread({
            socket.use { s ->
                runCatching {
                    // 性能关键：禁用 Nagle 算法，避免「小包 + 延迟 ACK」造成的数百毫秒卡顿
                    s.tcpNoDelay = true
                    // keep-alive 空闲 5 分钟后断开，防止连接线程堆积
                    s.soTimeout = 300_000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val out = s.getOutputStream()
                    // 同一连接循环处理多个请求（HTTP/1.1 长连接），
                    // mcp-remote / Postman 等客户端复用连接时免去每请求重新握手
                    while (true) {
                        val first = reader.readLine() ?: break
                        val expected = token(appContext)
                        var contentLength = 0
                        var authorized = expected.isEmpty()
                        var keepAlive = first.contains("HTTP/1.1")
                        while (true) {
                            val line = reader.readLine() ?: return@runCatching
                            if (line.isEmpty()) break
                            val lower = line.lowercase(Locale.US)
                            if (lower.startsWith("content-length:")) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                            if (lower.startsWith("connection:")) {
                                keepAlive = when {
                                    "close" in lower -> false
                                    "keep-alive" in lower -> true
                                    else -> keepAlive
                                }
                            }
                            // 令牌为空（不校验）时忽略请求携带的任何 Authorization 头
                            if (expected.isNotEmpty() && lower.startsWith("authorization:")) {
                                authorized = line.substringAfter(':').trim() == "Bearer $expected"
                            }
                        }
                        // 循环读满请求体：单次 read 可能因 TCP 分段读不满，
                        // 残留字节会被误当下一个请求解析，导致长连接协议错位断连
                        val buf = CharArray(contentLength)
                        var off = 0
                        while (off < contentLength) {
                            val n = reader.read(buf, off, contentLength - off)
                            if (n <= 0) return@runCatching
                            off += n
                        }
                        val body = if (off <= 0) "" else String(buf, 0, off)
                        if (!authorized) {
                            write(out, 401, """{"error":"unauthorized"}""", false)
                            break
                        }
                        when {
                            first.startsWith("OPTIONS") -> write(out, 204, "", keepAlive)
                            first.startsWith("POST") -> write(out, 200, handleRpc(body), keepAlive)
                            else -> write(out, 405, """{"error":"POST only"}""", false)
                        }
                        if (!keepAlive) break
                    }
                }
            }
        }, "MagicWindow-MCP-conn").apply { isDaemon = true }.start()
    }

    /** header + body 一次性写出（分两次 write 会触发 Nagle/延迟 ACK 互等，产生数百毫秒延迟） */
    private fun write(out: java.io.OutputStream, code: Int, json: String, keepAlive: Boolean) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id\r\n" +
            (if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n") +
            "Content-Length: ${bytes.size}\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8) + bytes)
        out.flush()
    }

    // ── JSON-RPC / MCP 协议 ──────────────────────────────────

    private fun handleRpc(body: String): String {
        val req = runCatching { JSONObject(body) }.getOrElse {
            return rpcError(null, -32700, "Parse error").toString()
        }
        val id = req.opt("id")
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        val result: Any = when (method) {
            "initialize" -> JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                "result", JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject()
                        .put("name", "magic-window")
                        .put("version", "1.0"))
            )

            "notifications/initialized", "notifications/cancelled" ->
                JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)

            "ping" -> JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("result", JSONObject())

            "tools/list" -> JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("result", JSONObject().put("tools", toolsList()))

            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val toolResult = runCatching { callTool(name, args) }
                    .getOrElse {
                        toolText("工具执行失败：${it.message ?: it.javaClass.simpleName}")
                    }
                JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("result", JSONObject().put("content", JSONArray().put(
                        JSONObject().put("type", "text").put("text", toolResult.toString(2))
                    )))
            }

            else -> rpcError(id, -32601, "Method not found: $method")
        }
        return result.toString()
    }

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun toolText(text: String): JSONObject =
        JSONObject().put("content", JSONArray().put(
            JSONObject().put("type", "text").put("text", text)
        )).put("isError", true)

    /** 与 AiToolExecutor 同名同义的 12 个工具；schema 用 MCP 的 inputSchema 格式 */
    private fun toolsList(): JSONArray = JSONArray()
        .put(toolDef(
            "search_apps", "按关键词搜索已安装的应用。返回应用的包名、名称、是否系统应用、命中的系统内置规则名单。",
            strSchema(required = listOf("keyword" to "搜索关键词，可以是应用名或包名的一部分"),
                optional = listOf("limit" to "返回结果数量上限，默认 10")))
        ).put(toolDef(
            "get_app_activities", "获取指定应用的全部 Activity 页面列表（启动页排最前），用于确定哪些页面参与分屏。",
            strSchema(required = listOf("package_name" to "应用包名")))
        ).put(toolDef(
            "get_current_rules", "查看当前已配置的所有应用规则（包名/启停/模式）。",
            strSchema())
        ).put(toolDef(
            "get_app_rule", "查看某个应用的详细规则配置（完整字段）。",
            strSchema(required = listOf("package_name" to "应用包名")))
        ).put(toolDef(
            "set_app_rule", "为某个应用创建或更新规则，保存后立即热生效。参数与 AI 助手侧 set_app_rule 完全一致（扁平 snake_case）。",
            strSchema(required = listOf(
                "package_name" to "应用包名",
                "mode" to "模式：embedding=平行窗口, fixedOrientation=固定横屏, fullScreen=通用全屏, off=不处理"),
                optional = listOf(
                    "activity_rule" to "参与分屏的页面（逗号分隔的 Activity 全类名，不带模式码），仅 embedding",
                    "split_pair_rule" to "左右栏配对，格式「左栏:*」，多对用逗号分隔，仅 embedding",
                    "split_ratio" to "左栏宽度占比 0.1~0.9，仅 embedding",
                    "placeholder" to "右栏默认占位页面，格式「主页面全类名:占位页面全类名」，仅 embedding",
                    "transition_rules" to "不参与分屏的过渡页面（逗号分隔），仅 embedding",
                    "support_full_size" to "是否可放大到整屏，true/false，仅 embedding",
                    "is_show_divider" to "是否显示分割线，true/false，仅 embedding",
                    "force_portrait_activity" to "始终竖着显示的页面，格式「包名/类名」，仅 embedding",
                    "full_rule" to "整屏显示方式，推荐 nra:cr:rcr:nr。仅 fullScreen 模式可用，平行窗口模式设置会导致规则失效",
                    "fo_default_settings" to "固定横屏档位：full 或 fo，仅 fixedOrientation",
                    "fo_support_modes" to "支持的档位，一般为 full,fo，仅 fixedOrientation",
                    "fo_ratio" to "信箱模式宽高比，如 1.1，仅 fixedOrientation",
                    "fo_force_portrait_activity" to "固定横屏下仍竖屏显示的页面，格式「包名/类名」，仅 fixedOrientation",
                    "autoui_enable" to "是否启用界面适配优化，true/false，可叠加在任意模式上",
                    "autoui_activity_rule" to "界面适配页面规则，格式「页面全类名:模式码」，模式码 1=左侧 2=右侧 6=全屏，多页面用分号分隔",
                    "autoui_skipped_activity_rule" to "跳过界面适配的页面（分号分隔）")))
        ).put(toolDef(
            "delete_app_rule", "删除某个应用的模块规则，恢复系统默认行为。",
            strSchema(required = listOf("package_name" to "应用包名")))
        ).put(toolDef(
            "get_system_rules", "查看系统内置规则（平行窗口/固定横屏/界面适配三个名单的原始属性 + 固定横屏禁用标记）。",
            strSchema(required = listOf("package_name" to "应用包名")))
        ).put(toolDef(
            "validate_rule", "校验某个应用已保存规则的格式是否符合系统规范（分隔符、包名/类名格式、数值范围、模式互斥、页面类名存在性）。保存规则后建议立即调用。",
            strSchema(required = listOf("package_name" to "应用包名")))
        ).put(toolDef(
            "diagnose_app", "综合诊断某个应用的适配状态：模块规则 + 系统内置规则 + 格式校验 + 互斥冲突检查。规则不生效时优先用这个工具。",
            strSchema(required = listOf("package_name" to "应用包名")))
        ).put(toolDef(
            "get_crash_log", "应用打开就闪退时的定位工具：读取系统崩溃日志（需要 root），结合规则内容分析闪退原因，直接指出是哪个规则字段导致的。",
            strSchema(required = listOf("package_name" to "应用包名"),
                optional = listOf("max_events" to "最多返回的崩溃事件数，默认 5")))
        ).put(toolDef(
            "launch_app", "启动某个应用（可指定页面）供实测适配效果。",
            strSchema(required = listOf("package_name" to "应用包名"),
                optional = listOf("activity_name" to "要启动的页面 Activity 全类名；不传则启动应用首页")))
        ).put(toolDef(
            "export_rules", "把规则导出为本地 JSON 文件。不传 package_name 导出全部；传则只导出该应用。",
            strSchema(optional = listOf("package_name" to "只导出该应用的规则")))
        ).put(toolDef(
            "get_module_status", "查看模块整体状态：版本、规则数量统计、系统规则源是否读取成功（root/加载问题排查入口）。",
            strSchema())
        )

    /** 纯字符串参数的简化 schema 构造：required/optional 传「参数名 to 描述」 */
    private fun strSchema(
        required: List<Pair<String, String>> = emptyList(),
        optional: List<Pair<String, String>> = emptyList()
    ): JSONObject {
        val props = JSONObject()
        (required + optional).forEach { (name, desc) ->
            props.put(name, JSONObject().put("type", "string").put("description", desc))
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray().apply { required.forEach { put(it.first) } })
    }

    private fun toolDef(name: String, desc: String, schema: JSONObject): JSONObject =
        JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)

    // ── 工具实现（统一切回主线程执行，避免与 UI 并发改规则） ──

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val latch = CountDownLatch(1)
        lateinit var result: JSONObject
        mainHandler.post {
            try {
                result = dispatchOnMain(name, args)
            } catch (t: Throwable) {
                result = toolText("工具执行失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            return toolText("工具执行超时")
        }
        return result
    }

    private fun dispatchOnMain(name: String, args: JSONObject): JSONObject = when (name) {
        "search_apps" -> searchApps(args.optString("keyword"), args.optInt("limit", 10))
        "get_app_activities" -> listActivities(args.optString("package_name"))
        "get_current_rules" -> listRules()
        "get_app_rule" -> getRule(args.optString("package_name"))
        "set_app_rule" -> setAppRule(args)
        "delete_app_rule" -> deleteRule(args.optString("package_name"))
        "get_system_rules" -> systemRule(args.optString("package_name"))
        "validate_rule" -> validateRule(args.optString("package_name"))
        "diagnose_app" -> diagnoseApp(args.optString("package_name"))
        "get_crash_log" -> RuleDiagnostics.crashReport(
            appContext, args.optString("package_name"), args.optInt("max_events", 5)
        )
        "launch_app" -> launchApp(args)
        "export_rules" -> exportRules(args.optString("package_name", ""))
        "get_module_status" -> RuleDiagnostics.moduleStatus(appContext)
        else -> toolText("未知工具：$name")
    }

    private fun listRules(): JSONObject {
        val rules = ConfigRepository.allRules()
        val arr = JSONArray()
        rules.values.forEach { arr.put(it.toJson()) }
        return JSONObject().put("count", arr.length()).put("rules", arr)
    }

    private fun getRule(pkg: String): JSONObject {
        val rule = ConfigRepository.rule(pkg)
        return JSONObject().put("package_name", pkg)
            .put("exists", rule != null)
            .put("rule", rule?.toJson() ?: JSONObject.NULL)
    }

    /**
     * 与 AI 助手侧 set_app_rule 同参：扁平 snake_case 字段，布尔仅在显式传入时覆盖。
     * 保留旧版「rule 对象」整体覆盖方式作为兼容（power 用户可一次写全字段）。
     */
    private fun setAppRule(args: JSONObject): JSONObject {
        val pkg = args.optString("package_name", "")
        if (pkg.isEmpty()) return toolText("package_name 不能为空")
        val base = ConfigRepository.rule(pkg)?.toJson() ?: JSONObject()
        base.put("packageName", pkg)
        args.optString("mode", "").takeIf { it.isNotEmpty() }?.let { modeStr ->
            if (modeStr !in listOf("embedding", "fixedOrientation", "fullScreen", "off")) {
                return toolText("无效的 mode: $modeStr")
            }
            base.put("mode", modeStr)
        }
        args.optString("activity_rule").takeIf { it.isNotEmpty() }?.let { base.put("activityRule", it) }
        args.optString("split_pair_rule").takeIf { it.isNotEmpty() }?.let { base.put("splitPairRule", it) }
        args.optString("split_ratio").takeIf { it.isNotEmpty() }?.let { base.put("splitRatio", it) }
        args.optString("placeholder").takeIf { it.isNotEmpty() }?.let { base.put("placeholder", it) }
        args.optString("transition_rules").takeIf { it.isNotEmpty() }?.let { base.put("transitionRules", it) }
        args.optString("force_portrait_activity").takeIf { it.isNotEmpty() }?.let { base.put("forcePortraitActivity", it) }
        args.optString("full_rule").takeIf { it.isNotEmpty() }?.let { base.put("fullRule", it) }
        args.optString("fo_default_settings").takeIf { it.isNotEmpty() }?.let { base.put("foDefaultSettings", it) }
        args.optString("fo_support_modes").takeIf { it.isNotEmpty() }?.let { base.put("foSupportModes", it) }
        args.optString("fo_ratio").takeIf { it.isNotEmpty() }?.let { base.put("foRatio", it) }
        args.optString("fo_force_portrait_activity").takeIf { it.isNotEmpty() }?.let { base.put("foForcePortraitActivity", it) }
        args.optString("autoui_activity_rule").takeIf { it.isNotEmpty() }?.let { base.put("autoUiActivityRule", it) }
        args.optString("autoui_skipped_activity_rule").takeIf { it.isNotEmpty() }?.let { base.put("autoUiSkippedActivityRule", it) }
        if (args.has("support_full_size")) base.put("supportFullSize", args.optBoolean("support_full_size", true))
        if (args.has("is_show_divider")) base.put("isShowDivider", args.optBoolean("is_show_divider", true))
        if (args.has("autoui_enable")) base.put("autoUiEnable", args.optBoolean("autoui_enable", true))
        args.optJSONObject("rule")?.let { up ->
            up.keys().forEachRemaining { key -> if (key != "packageName") base.put(key, up.get(key)) }
        }
        val rule = AppRule.fromJson(base)
        ConfigRepository.saveRule(rule)
        return JSONObject().put("saved", true)
            .put("rule", ConfigRepository.rule(pkg)?.toJson() ?: rule.toJson())
    }

    private fun deleteRule(pkg: String): JSONObject {
        val existed = ConfigRepository.rule(pkg) != null
        ConfigRepository.removeRule(pkg)
        return JSONObject().put("deleted", existed).put("package_name", pkg)
    }

    private fun systemRule(pkg: String): JSONObject =
        JSONObject()
            .put("package_name", pkg)
            .put("fixedDisabled", SystemRuleSource.isFixedDisabled(pkg))
            .put("embedding", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.EMBEDDING, pkg)))
            .put("fixedOrientation", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.FIXED, pkg)))
            .put("autoUI", JSONObject(SystemRuleSource.attrsOf(SystemRuleSource.Kind.AUTO_UI, pkg)))

    // ── 调试工具实现（与 AiToolExecutor 共用 RuleDiagnostics 逻辑） ──

    private fun validateRule(pkg: String): JSONObject {
        if (pkg.isEmpty()) return toolText("package_name 不能为空")
        val rule = ConfigRepository.rule(pkg)
            ?: return toolText("该应用还没有配置规则，请先用 set_app_rule 设置")
        val issues = RuleDiagnostics.validate(appContext, rule)
        val hasError = (0 until issues.length()).any { issues.optJSONObject(it)?.optString("level") == "error" }
        return JSONObject()
            .put("package_name", pkg)
            .put("valid", !hasError)
            .put("issue_count", issues.length())
            .put("issues", issues)
    }

    private fun diagnoseApp(pkg: String): JSONObject {
        if (pkg.isEmpty()) return toolText("package_name 不能为空")
        return RuleDiagnostics.diagnose(appContext, pkg)
    }

    private fun launchApp(args: JSONObject): JSONObject {
        val pkg = args.optString("package_name", "")
        if (pkg.isEmpty()) return toolText("package_name 不能为空")
        val activity = args.optString("activity_name", "")
        val intent = if (activity.isNotEmpty()) {
            android.content.Intent().setClassName(pkg, activity)
        } else {
            appContext.packageManager.getLaunchIntentForPackage(pkg)
        } ?: return toolText("没有找到可启动的入口：$pkg")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            JSONObject()
                .put("launched", true)
                .put("package_name", pkg)
                .put("activity", intent.component?.className ?: JSONObject.NULL)
        } catch (t: Throwable) {
            toolText("启动失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // 应用列表缓存：getInstalledApplications 在应用较多时耗时数百毫秒，60s 内复用
    @Volatile
    private var cachedApps: List<android.content.pm.ApplicationInfo>? = null

    @Volatile
    private var cachedAppsAt = 0L

    private fun installedApps(): List<android.content.pm.ApplicationInfo> {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedApps?.takeIf { now - cachedAppsAt < 60_000 }?.let { return it }
        val fresh = appContext.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        cachedApps = fresh
        cachedAppsAt = now
        return fresh
    }

    private fun searchApps(keyword: String, limit: Int): JSONObject {
        val pm = appContext.packageManager
        val lower = keyword.lowercase(Locale.US)
        val arr = JSONArray()
        val all = installedApps()
        for (info in all) {
            if (arr.length() >= limit.coerceAtLeast(1)) break
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            if (lower.isEmpty() ||
                label.lowercase(Locale.US).contains(lower) ||
                info.packageName.lowercase(Locale.US).contains(lower)
            ) {
                arr.put(
                    JSONObject()
                        .put("packageName", info.packageName)
                        .put("label", label)
                        .put("isSystem", (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                        .put("builtin", JSONArray(SystemRuleSource.kindsOf(info.packageName).map { it.name }))
                )
            }
        }
        return JSONObject().put("count", arr.length()).put("apps", arr)
    }

    private fun listActivities(pkg: String): JSONObject {
        val pm = appContext.packageManager
        val acts = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities }.getOrNull()
            ?: return toolText("没有抓到该应用的页面：$pkg")
        val launcher = runCatching { pm.getLaunchIntentForPackage(pkg)?.component?.className }.getOrNull()
        val names = acts.mapNotNull { it.name }.distinct()
            .sortedWith(compareByDescending<String> { it == launcher }.thenBy { it })
        return JSONObject().put("package_name", pkg)
            .put("launcher", launcher ?: JSONObject.NULL)
            .put("activities", JSONArray(names))
    }

    private fun exportRules(pkg: String): JSONObject {
        val rules = if (pkg.isEmpty()) {
            ConfigRepository.allRules().values.toList()
        } else {
            listOfNotNull(ConfigRepository.rule(pkg))
        }
        if (rules.isEmpty()) return toolText("没有可导出的规则")

        val json = buildRulesJson(rules)
        val dir = File(appContext.getExternalFilesDir(null), "export").apply { mkdirs() }
        val file = File(dir, "magicwindow_${pkg.ifEmpty { "all" }}_${System.currentTimeMillis()}.json")
        file.writeText(json.toString(2))
        return JSONObject()
            .put("exported", rules.size)
            .put("path", file.absolutePath)
            .put("json", json)
    }

    /** 与 ConfigExporter 兼容的导出格式（version 2），MCP 与 App 内查看/复制共用 */
    fun buildRulesJson(rules: List<AppRule>): JSONObject = JSONObject()
        .put("version", 2)
        .put("exportTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        .put("moduleName", "完美横屏")
        .put("modulePackage", Constants.MODULE_PACKAGE)
        .put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
}
