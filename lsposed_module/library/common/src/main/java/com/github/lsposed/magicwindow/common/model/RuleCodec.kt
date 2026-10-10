package com.github.lsposed.magicwindow.common.model

import org.json.JSONArray
import org.json.JSONObject

/** 规则集的 JSON 编解码，app 与 hook 两侧共用，保证格式一致。 */
object RuleCodec {

    fun encodeRules(rules: Collection<AppRule>): String {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /**
     * 解析失败抛出异常（而非静默返回空/半截表）：
     * hook 侧 RuleStore.loadNow 的 runCatching 会保留旧快照并打错误日志，
     * 若吞掉异常会用空表覆盖正常快照、把「读取失败」伪装成「配置被清空」。
     */
    fun decodeRules(text: String?): MutableMap<String, AppRule> {
        val map = LinkedHashMap<String, AppRule>()
        if (text.isNullOrBlank()) return map
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val rule = AppRule.fromJson(arr.getJSONObject(i))
            if (rule.packageName.isNotEmpty()) map[rule.packageName] = rule
        }
        return map
    }
}
