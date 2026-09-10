package com.github.lsposed.magicwindow.data

import android.content.Context
import android.net.Uri
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.GlobalConfig
import com.github.lsposed.magicwindow.common.model.RuleCodec
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * 配置导入导出工具
 */
object ConfigExporter {

    /**
     * 配置数据模型
     */
    data class ExportData(
        val version: Int,
        val exportTime: String,
        val moduleName: String,
        val modulePackage: String,
        val globalConfig: GlobalConfig,
        val rules: List<AppRule>
    )

    /**
     * 导出配置到 JSON 文件
     */
    fun exportConfig(context: Context, destinationUri: Uri): Boolean {
        return try {
            val config = ConfigRepository.global()
            val rules = ConfigRepository.allRules().values.toList()
            
            val exportData = ExportData(
                version = 1,
                exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                moduleName = "完美横屏",
                modulePackage = Constants.MODULE_PACKAGE,
                globalConfig = config,
                rules = rules
            )
            
            val json = toJson(exportData)
            
            val contentResolver = context.contentResolver
            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                outputStream.write(json.toByteArray(Charsets.UTF_8))
            }
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 从 JSON 导入配置
     */
    fun importConfig(context: Context, sourceUri: Uri): ImportResult {
        return try {
            val contentResolver = context.contentResolver
            val json = StringBuilder()
            
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    json.appendLine(line!!)
                }
                reader.close()
            }
            
            // 尝试检测是否为 Magisk XML 格式
            val content = json.toString()
            if (content.contains("<embedded_rules") || content.contains("<fixed_orientation") ||
                content.contains("<autoui") || content.contains("<config")) {
                // Magisk XML 格式，交由 MagiskRuleImporter 处理
                return importMagiskXml(context, sourceUri)
            }
            
            parseAndImport(context, content)
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult(false, "导入失败：${e.message}")
        }
    }

    /**
     * 导入 Magisk XML 规则
     */
    private fun importMagiskXml(context: Context, sourceUri: Uri): ImportResult {
        return try {
            val contentResolver = context.contentResolver
            val fd = contentResolver.openFileDescriptor(sourceUri, "r")
                ?: return ImportResult(false, "无法打开文件")
            
            val result = MagiskRuleImporter.importFromFd(context, fd)
            
            val summary = buildString {
                result.summary.forEach { (source, count) ->
                    appendLine("${when (source) {
                        MagiskRuleImporter.Source.EMBEDDING -> "平行窗口"
                        MagiskRuleImporter.Source.FIXED -> "固定横屏"
                        MagiskRuleImporter.Source.AUTO_UI -> "界面适配"
                        MagiskRuleImporter.Source.SETTINGS -> "设置"
                    }}: $count 条")
                }
            }
            
            val stats = "Magisk 规则包：${result.rules.size} 条应用规则"
            val exportTime = "Magisk 模块"
            
            ImportResult(true, null, null, result.rules, stats, exportTime)
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult(false, "导入 Magisk 规则失败：${e.message}")
        }
    }

    /**
     * 解析 JSON 并导入配置
     */
    private fun parseAndImport(context: Context, json: String): ImportResult {
        try {
            val jsonObject = JSONObject(json)
            
            // 验证版本
            val version = jsonObject.optInt("version", 0)
            if (version != 1) {
                return ImportResult(false, "不支持的配置版本：$version")
            }
            
            // 验证模块包名
            val packageStr = jsonObject.optString("modulePackage", "")
            if (packageStr != Constants.MODULE_PACKAGE) {
                return ImportResult(false, "配置来源模块不匹配")
            }
            
            // 解析全局配置
            val globalJson = jsonObject.getJSONObject("globalConfig")
            val globalConfig = RuleCodec.decodeGlobal(globalJson.toString())
            
            // 解析规则列表
            val rulesJson = jsonObject.getJSONArray("rules")
            val rules = mutableListOf<AppRule>()
            for (i in 0 until rulesJson.length()) {
                val ruleJson = rulesJson.getJSONObject(i)
                val rule = AppRule.fromJson(ruleJson)
                rules.add(rule)
            }
            
            // 显示统计信息
            val exportTime = jsonObject.optString("exportTime", "未知时间")
            val stats = "全局配置 + ${rules.size} 条应用规则"
            
            return ImportResult(true, null, globalConfig, rules, stats, exportTime)
        } catch (e: Exception) {
            return ImportResult(false, "解析配置失败：${e.message}")
        }
    }

    /**
     * 将配置数据转换为 JSON
     */
    private fun toJson(data: ExportData): String {
        val json = JSONObject()
        json.put("version", data.version)
        json.put("exportTime", data.exportTime)
        json.put("moduleName", data.moduleName)
        json.put("modulePackage", data.modulePackage)
        
        json.put("globalConfig", JSONObject(RuleCodec.encodeGlobal(data.globalConfig)))
        
        val rulesArray = JSONArray()
        data.rules.forEach { rule ->
            rulesArray.put(rule.toJson())
        }
        json.put("rules", rulesArray)
        
        return json.toString(2)
    }

    /**
     * 导入结果
     */
    data class ImportResult(
        val success: Boolean,
        val errorMessage: String?,
        val globalConfig: GlobalConfig? = null,
        val rules: List<AppRule>? = null,
        val stats: String? = null,
        val exportTime: String? = null
    )

    /**
     * 显示导入确认对话框
     */
    fun showImportConfirm(
        context: Context,
        result: ImportResult,
        onConfirm: (ImportResult) -> Unit
    ) {
        if (result.errorMessage != null) {
            MaterialAlertDialogBuilder(context)
                .setTitle("导入失败")
                .setMessage(result.errorMessage)
                .setPositiveButton("确定", null)
                .show()
            return
        }
        
        val message = buildString {
            appendLine("检测到以下配置：")
            appendLine()
            appendLine("导出时间：${result.exportTime}")
            appendLine("配置内容：${result.stats}")
            appendLine()
            if (result.exportTime == "Magisk 模块") {
                appendLine("这是 Magisk 完美横屏模块的规则包，导入后将转换为本模块格式。")
                appendLine()
            }
            appendLine("导入后将覆盖当前所有配置，是否继续？")
        }
        
        MaterialAlertDialogBuilder(context)
            .setTitle("确认导入")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                onConfirm(result)
            }
            .show()
    }
}
