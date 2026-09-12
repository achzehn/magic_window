package com.github.lsposed.magicwindow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.lsposed.magicwindow.ModuleStatus
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.ConfigExporter
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.databinding.ActivityMainBinding
import com.github.lsposed.magicwindow.mcp.McpServer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 桌面图标入口，停用它就等于隐藏图标 */
    private val launcherAlias by lazy {
        ComponentName(this, "com.github.lsposed.magicwindow.ui.LauncherAlias")
    }

    /** 导出配置文件选择器 */
    private val configExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            if (ConfigExporter.exportConfig(this, it)) {
                Snackbar.make(binding.root, R.string.config_export_success, Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, R.string.config_export_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** 导入配置文件选择器 */
    private val configImporter = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = ConfigExporter.importConfig(this, it)
            if (result.success && result.rules != null) {
                ConfigExporter.showImportConfirm(this, result) {
                    ConfigRepository.replaceAllRules(result.rules!!)
                    Snackbar.make(binding.root, R.string.config_import_success, Snackbar.LENGTH_SHORT).show()
                    renderStatus()
                }
            } else {
                val message = result.errorMessage ?: getString(R.string.config_import_failed)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 检查 Root 权限
        if (!checkRootPermission()) {
            showRootRequiredDialog()
            return
        }

        binding.cardApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        binding.swMcp.setOnCheckedChangeListener { _, on ->
            if (on) {
                if (!McpServer.start(applicationContext)) {
                    binding.swMcp.isChecked = false
                    Snackbar.make(binding.root, R.string.mcp_start_failed, Snackbar.LENGTH_SHORT).show()
                }
            } else {
                McpServer.stop()
            }
            renderMcpState()
        }

        // 地址点击复制；端口/令牌点击进入编辑
        fun bindCopy(view: TextView, text: () -> String) {
            view.setOnClickListener { copyText(text()) }
            view.setOnLongClickListener { copyText(text()); true }
        }
        bindCopy(binding.tvMcpLoopback) { McpServer.loopbackUrl() }
        bindCopy(binding.tvMcpLan) { McpServer.localUrl() }
        binding.tvMcpPort.setOnClickListener { showMcpPortDialog() }
        binding.tvMcpToken.setOnClickListener { showMcpTokenDialog() }
        binding.tvMcpToken.setOnLongClickListener {
            McpServer.token(this).takeIf { it.isNotEmpty() }?.let { copyText(it) }
            true
        }

        binding.btnMcpJson.setOnClickListener { showMcpClientJsonDialog() }
    }

    /** 修改端口：保存后若服务在运行则自动重启换端口 */
    private fun showMcpPortDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.mcp_port_hint)
            setText(McpServer.port(this@MainActivity).toString())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_port_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mcp_action_save) { _, _ ->
                val value = input.text.toString().toIntOrNull() ?: 0
                if (value !in 1024..65535) {
                    Snackbar.make(binding.root, R.string.mcp_port_invalid, Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!McpServer.setPort(applicationContext, value)) {
                    Snackbar.make(binding.root, R.string.mcp_start_failed, Snackbar.LENGTH_SHORT).show()
                }
                renderMcpState()
            }
            .show()
    }

    /** 修改令牌：可随机生成，留空表示不校验 */
    private fun showMcpTokenDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.mcp_token_hint)
            setText(McpServer.token(this@MainActivity))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_token_title)
            .setView(input)
            .setNeutralButton(R.string.mcp_action_random, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mcp_action_save, null)
            .create()
            .apply {
                setOnShowListener {
                    // 随机生成只填入输入框，不直接保存
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        input.setText(McpServer.randomToken())
                    }
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        McpServer.setToken(applicationContext, input.text.toString())
                        renderMcpState()
                        dismiss()
                    }
                }
            }
            .show()
    }

    /**
     * 预览 MCP 客户端接入配置 JSON（自动带上当前局域网 IP），
     * 点击/长按 JSON 可复制，另带「复制」按钮。
     */
    private fun showMcpClientJsonDialog() {
        val json = McpServer.clientConfigJson()
        val jsonView = TextView(this).apply {
            text = json
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(false)
            setOnLongClickListener { copyText(json); true }
            setOnClickListener { copyText(json) }
        }
        val container = ScrollView(this).apply {
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = getString(R.string.mcp_json_hint)
                    setTextAppearance(R.style.Text_MagicWindow_Desc)
                })
                addView(jsonView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (resources.displayMetrics.density * 8).toInt()
                })
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_json_title)
            .setView(container)
            .setPositiveButton(R.string.action_copy) { _, _ -> copyText(json) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("magic-window", text))
        Snackbar.make(binding.root, R.string.capture_copied, Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_hide_icon).isChecked = isIconHidden()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_hide_icon -> {
            if (item.isChecked) {
                setIconHidden(false)
                item.isChecked = false
                Snackbar.make(binding.root, R.string.show_icon_done, Snackbar.LENGTH_SHORT).show()
            } else {
                confirmHide(item)
            }
            true
        }
        R.id.action_export_config -> {
            configExporter.launch("magicwindow_config_${System.currentTimeMillis()}.json")
            true
        }
        R.id.action_import_config -> {
            configImporter.launch(
                arrayOf(
                    "application/json",
                    "text/plain"
                )
            )
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** 隐藏之后只能从管理器进来，所以先确认一次 */
    private fun confirmHide(item: MenuItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hide_icon_confirm_title)
            .setMessage(R.string.hide_icon_confirm_msg)
            .setNegativeButton(R.string.hide_icon_confirm_cancel, null)
            .setPositiveButton(R.string.hide_icon_confirm_ok) { _, _ ->
                setIconHidden(true)
                item.isChecked = true
                Snackbar.make(binding.root, R.string.hide_icon_done, Snackbar.LENGTH_LONG).show()
            }
            .show()
    }

    private fun isIconHidden(): Boolean =
        packageManager.getComponentEnabledSetting(launcherAlias) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun setIconHidden(hidden: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcherAlias,
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderMcpState()
    }

    private fun renderMcpState() {
        binding.swMcp.isChecked = McpServer.isRunning()
        binding.tvMcpState.text =
            if (McpServer.isRunning()) getString(R.string.mcp_running)
            else getString(R.string.mcp_stopped)
        binding.tvMcpLoopback.text = McpServer.loopbackUrl()
        binding.tvMcpLan.text = McpServer.localUrl()
        binding.tvMcpPort.text = McpServer.port(this).toString()
        binding.tvMcpToken.text = McpServer.token(this).ifEmpty { getString(R.string.mcp_token_none) }
    }

    private fun renderStatus() {
        val active = ModuleStatus.isModuleActive()
        binding.tvStatus.text = getString(
            if (active) R.string.status_active else R.string.status_inactive
        )
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.ok_green else R.color.conflict_error
            )
        )
        binding.tvStatusDesc.text =
            getString(R.string.status_configured, ConfigRepository.configuredCount())
    }

    /** 检查 Root 权限 */
    private fun checkRootPermission(): Boolean {
        // 方法 1：检查 SuPath
        val suPaths = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in suPaths) {
            if (File(path).exists()) {
                return true
            }
        }

        // 方法 2：尝试执行 whoami 命令（需要 su 可执行）
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "whoami"))
            val result = process.inputStream.bufferedReader().use { it.readText().trim() }
            result == "root"
        } catch (e: Exception) {
            false
        }
    }

    /** 显示 Root 权限必需对话框 */
    private fun showRootRequiredDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.root_required_title)
            .setMessage(R.string.root_required_message)
            .setPositiveButton(R.string.root_required_ok) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
}
