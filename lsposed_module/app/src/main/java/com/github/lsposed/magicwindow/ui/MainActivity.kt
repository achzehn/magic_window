package com.github.lsposed.magicwindow.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.lsposed.magicwindow.ModuleStatus
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.ConfigExporter
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.LogExporter
import com.github.lsposed.magicwindow.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 桌面图标入口，停用它就等于隐藏图标 */
    private val launcherAlias by lazy {
        ComponentName(this, "com.github.lsposed.magicwindow.ui.LauncherAlias")
    }

    /** 导出日志文件选择器 */
    private val logExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            if (LogExporter.exportLogs(this, it)) {
                Snackbar.make(binding.root, R.string.log_export_success, Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, R.string.log_export_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
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
            when (val result = ConfigExporter.importConfig(this, it)) {
                is ConfigExporter.ImportResult -> {
                    if (result.success && result.globalConfig != null && result.rules != null) {
                        ConfigExporter.showImportConfirm(this, result) {
                            ConfigRepository.saveGlobal(result.globalConfig!!)
                            ConfigRepository.saveRules(result.rules!!)
                            Snackbar.make(binding.root, R.string.config_import_success, Snackbar.LENGTH_SHORT).show()
                            renderStatus()
                        }
                    } else {
                        val message = result.errorMessage ?: getString(R.string.config_import_failed)
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.cardApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }
        buildAdvanced()
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
        R.id.action_export_log -> {
            logExporter.launch("magicwindow_logs_${System.currentTimeMillis()}.txt")
            true
        }
        R.id.action_preview_log -> {
            LogExporter.showLogPreview(this)
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

    private fun buildAdvanced() {
        val c = ConfigRepository.global()
        val box = binding.advancedContainer
        box.removeAllViews()

        UiKit.switchRow(
            box, getString(R.string.opt_verify_gates), getString(R.string.opt_verify_gates_desc),
            c.verifyGates
        ) { c.verifyGates = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_force_ae), getString(R.string.opt_force_ae_desc),
            c.forceActivityEmbedding
        ) { c.forceActivityEmbedding = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_force_autoui), getString(R.string.opt_force_autoui_desc),
            c.forceAutoUi
        ) { c.forceAutoUi = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_flip_switch), getString(R.string.opt_flip_switch_desc),
            c.flipUserSwitches
        ) { c.flipUserSwitches = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_query_hook), getString(R.string.opt_query_hook_desc),
            c.queryHookFallback
        ) { c.queryHookFallback = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_enable_query_hook),
            getString(R.string.opt_enable_query_hook_desc),
            c.enableQueryHook
        ) { c.enableQueryHook = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_override_disable),
            getString(R.string.opt_override_disable_desc),
            c.overrideSystemDisable
        ) { c.overrideSystemDisable = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_rule_table_inject),
            getString(R.string.opt_rule_table_inject_desc),
            c.ruleTableInject
        ) { c.ruleTableInject = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_autoui_inject), getString(R.string.opt_autoui_inject_desc),
            c.autoUiCloudInject
        ) { c.autoUiCloudInject = it; ConfigRepository.saveGlobal(c) }

        UiKit.textRow(
            box,
            label = getString(R.string.opt_cloud_version),
            value = c.cloudDataVersion.toString(),
            hint = getString(R.string.opt_cloud_version_desc)
        ) { v ->
            v.trim().toLongOrNull()?.let { c.cloudDataVersion = it; ConfigRepository.saveGlobal(c) }
        }

        UiKit.switchRow(
            box, getString(R.string.opt_hook_cloud_string),
            getString(R.string.opt_hook_cloud_string_desc),
            c.hookCloudDataString
        ) { c.hookCloudDataString = it; ConfigRepository.saveGlobal(c) }

        UiKit.switchRow(
            box, getString(R.string.opt_verbose), getString(R.string.opt_verbose_desc),
            c.verboseLog
        ) { c.verboseLog = it; ConfigRepository.saveGlobal(c) }
    }
}
