package com.github.lsposed.magicwindow.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 客户端：兼容 OpenAI Chat Completions 格式，支持 function calling 与多模态图片。
 *
 * 使用方式：
 *   val client = AiClient(context)
 *   val reply = client.chat(messages) { toolName, toolArgs ->
 *       AiToolExecutor.execute(context, toolName, toolArgs)
 *   }
 *
 * 配置统一取自 [ModelManager] 当前启用的模型。
 */
class AiClient(private val context: Context) {

    data class ChatMessage(
        val role: String,       // "system" | "user" | "assistant" | "tool"
        val content: String? = null,
        val toolCalls: JSONArray? = null,
        val toolCallId: String? = null,
        val name: String? = null,
        /** 多模态图片，元素为 data URI（data:image/...;base64,xxxx），仅对 user 消息有意义 */
        val images: List<String> = emptyList()
    )

    /**
     * 发送对话请求，自动处理 function calling 循环。
     * @param messages 对话历史
     * @param onToolCall 工具调用回调，返回工具执行结果
     * @return 助手的最终回复文本
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        onToolCall: suspend (name: String, args: JSONObject) -> String
    ): String = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        val allMessages = messages.toMutableList()
        var rounds = 0

        while (rounds < cfg.toolRounds) {
            rounds++

            val response = httpPost(
                "${cfg.apiBase.trimEnd('/')}/chat/completions",
                cfg.apiKey,
                buildRequestBody(cfg, allMessages, includeTools = true)
            )
            val responseJson = JSONObject(response)

            if (responseJson.has("error")) {
                val errMsg = responseJson.getJSONObject("error").optString("message", "未知错误")
                throw RuntimeException("API 错误: $errMsg")
            }

            val choices = responseJson.optJSONArray("choices")
                ?: throw RuntimeException("API 返回格式异常：缺少 choices")
            if (choices.length() == 0) throw RuntimeException("API 返回空结果")

            val first = choices.getJSONObject(0)
            val message = first.getJSONObject("message")
            val finishReason = first.optString("finish_reason", "")

            // 如果没有 tool_calls，返回最终回复
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0 || finishReason == "stop") {
                return@withContext message.optString("content", "")
            }

            // 有 tool_calls：执行工具并继续对话
            allMessages.add(ChatMessage(
                role = "assistant",
                content = if (message.isNull("content")) null else message.optString("content", null),
                toolCalls = toolCalls
            ))

            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                val toolName = fn.getString("name")
                val toolArgs = try {
                    JSONObject(fn.optString("arguments", "{}"))
                } catch (_: Exception) {
                    JSONObject()
                }
                val toolCallId = tc.getString("id")

                // 执行工具
                val result = onToolCall(toolName, toolArgs)

                allMessages.add(ChatMessage(
                    role = "tool",
                    content = result,
                    toolCallId = toolCallId,
                    name = toolName
                ))
            }
        }

        return@withContext "（工具调用轮数已达上限，请简化请求后重试）"
    }

    /**
     * 单轮补全：不带工具定义，请求体更小、响应更快。
     * 用于页面抓取的批量标注 / 中文补全等固定格式任务。
     */
    suspend fun complete(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        val response = httpPost(
            "${cfg.apiBase.trimEnd('/')}/chat/completions",
            cfg.apiKey,
            buildRequestBody(cfg, messages, includeTools = false)
        )
        val json = JSONObject(response)
        if (json.has("error")) {
            val errMsg = json.getJSONObject("error").optString("message", "未知错误")
            throw RuntimeException("API 错误: $errMsg")
        }
        val choices = json.optJSONArray("choices")
            ?: throw RuntimeException("API 返回格式异常：缺少 choices")
        if (choices.length() == 0) throw RuntimeException("API 返回空结果")
        choices.getJSONObject(0).getJSONObject("message").optString("content", "")
    }

    /** 当前启用的模型；未配置时给出明确提示 */
    private fun currentConfig(): ModelManager.ModelConfig {
        return ModelManager.getCurrent(context)
            ?: throw IllegalStateException("未配置可用模型，请先在「模型管理」中添加并测试通过")
    }

