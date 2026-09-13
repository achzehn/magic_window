package com.github.lsposed.magicwindow.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多模型管理器：支持增删改查、切换当前模型。
 * 参考 Trae IDE 的模型管理方式。
 */
object ModelManager {
    private const val PREFS = "ai_models"
    private const val KEY_MODELS = "models"
    private const val KEY_CURRENT_ID = "current_id"

    data class ModelConfig(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val apiBase: String,
        val modelId: String,
        val apiKey: String,
        val maxInputTokens: Int = 131072,
        val maxOutputTokens: Int = 16384,
        val toolRounds: Int = 25,
        val temperature: Float = 0.4f,
        val topP: Float = 0.95f,
        val topK: Int = 20,
        val enabled: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("apiBase", apiBase)
            put("modelId", modelId)
            put("apiKey", apiKey)
            put("maxInputTokens", maxInputTokens)
            put("maxOutputTokens", maxOutputTokens)
            put("toolRounds", toolRounds)
            put("temperature", temperature.toDouble())
            put("topP", topP.toDouble())
            put("topK", topK)
            put("enabled", enabled)
        }

        companion object {
            fun fromJson(o: JSONObject): ModelConfig = ModelConfig(
                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                apiBase = o.optString("apiBase", ""),
                modelId = o.optString("modelId", ""),
                apiKey = o.optString("apiKey", ""),
                maxInputTokens = o.optInt("maxInputTokens", 131072),
                maxOutputTokens = o.optInt("maxOutputTokens", 16384),
                toolRounds = o.optInt("toolRounds", 25),
                temperature = o.optDouble("temperature", 0.4).toFloat(),
                topP = o.optDouble("topP", 0.95).toFloat(),
                topK = o.optInt("topK", 20),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 获取所有模型 */
    fun getAll(context: Context): List<ModelConfig> {
        val json = prefs(context).getString(KEY_MODELS, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { ModelConfig.fromJson(arr.getJSONObject(it)) }
    }

    /** 获取所有已启用的模型 */
    fun getEnabled(context: Context): List<ModelConfig> =
        getAll(context).filter { it.enabled }

    /** 获取当前选中的模型（停用的模型不会返回） */
    fun getCurrent(context: Context): ModelConfig? {
        val currentId = prefs(context).getString(KEY_CURRENT_ID, null)
        val enabled = getEnabled(context)
        return if (currentId != null) {
            enabled.find { it.id == currentId } ?: enabled.firstOrNull()
        } else {
            enabled.firstOrNull()
        }
    }

    /** 保存模型列表 */
    fun saveAll(context: Context, models: List<ModelConfig>) {
        val arr = JSONArray()
        models.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_MODELS, arr.toString()).apply()
    }

    /** 添加模型 */
    fun add(context: Context, model: ModelConfig): ModelConfig {
        val models = getAll(context).toMutableList()
        models.add(model)
        saveAll(context, models)
        // 如果是第一个模型，自动设为当前
        if (models.size == 1) {
            setCurrent(context, model.id)
        }
        return model
    }

    /** 更新模型 */
    fun update(context: Context, model: ModelConfig) {
        val models = getAll(context).toMutableList()
        val idx = models.indexOfFirst { it.id == model.id }
        if (idx >= 0) {
            models[idx] = model
            saveAll(context, models)
        }
    }

    /** 删除模型 */
    fun delete(context: Context, modelId: String) {
        val wasCurrent = prefs(context).getString(KEY_CURRENT_ID, null) == modelId
        val models = getAll(context).toMutableList()
        models.removeAll { it.id == modelId }
        saveAll(context, models)
        // 如果删除的是当前模型，切换到第一个已启用模型
        if (wasCurrent) {
            setCurrent(context, models.firstOrNull { it.enabled }?.id)
        }
    }

    /** 设置当前模型 */
    fun setCurrent(context: Context, modelId: String?) {
        prefs(context).edit().putString(KEY_CURRENT_ID, modelId).apply()
    }

    /** 是否有任何模型 */
    fun hasAny(context: Context): Boolean = getAll(context).isNotEmpty()

    /** 获取当前模型的显示名称 */
    fun currentDisplayName(context: Context): String {
        val model = getCurrent(context)
        return model?.name ?: model?.modelId ?: "未配置"
    }
}
