package com.github.lsposed.magicwindow.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 客户端：兼容 OpenAI Chat Completions 格式，支持 function calling。
 *
 * 使用方式：
 *   val client = AiClient(context)
 *   val reply = client.chat(messages) { toolName, toolArgs ->
 *       AiToolExecutor.execute(context, toolName, toolArgs)
 *   }
 */
class AiClient(private val context: Context) {

    data class ChatMessage(
        val role: String,       // "system" | "user" | "assistant" | "tool"
        val content: String? = null,
        val toolCalls: JSONArray? = null,
        val toolCallId: String? = null,
        val name: String? = null
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
        val apiBase = AiSettings.apiBase(context).trimEnd('/')
        val modelId = AiSettings.modelId(context)
        val apiKey = AiSettings.apiKey(context)
        val maxTokens = AiSettings.maxOutputTokens(context)
        val temperature = AiSettings.temperature(context)
        val topP = AiSettings.topP(context)
        val topK = AiSettings.topK(context)
        val maxRounds = AiSettings.toolRounds(context)

        if (apiKey.isEmpty()) throw IllegalStateException("请先配置 API 密钥")

        val allMessages = messages.toMutableList()
        var rounds = 0

        while (rounds < maxRounds) {
            rounds++

            // 构建请求
            val body = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", maxTokens)
                put("temperature", temperature.toDouble())
                put("top_p", topP.toDouble())
                put("top_k", topK)
                put("messages", JSONArray().apply {
                    allMessages.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", msg.role)
                            msg.content?.let { put("content", it) }
                            msg.toolCalls?.let { put("tool_calls", it) }
                            msg.toolCallId?.let { put("tool_call_id", it) }
                            msg.name?.let { put("name", it) }
                        })
                    }
                })
                // 附带工具定义
                put("tools", AiToolExecutor.toolDefinitions)
            }

            // 发送请求
            val response = httpPost("$apiBase/chat/completions", apiKey, body.toString())
            val responseJson = JSONObject(response)

            if (responseJson.has("error")) {
                val errMsg = responseJson.getJSONObject("error").optString("message", "未知错误")
                throw RuntimeException("API 错误: $errMsg")
            }

            val choices = responseJson.getJSONArray("choices")
            if (choices.length() == 0) throw RuntimeException("API 返回空结果")

            val message = choices.getJSONObject(0).getJSONObject("message")
            val finishReason = choices.getJSONObject(0).optString("finish_reason", "")

            // 如果没有 tool_calls，返回最终回复
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0 || finishReason == "stop") {
                return@withContext message.optString("content", "")
            }

            // 有 tool_calls：执行工具并继续对话
            allMessages.add(ChatMessage(
                role = "assistant",
                content = message.optString("content", null),
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
     * 测试 API 连通性：发送一条简单消息，验证 API 地址、密钥、模型是否正确。
     * @return 成功返回模型回复文本，失败返回错误信息
     */
    suspend fun testConnection(
        apiBase: String = AiSettings.apiBase(context).trimEnd('/'),
        modelId: String = AiSettings.modelId(context),
        apiKey: String = AiSettings.apiKey(context)
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
            }
            val response = httpPost("$apiBase/chat/completions", apiKey, body.toString())
            val json = JSONObject(response)
            if (json.has("error")) {
                val msg = json.getJSONObject("error").optString("message", "未知错误")
                return@withContext "失败：$msg"
            }
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
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

    private fun httpPost(url: String, apiKey: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (code !in 200..299) {
                throw RuntimeException("HTTP $code: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
