package com.github.lsposed.magicwindow.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * AI 模型配置持久化。
 * 存储格式参考 Trae 的自定义模型配置。
 */
object AiSettings {
    private const val PREFS = "ai_settings"
    private const val KEY_API_BASE = "api_base"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_MAX_INPUT_TOKENS = "max_input_tokens"
    private const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
    private const val KEY_TOOL_ROUNDS = "tool_rounds"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_TOP_P = "top_p"
    private const val KEY_TOP_K = "top_k"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 读取 ──

    fun apiBase(context: Context): String =
        prefs(context).getString(KEY_API_BASE, "") ?: ""

    fun modelId(context: Context): String =
        prefs(context).getString(KEY_MODEL_ID, "") ?: ""

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun displayName(context: Context): String =
        prefs(context).getString(KEY_DISPLAY_NAME, "") ?: ""

    fun maxInputTokens(context: Context): Int =
        prefs(context).getInt(KEY_MAX_INPUT_TOKENS, 131072)

    fun maxOutputTokens(context: Context): Int =
        prefs(context).getInt(KEY_MAX_OUTPUT_TOKENS, 16384)

    fun toolRounds(context: Context): Int =
        prefs(context).getInt(KEY_TOOL_ROUNDS, 25)

    fun temperature(context: Context): Float =
        prefs(context).getFloat(KEY_TEMPERATURE, 0.4f)

    fun topP(context: Context): Float =
        prefs(context).getFloat(KEY_TOP_P, 0.95f)

    fun topK(context: Context): Int =
        prefs(context).getInt(KEY_TOP_K, 20)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /** 是否已配置（有 API 地址和密钥） */
    fun isConfigured(context: Context): Boolean =
        apiKey(context).isNotEmpty() && apiBase(context).isNotEmpty() && modelId(context).isNotEmpty()

    // ── 写入 ──

    fun save(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit().apply(block).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 获取模型显示名称（未设置时返回模型 ID） */
    fun modelDisplayName(context: Context): String {
        val name = displayName(context)
        return if (name.isNotEmpty()) name else modelId(context)
    }
}
