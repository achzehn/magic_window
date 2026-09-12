package com.github.lsposed.magicwindow.mcp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.data.ConfigRepository
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
 * 提供的工具：
 *   list_rules / get_rule / set_rule / delete_rule
 *   get_system_rule / search_apps / list_activities / export_rules
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
     * 生成 MCP 客户端接入配置 JSON（两种方式二选一粘贴）：
     *   magic-window                → 支持 HTTP 直连 + 自定义 header 的客户端（Trae / Cursor / Cline 等）
     *   magic-window-claude-desktop → Claude Desktop 等不支持 headers 字段的客户端，走 mcp-remote 中转
     * 令牌为空时不带鉴权头。
     */
    fun clientConfigJson(): String {
        val lan = localUrl()
        val auth = "Bearer ${token(appContext)}"
        val direct = JSONObject().put("url", lan)
        if (auth != "Bearer ") direct.put("headers", JSONObject().put("Authorization", auth))
        val args = JSONArray().put("-y").put("mcp-remote").put(lan)
        if (auth != "Bearer ") args.put("--header").put(auth)
        return JSONObject()
            .put("mcpServers", JSONObject()
                .put("magic-window", direct)
                .put("magic-window-claude-desktop", JSONObject()
                    .put("command", "npx")
                    .put("args", args)))
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
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val first = reader.readLine() ?: return@use
                    val expected = token(appContext)
                    var contentLength = 0
                    var authorized = expected.isEmpty()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val lower = line.lowercase(Locale.US)
                        if (lower.startsWith("content-length:")) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        // 令牌为空（不校验）时忽略请求携带的任何 Authorization 头
                        if (expected.isNotEmpty() && lower.startsWith("authorization:")) {
                            authorized = line.substringAfter(':').trim() == "Bearer $expected"
                        }
                    }
                    val body = CharArray(contentLength).let { buf ->
                        val n = reader.read(buf)
                        if (n <= 0) "" else String(buf, 0, n)
                    }
                    if (!authorized) {
                        write(s, 401, """{"error":"unauthorized"}""")
                        return@use
                    }
                    val isOptions = first.startsWith("OPTIONS")
                    when {
                        isOptions -> {
                            s.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 204 No Content\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n" +
                                        "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                                        "Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id\r\n\r\n")
                                        .toByteArray()
                                )
                                flush()
                            }
                        }

                        first.startsWith("POST") -> write(s, 200, handleRpc(body))
                        else -> write(s, 405, """{"error":"POST only"}""")
                    }
                }
            }
        }, "MagicWindow-MCP-conn").apply { isDaemon = true }.start()
    }

    private fun write(socket: Socket, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray() + bytes)
            flush()
        }
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

    private fun toolsList(): JSONArray = JSONArray().put(
        toolDef(
            "list_rules", "列出本模块已配置的全部应用规则",
            JSONObject().put("type", "object").put("properties", JSONObject())
        )
    ).put(
        toolDef(
            "get_rule", "读取某个应用的模块规则（未设置时返回 null）",
            JSONObject().put("type", "object").put("required", JSONArray().put("package_name"))
                .put("properties", JSONObject().put(
                    "package_name", JSONObject().put("type", "string").put("description", "应用包名")
                ))
        )
    ).put(
        toolDef(
            "set_rule", "创建或更新某个应用的规则。字段与规则文件属性同名（mode: off/fullScreen/embedding/fixedOrientation），只传需要修改的字段，未传字段保持现有值。保存后立即热生效",
            JSONObject().put("type", "object").put("required", JSONArray().put("package_name"))
                .put("properties", JSONObject()
                    .put("package_name", JSONObject().put("type", "string"))
                    .put("rule", JSONObject().put("type", "object")
                        .put("description", "规则字段键值对，如 {\"mode\":\"fullScreen\",\"fullRule\":\"nra:cr:rcr:nr\"}"))
                )
        )
    ).put(
        toolDef(
            "delete_rule", "删除某个应用的模块规则（恢复系统内置行为）",
            JSONObject().put("type", "object").put("required", JSONArray().put("package_name"))
                .put("properties", JSONObject().put(
                    "package_name", JSONObject().put("type", "string")
                ))
        )
    ).put(
        toolDef(
            "get_system_rule", "读取系统内置规则（平行窗口/固定横屏/界面适配三个名单的原始属性）",
            JSONObject().put("type", "object").put("required", JSONArray().put("package_name"))
                .put("properties", JSONObject().put(
                    "package_name", JSONObject().put("type", "string")
                ))
        )
    ).put(
        toolDef(
            "search_apps", "按关键词搜索已安装应用",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("keyword", JSONObject().put("type", "string"))
                    .put("limit", JSONObject().put("type", "integer").put("default", 20)))
        )
    ).put(
        toolDef(
            "list_activities", "列出某个应用的全部页面（Activity 全类名，启动页排最前），用于填 activityRule / splitPairRule 等页面字段",
            JSONObject().put("type", "object").put("required", JSONArray().put("package_name"))
                .put("properties", JSONObject().put(
                    "package_name", JSONObject().put("type", "string")
                ))
        )
    ).put(
        toolDef(
            "export_rules", "把规则导出为本地 JSON 文件。不传 package_name 导出全部；传则只导出该应用",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put(
                    "package_name", JSONObject().put("type", "string")
                ))
        )
    )

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
        "list_rules" -> listRules()
        "get_rule" -> getRule(args.optString("package_name"))
        "set_rule" -> setRule(args.optString("package_name"), args.optJSONObject("rule"))
        "delete_rule" -> deleteRule(args.optString("package_name"))
        "get_system_rule" -> systemRule(args.optString("package_name"))
        "search_apps" -> searchApps(args.optString("keyword"), args.optInt("limit", 20))
        "list_activities" -> listActivities(args.optString("package_name"))
        "export_rules" -> exportRules(args.optString("package_name", ""))
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

    private fun setRule(pkg: String, updates: JSONObject?): JSONObject {
        if (pkg.isEmpty()) return toolText("package_name 不能为空")
        val base = ConfigRepository.rule(pkg)?.toJson() ?: JSONObject()
        base.put("packageName", pkg)
        updates?.let { up ->
            up.keys().forEachRemaining { key ->
                if (key != "packageName") base.put(key, up.get(key))
            }
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

    private fun searchApps(keyword: String, limit: Int): JSONObject {
        val pm = appContext.packageManager
        val lower = keyword.lowercase(Locale.US)
        val arr = JSONArray()
        val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
