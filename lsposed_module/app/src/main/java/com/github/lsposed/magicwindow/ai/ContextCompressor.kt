package com.github.lsposed.magicwindow.ai

import android.util.Log

/**
 * 上下文压缩器：以模型高级设置中的 maxInputTokens（上下文窗口）为上限，
 * 估算对话 token 占用，达到阈值时调用 AI 将前文摘要压缩，防止请求超出上下文窗口。
 *
 * 触发时机：每次发起请求前检查；压缩后旧消息（含图片 base64）被一条摘要替换，
 * 摘要同步写入聊天历史，恢复历史对话后上下文仍然紧凑。
 */
object ContextCompressor {

    /** 触发压缩的阈值（占 maxInputTokens 的比例） */
    private const val TRIGGER_RATIO = 0.85f

    /** 压缩后的目标占用（给后续对话留出头） */
    private const val TARGET_RATIO = 0.5f

    /** 每张图片的估算 token（1280px 视觉编码，主流多模态模型约 1k~1.7k） */
    private const val TOKENS_PER_IMAGE = 1200

    /** 工具定义等请求固定开销的粗略估算 */
    private const val TOOLS_OVERHEAD = 2500

    /** 压缩时至少保留的最近消息条数（保证本次用户消息与上一轮回复完整） */
    private const val MIN_KEEP_RECENT = 2

    private const val TAG = "ContextCompressor"

    /**
     * 粗略估算文本 token 数：CJK 字符约 1 token/字，其余约 4 字符/token。
     * 偏保守（估高不估低），避免低估导致超限。
     */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) {
            when {
                c.code in 0x2E80..0x9FFF || c.code in 0x3000..0x303F ||
                    c.code in 0xFF00..0xFFEF || c.code in 0xAC00..0xD7AF -> cjk++
                c.isWhitespace() -> Unit
                else -> other++
            }
        }
        return cjk + (other + 3) / 4
    }

    /** 估算整段对话（含工具定义固定开销与图片）的 token 占用 */
    fun estimateMessages(messages: List<AiClient.ChatMessage>, includeToolsOverhead: Boolean = true): Int {
        var total = if (includeToolsOverhead) TOOLS_OVERHEAD else 0
        messages.forEach { m ->
            total += estimateTokens(m.content ?: "")
            total += m.images.size * TOKENS_PER_IMAGE
            m.toolCalls?.let { total += estimateTokens(it.toString()) }
        }
        return total
    }

    /** 是否达到压缩阈值 */
    fun shouldCompress(messages: List<AiClient.ChatMessage>, cfg: ModelManager.ModelConfig): Boolean =
        estimateMessages(messages) > (cfg.maxInputTokens * TRIGGER_RATIO).toInt()

    data class Result(
        /** 压缩后的完整消息列表（含 system） */
        val messages: List<AiClient.ChatMessage>,
        /** 摘要文本；null 表示 AI 摘要失败、已降级为直接丢弃旧消息 */
        val summary: String?,
        /** 被压缩（移除）的消息条数；0 表示无需压缩 */
        val removedCount: Int,
        val beforeTokens: Int,
        val afterTokens: Int
    )

    /**
     * 压缩消息列表：保留 system 与最近消息（约 TARGET_RATIO 以内），
     * 其余旧消息由 AI 总结为一条 user 角色摘要（旧图片随之释放，不再占用上下文）。
     *
     * @param complete 单轮补全调用（不带工具），用于生成摘要
     */
    suspend fun compress(
        messages: List<AiClient.ChatMessage>,
        cfg: ModelManager.ModelConfig,
        complete: suspend (List<AiClient.ChatMessage>) -> String
    ): Result {
        val before = estimateMessages(messages)
        val target = (cfg.maxInputTokens * TARGET_RATIO).toInt()

        // system 提示词固定在头部，不参与压缩
        val system = messages.take(1).filter { it.role == "system" }
        val body = messages.drop(system.size)

        // 从尾部保留最近消息，累加到接近目标为止
        var acc = estimateMessages(system)
        val keepList = mutableListOf<AiClient.ChatMessage>()
        for (m in body.asReversed()) {
            val cost = estimateMessages(listOf(m), includeToolsOverhead = false)
            if (keepList.size >= MIN_KEEP_RECENT && acc + cost > target) break
            keepList.add(m)
            acc += cost
        }
        val kept = keepList.asReversed()
        val removed = body.size - kept.size
        if (removed <= 0) {
            // 最近几条消息本身就超限，没有可压缩空间
            return Result(messages, null, 0, before, before)
        }

        val toSummarize = body.subList(0, removed)
        val summary = runCatching { summarize(toSummarize, complete) }.onFailure {
            Log.w(TAG, "AI 摘要失败，降级为直接丢弃旧消息", it)
        }.getOrNull()

        val newBody = mutableListOf<AiClient.ChatMessage>()
        if (summary != null) {
            newBody.add(AiClient.ChatMessage("user", "[前文摘要] $summary"))
        }
        newBody.addAll(kept)
        val newMessages = system + newBody
        return Result(newMessages, summary, removed, before, estimateMessages(newMessages))
    }

    /** 调用 AI 把旧对话压缩为中文摘要；失败抛异常由调用方降级 */
    private suspend fun summarize(
        old: List<AiClient.ChatMessage>,
        complete: suspend (List<AiClient.ChatMessage>) -> String
    ): String {
        val sb = StringBuilder()
        old.forEach { m ->
            val text = m.content.orEmpty()
            if (m.images.isNotEmpty()) {
                sb.appendLine("[${m.role}] $text（附 ${m.images.size} 张图片）")
            } else {
                sb.appendLine("[${m.role}] $text")
            }
        }
        val prompt = buildString {
            append("以下是一段用户与 AI 助手的对话历史。请将其压缩为简洁的中文摘要，要求：\n")
            append("1. 保留用户的适配目标与偏好；\n")
            append("2. 保留所有已确认/已保存的规则配置（应用包名、模式、关键参数值，务必原样保留参数）；\n")
            append("3. 保留未完成的待办与约定；\n")
            append("4. 丢弃寒暄与重复内容；\n")
            append("5. 直接输出摘要正文，不要加「摘要：」前缀，不要寒暄。\n\n")
            append(sb.toString().take(60_000))
        }
        val out = complete(listOf(AiClient.ChatMessage("user", prompt))).trim()
        if (out.isEmpty()) throw IllegalStateException("摘要为空")
        return out
    }
}
