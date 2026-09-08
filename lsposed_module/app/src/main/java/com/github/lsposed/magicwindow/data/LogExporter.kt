package com.github.lsposed.magicwindow.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.github.lsposed.magicwindow.common.Constants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志导出工具
 */
object LogExporter {

    /**
     * 导出 LSPosed 日志到文件
     */
    fun exportLogs(context: Context, destinationUri: Uri): Boolean {
        return try {
            val contentResolver = context.contentResolver
            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                val writer = java.io.OutputStreamWriter(outputStream)
                
                // 读取 logcat 日志，过滤模块标签
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "${Constants.LOG_TAG}:V", "*:E")
                )
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                writer.appendLine("=== 完美横屏模块日志 ===")
                writer.appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                writer.appendLine("Android 版本：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                writer.appendLine("模块包名：${Constants.MODULE_PACKAGE}")
                writer.appendLine("===================================\n")
                
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    writer.appendLine(line!!)
                }
                
                reader.close()
                errorReader.close()
                process.waitFor()
                writer.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 预览最近的日志内容
     */
    fun previewLogs(context: Context, maxLines: Int = 200): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", maxLines.toString(), "${Constants.LOG_TAG}:V", "*:E")
            )
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val logs = StringBuilder()
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                logs.appendLine(line!!)
            }
            
            reader.close()
            process.waitFor()
            logs.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            "无法读取日志：${e.message}"
        }
    }

    /**
     * 显示日志预览对话框
     */
    fun showLogPreview(context: Context) {
        val logs = previewLogs(context)
        
        MaterialAlertDialogBuilder(context)
            .setTitle("日志预览")
            .setMessage(logs)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制全部") { _, _ ->
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("MagicWindow Logs", logs)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "复制失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
