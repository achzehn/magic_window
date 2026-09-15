package com.github.lsposed.magicwindow.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 聊天历史持久化管理。
 * 支持多对话、对话列表、继续历史对话。
 */
object ChatHistory {
    private const val PREFS = "ai_chat_history"
    private const val KEY_CONVERSATIONS = "conversations"

    /** 附件元数据：图片只存本地文件路径（base64 太大不进 SharedPreferences） */
    data class AttachmentMeta(
        val type: String,       // "image" | "text"
        val name: String,
        val localPath: String? = null
    )

    data class Message(
        val role: String,       // "user" | "assistant"
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val attachments: List<AttachmentMeta> = emptyList(),
        /** 该条消息为上下文压缩摘要（展示用 AI 样式，协议上作为 user 发送） */
        val isSummary: Boolean = false
    )

    data class Conversation(
        val id: String = java.util.UUID.randomUUID().toString(),
        val title: String,
        val modelId: String = "",
        val messages: MutableList<Message> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastModified: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("modelId", modelId)
            put("createdAt", createdAt)
            put("lastModified", lastModified)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        if (msg.isSummary) put("isSummary", true)
                        if (msg.attachments.isNotEmpty()) {
                            put("attachments", JSONArray().apply {
                                msg.attachments.forEach { att ->
                                    put(JSONObject().apply {
                                        put("type", att.type)
                                        put("name", att.name)
                                        att.localPath?.let { put("localPath", it) }
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }

        companion object {
            fun fromJson(o: JSONObject): Conversation {
            val msgs = mutableListOf<Message>()
            val arr = o.optJSONArray("messages")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val atts = mutableListOf<AttachmentMeta>()
                    m.optJSONArray("attachments")?.let { attArr ->
                        for (j in 0 until attArr.length()) {
                            val a = attArr.getJSONObject(j)
                            atts.add(AttachmentMeta(
                                type = a.optString("type", ""),
                                name = a.optString("name", ""),
                                localPath = a.optString("localPath", "").ifEmpty { null }
                            ))
                        }
                    }
                    msgs.add(Message(
                        role = m.getString("role"),
                        content = m.getString("content"),
                        timestamp = m.optLong("timestamp", 0),
                        attachments = atts,
                        isSummary = m.optBoolean("isSummary", false)
                    ))
                }
            }
                return Conversation(
                    id = o.optString("id", java.util.UUID.randomUUID().toString()),
                    title = o.optString("title", ""),
                    modelId = o.optString("modelId", ""),
                    messages = msgs,
                    createdAt = o.optLong("createdAt", 0),
                    lastModified = o.optLong("lastModified", 0)
                )
            }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 获取所有对话（按最后修改时间倒序） */
    fun getAll(context: Context): List<Conversation> {
        val json = prefs(context).getString(KEY_CONVERSATIONS, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length())
            .map { Conversation.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.lastModified }
    }

    /** 保存所有对话 */
    private fun saveAll(context: Context, conversations: List<Conversation>) {
        val arr = JSONArray()
        conversations.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_CONVERSATIONS, arr.toString()).apply()
    }

    /** 获取单个对话 */
    fun get(context: Context, conversationId: String): Conversation? {
        return getAll(context).find { it.id == conversationId }
    }

    /** 创建新对话 */
    fun create(context: Context, title: String, modelId: String = ""): Conversation {
        val conv = Conversation(title = title, modelId = modelId)
        val list = getAll(context).toMutableList()
        list.add(0, conv)
        saveAll(context, list)
        return conv
    }

    /** 保存对话（更新或新增） */
    fun save(context: Context, conversation: Conversation) {
        conversation.lastModified = System.currentTimeMillis()
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == conversation.id }
        if (idx >= 0) {
            list[idx] = conversation
        } else {
            list.add(0, conversation)
        }
        saveAll(context, list)
    }

    /** 删除对话（联动删除对话内图片附件的本地文件） */
    fun delete(context: Context, conversationId: String) {
        val list = getAll(context).toMutableList()
        list.firstOrNull { it.id == conversationId }?.let { deleteImageFiles(it) }
        list.removeAll { it.id == conversationId }
        saveAll(context, list)
    }

    /** 清空所有对话（联动清空图片附件目录） */
    fun clearAll(context: Context) {
        getAll(context).forEach { deleteImageFiles(it) }
        prefs(context).edit().remove(KEY_CONVERSATIONS).apply()
    }

    /** 清理未被任何对话引用的孤儿图片文件（如发送后未保存历史就退出） */
    fun pruneOrphanImages(context: Context) {
        val dir = File(context.filesDir, "chat_images")
        val files = dir.listFiles() ?: return
        val referenced = getAll(context)
            .flatMap { it.messages }
            .flatMap { it.attachments }
            .mapNotNull { it.localPath }
            .toSet()
        files.forEach { f ->
            if (f.absolutePath !in referenced) runCatching { f.delete() }
        }
    }

    private fun deleteImageFiles(conv: Conversation) {
        conv.messages.flatMap { it.attachments }
            .mapNotNull { it.localPath }
            .forEach { runCatching { File(it).delete() } }
    }

    /** 生成对话标题（取前20个字符） */
    fun generateTitle(firstMessage: String): String {
        val clean = firstMessage.replace("\n", " ").trim()
        return if (clean.length > 20) clean.substring(0, 20) + "…" else clean
    }
}
