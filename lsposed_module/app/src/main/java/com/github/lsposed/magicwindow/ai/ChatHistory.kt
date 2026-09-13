package com.github.lsposed.magicwindow.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天历史持久化管理。
 * 支持多对话、对话列表、继续历史对话。
 */
object ChatHistory {
    private const val PREFS = "ai_chat_history"
    private const val KEY_CONVERSATIONS = "conversations"

    data class Message(
        val role: String,       // "user" | "assistant"
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
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
                        msgs.add(Message(
                            role = m.getString("role"),
                            content = m.getString("content"),
                            timestamp = m.optLong("timestamp", 0)
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

    /** 删除对话 */
    fun delete(context: Context, conversationId: String) {
        val list = getAll(context).toMutableList()
        list.removeAll { it.id == conversationId }
        saveAll(context, list)
    }

    /** 清空所有对话 */
    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY_CONVERSATIONS).apply()
    }

    /** 生成对话标题（取前20个字符） */
    fun generateTitle(firstMessage: String): String {
        val clean = firstMessage.replace("\n", " ").trim()
        return if (clean.length > 20) clean.substring(0, 20) + "…" else clean
    }
}