    /** 构建 OpenAI Chat Completions 请求体；图片以多模态 content 数组发送 */
    private fun buildRequestBody(
        cfg: ModelManager.ModelConfig,
        messages: List<ChatMessage>,
        includeTools: Boolean,
        stream: Boolean = false
    ): String {
        val body = JSONObject().apply {
            put("model", cfg.modelId)
            put("max_tokens", cfg.maxOutputTokens)
            put("temperature", cfg.temperature.toDouble())
            put("top_p", cfg.topP.toDouble())
            put("top_k", cfg.topK)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        when {
                            // 多模态：文本 + 图片
                            msg.images.isNotEmpty() -> put("content", JSONArray().apply {
                                put(JSONObject().put("type", "text")
                                    .put("text", msg.content ?: "请分析这张图片"))
                                msg.images.forEach { uri ->
                                    put(JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", uri)))
                                }
                            })
                            msg.content != null -> put("content", msg.content)
                        }
                        msg.toolCalls?.let { put("tool_calls", it) }
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        msg.name?.let { put("name", it) }
                    })
                }
            })
            if (stream) put("stream", true)
            if (includeTools) {
                put("tools", AiToolExecutor.toolDefinitions)
            }
        }
        return body.toString()
    }

    /**
     * 测试 API 连通性：发送一条简单消息，验证 API 地址、密钥、模型是否正确。
     * @return 成功以「成功」开头，失败以「失败」开头
     */
    suspend fun testConnection(
        apiBase: String,
        modelId: String,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext "错误：API 密钥为空"
        if (apiBase.isEmpty()) return@withContext "错误：API 地址为空"
        if (modelId.isEmpty()) return@withContext "错误：模型 ID 为空"

        try {
            val body = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", 50)
                put("temperature", 0.1)
                put("messages", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("content", "你好，请回复「连接成功」")))
            }.toString()
            val response = httpPost("${apiBase.trimEnd('/')}/chat/completions", apiKey, body)
            val json = JSONObject(response)
            if (json.has("error")) {
                val msg = json.getJSONObject("error").optString("message", "未知错误")
                return@withContext "失败：$msg"
            }
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", "")
                return@withContext "成功：$content"
            }
            return@withContext "失败：API 返回空结果"
        } catch (e: Exception) {
            return@withContext "失败：${e.message}"
        }
    }

    // ── HTTP ──

    /** 当前活动的连接，用于用户手动停止时强制断开 */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /** 用户手动停止：断开当前流式请求的底层连接，解除读阻塞 */
    fun cancelActiveRequest() {
        activeConn?.disconnect()
    }

    private fun openPost(url: String, apiKey: String, body: String, accept: String): HttpURLConnection {
        android.util.Log.d("AiClient", "POST $url, body=${body.length} chars")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", accept)
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        return conn
    }

    /**
     * 流式对话（SSE）：token 逐段到达，连接保持活跃，
     * 解决移动网络下非流式请求长时间无数据被网关掐断的问题。
     * @param onDelta 每收到一段文本回调一次（顺序保证）
     * @param onToolCall 工具调用回调
     * @return 完整回复文本
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        onDelta: suspend (String) -> Unit,
        onToolCall: suspend (name: String, args: JSONObject) -> String
    ): String = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        val allMessages = messages.toMutableList()
        var rounds = 0

        while (rounds < cfg.toolRounds) {
            rounds++
            ensureActive()

            val conn = openPost(
                "${cfg.apiBase.trimEnd('/')}/chat/completions",
                cfg.apiKey,
                buildRequestBody(cfg, allMessages, includeTools = true, stream = true),
                accept = "text/event-stream"
            )
            activeConn = conn
            try {
                val code = conn.responseCode
                val respStream = if (code in 200..299) conn.inputStream else conn.errorStream
                if (code !in 200..299) {
                    val errText = respStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw RuntimeException(
                        if (errText.isBlank()) "HTTP $code（服务无响应内容，请检查请求地址或模型参数）"
                        else "HTTP $code: ${errText.take(500)}"
                    )
                }

                val content = StringBuilder()
                val toolIds = LinkedHashMap<Int, String>()
                val toolNames = LinkedHashMap<Int, String>()
                val toolArgs = LinkedHashMap<Int, StringBuilder>()
                var finish = ""

                val reader = BufferedReader(InputStreamReader(respStream, Charsets.UTF_8))
                var line = reader.readLine()
                while (line != null) {
                    ensureActive()
                    val t = line.trim()
                    if (t.isNotEmpty()) {
                        val isSse = t.startsWith("data:")
                        val payload = if (isSse) t.removePrefix("data:").trim() else t
                        if (payload == "[DONE]") break
                        if (payload.startsWith("{")) {
                            try {
                                val json = JSONObject(payload)
                                json.optJSONObject("error")?.let {
                                    throw RuntimeException("API 错误: ${it.optString("message", "未知错误")}")
                                }
                                // 网关不支持 stream 时会原样返回普通 JSON
                                if (!isSse && json.has("choices")) {
                                    val msg = json.optJSONArray("choices")
                                        ?.optJSONObject(0)?.optJSONObject("message")
                                    if (msg != null) {
                                        if (!msg.isNull("content")) content.append(msg.optString("content"))
                                        finish = "stop"
                                        break
                                    }
                                }
                                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                                if (choice != null) {
                                    if (!choice.isNull("finish_reason")) {
                                        val fr = choice.optString("finish_reason")
                                        if (fr.isNotEmpty()) finish = fr
                                    }
                                    val delta = choice.optJSONObject("delta")
                                    if (delta != null) {
                                        if (!delta.isNull("content")) {
                                            val piece = delta.optString("content")
                                            if (piece.isNotEmpty()) {
                                                content.append(piece)
                                                onDelta(piece)
                                            }
                                        }
                                        val tcs = delta.optJSONArray("tool_calls")
                                        if (tcs != null) {
                                            for (i in 0 until tcs.length()) {
                                                val tc = tcs.getJSONObject(i)
                                                val idx = tc.optInt("index", 0)
                                                if (!tc.isNull("id")) {
                                                    val id = tc.optString("id")
                                                    if (id.isNotEmpty()) toolIds[idx] = id
                                                }
                                                val fn = tc.optJSONObject("function")
                                                if (fn != null) {
                                                    if (!fn.isNull("name")) {
                                                        val n = fn.optString("name")
                                                        if (n.isNotEmpty()) toolNames[idx] = (toolNames[idx] ?: "") + n
                                                    }
                                                    if (!fn.isNull("arguments")) {
                                                        toolArgs.getOrPut(idx) { StringBuilder() }
                                                            .append(fn.optString("arguments"))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: RuntimeException) {
                                throw e
                            } catch (_: Exception) {
                                // 非 JSON 行（如注释、心跳），跳过
                            }
                        }
                    }
                    line = reader.readLine()
                }
                reader.close()

                if (finish == "tool_calls" || toolIds.isNotEmpty()) {
                    // 组装 assistant 的 tool_calls 消息并执行工具，进入下一轮
                    val order = toolIds.keys.sorted()
                    val tcArr = JSONArray()
                    order.forEach { idx ->
                        tcArr.put(JSONObject().apply {
                            put("id", toolIds[idx] ?: "call_$idx")
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", toolNames[idx] ?: "")
                                put("arguments", toolArgs[idx]?.toString() ?: "{}")
                            })
                        })
                    }
                    allMessages.add(ChatMessage(
                        role = "assistant",
                        content = content.toString().ifEmpty { null },
                        toolCalls = tcArr
                    ))
                    order.forEach { idx ->
                        val name = toolNames[idx] ?: return@forEach
                        val args = try {
                            JSONObject(toolArgs[idx]?.toString() ?: "{}")
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        val result = onToolCall(name, args)
                        allMessages.add(ChatMessage(
                            role = "tool",
                            content = result,
                            toolCallId = toolIds[idx] ?: "call_$idx",
                            name = name
                        ))
                    }
                    continue
                }

                return@withContext content.toString()
            } catch (e: Exception) {
                android.util.Log.e("AiClient", "流式请求失败: ${e.javaClass.simpleName}: ${e.message}", e)
                throw e
            } finally {
                activeConn = null
                conn.disconnect()
            }
        }

        return@withContext "（工具调用轮数已达上限，请简化请求后重试）"
    }

    private fun httpPost(url: String, apiKey: String, body: String): String {
        val conn = openPost(url, apiKey, body, accept = "application/json")
        try {
            val code = conn.responseCode
            // errorStream 在部分网关上可能为 null（空错误体），需要安全读取
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            if (code !in 200..299) {
                val snippet = text.take(500)
                android.util.Log.e("AiClient", "HTTP $code url=$url body=$snippet")
                throw RuntimeException(
                    if (snippet.isBlank()) "HTTP $code（服务无响应内容，请检查请求地址或模型参数）"
                    else "HTTP $code: $snippet"
                )
            }
            return text
        } catch (e: Exception) {
            // 保留真实异常类型与堆栈，便于排查“网络或服务异常”这类 message=null 的问题
            android.util.Log.e("AiClient", "请求失败: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
