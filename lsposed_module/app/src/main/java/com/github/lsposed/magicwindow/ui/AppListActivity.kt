package com.github.lsposed.magicwindow.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.common.model.WindowMode
import com.github.lsposed.magicwindow.data.AppItem
import com.github.lsposed.magicwindow.data.AppLoader
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource
import com.github.lsposed.magicwindow.databinding.ActivityAppListBinding
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var adapter: AppAdapter

    private var all: List<AppItem> = emptyList()
    private var keyword: String = ""

    private enum class Filter { ALL, CONFIGURED, USER, SYSTEM, BUILTIN }

    private var filter = Filter.ALL

    private var backCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = AppAdapter(
            onClick = { open(it) },
            onLongClick = { enterSelection(it) },
            onToggle = { toggle(it) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.setItemViewCacheSize(20)
        binding.recycler.adapter = adapter

        binding.etSearch.addTextChangedListener { text ->
            keyword = text.trim().lowercase()
            apply()
        }

        binding.filterGroup.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.chipConfigured -> Filter.CONFIGURED
                R.id.chipUser -> Filter.USER
                R.id.chipSystem -> Filter.SYSTEM
                R.id.chipBuiltin -> Filter.BUILTIN
                else -> Filter.ALL
            }
            apply()
        }

        binding.btnBatchApply.setOnClickListener { applyBatch() }
        binding.btnBatchClear.setOnClickListener { clearBatch() }
        binding.btnBatchEditField.setOnClickListener { showBatchEditFieldDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitSelection()
        }.also { backCallback = it })

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (all.isNotEmpty()) apply()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_app_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val on = adapter.selectionMode
        menu.findItem(R.id.action_select_all).isVisible = on
        menu.findItem(R.id.action_unselect).isVisible = on
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_select_all -> {
            adapter.selectAll()
            refreshBatchPanel()
            true
        }

        R.id.action_unselect -> {
            exitSelection()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun loadApps() {
        binding.progress.visibility = View.VISIBLE
        Thread {
            val list = AppLoader.load(this)
            runOnUiThread {
                all = list
                binding.progress.visibility = View.GONE
                apply()
            }
        }.start()
    }

    private fun apply() {
        val configured = ConfigRepository.allRules()
        val result = all.filter { item ->
            val hitKeyword = keyword.isEmpty() ||
                    item.label.lowercase().contains(keyword) ||
                    item.packageName.lowercase().contains(keyword)
            val hitFilter = when (filter) {
                Filter.ALL -> true
                // 「已配置」= 用户手动配过的 + 系统内置扫出来的，两类都算已配置
                Filter.CONFIGURED -> configured.containsKey(item.packageName) ||
                        SystemRuleSource.kindsOf(item.packageName).isNotEmpty()
                Filter.USER -> !item.isSystem
                Filter.SYSTEM -> item.isSystem
                Filter.BUILTIN -> SystemRuleSource.kindsOf(item.packageName).isNotEmpty()
            }
            hitKeyword && hitFilter
        }
        adapter.submit(result)
    }

    private fun open(item: AppItem) {
        startActivity(
            Intent(this, AppDetailActivity::class.java)
                .putExtra(AppDetailActivity.EXTRA_PACKAGE, item.packageName)
                .putExtra(AppDetailActivity.EXTRA_LABEL, item.label)
        )
    }

    private fun enterSelection(item: AppItem) {
        adapter.setSelectionMode(true)
        adapter.toggleSelect(item.packageName)
        invalidateOptionsMenu()
        refreshBatchPanel()
    }

    private fun toggle(item: AppItem) {
        adapter.toggleSelect(item.packageName)
        refreshBatchPanel()
    }

    private fun exitSelection() {
        adapter.setSelectionMode(false)
        invalidateOptionsMenu()
        refreshBatchPanel()
    }

    private fun refreshBatchPanel() {
        val show = adapter.selectionMode
        binding.batchPanel.visibility = if (show) View.VISIBLE else View.GONE
        backCallback?.isEnabled = show
        if (show) {
            binding.tvBatchTitle.text =
                getString(R.string.batch_title, adapter.selected.size)
        }
    }

    private fun selectedMode(): WindowMode = when (binding.batchModeGroup.checkedButtonId) {
        R.id.btnModeOff -> WindowMode.OFF
        R.id.btnModeFull -> WindowMode.FULL_SCREEN
        R.id.btnModeFixed -> WindowMode.FIXED_ORIENTATION
        else -> WindowMode.EMBEDDING
    }

    private fun applyBatch() {
        val pkgs = adapter.selected.toList()
        if (pkgs.isEmpty()) return
        val mode = selectedMode()
        val rules = pkgs.map { pkg ->
            ConfigRepository.ruleOrNew(pkg).also {
                it.enabled = true
                it.mode = mode
                // 批量设置只改 mode，三个手动开关保持默认关闭
            }
        }
        ConfigRepository.saveRules(rules)
        exitSelection()
        apply()
        Snackbar.make(binding.root, getString(R.string.batch_done, pkgs.size), Snackbar.LENGTH_SHORT)
            .show()
    }

    private fun clearBatch() {
        val pkgs = adapter.selected.toList()
        if (pkgs.isEmpty()) return
        ConfigRepository.removeRules(pkgs)
        exitSelection()
        apply()
        Snackbar.make(binding.root, getString(R.string.batch_done, pkgs.size), Snackbar.LENGTH_SHORT)
            .show()
    }

    /** 批量编辑任意字段 */
    private fun showBatchEditFieldDialog() {
        val pkgs = adapter.selected.toList()
        if (pkgs.isEmpty()) {
            Snackbar.make(binding.root, R.string.capture_none_selected, Snackbar.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_batch_edit_field, null)
        val etField = view.findViewById<EditText>(R.id.etField)
        val etValue = view.findViewById<EditText>(R.id.etValue)

        AlertDialog.Builder(this)
            .setTitle(R.string.batch_edit_field_title)
            .setMessage(R.string.batch_edit_field_desc)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.batch_apply) { _, _ ->
                val field = etField.text.toString().trim()
                val value = etValue.text.toString().trim()
                if (field.isEmpty() || value.isEmpty()) {
                    Snackbar.make(binding.root, "字段名和值不能为空", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyBatchField(pkgs, field, value)
            }
            .show()
    }

    private fun applyBatchField(pkgs: List<String>, field: String, value: String) {
        val rules = pkgs.map { pkg ->
            val original = ConfigRepository.ruleOrNew(pkg)
            original.enabled = true
            // 动态设置字段：通过 JSON 方式
            setFieldViaJson(original, field, value)
        }
        ConfigRepository.saveRules(rules)
        exitSelection()
        apply()
        Snackbar.make(binding.root, getString(R.string.batch_done, pkgs.size), Snackbar.LENGTH_SHORT)
            .show()
    }

    /** 通过 JSON 方式动态设置字段（傻瓜化：用户填字段名和值，我们自动映射） */
    private fun setFieldViaJson(rule: com.github.lsposed.magicwindow.common.model.AppRule, field: String, value: String): com.github.lsposed.magicwindow.common.model.AppRule {
        val json = rule.toJson()
        try {
            // 特殊处理：mode 字段需要转为 WindowMode
            if (field == "mode") {
                val mode = when (value.lowercase()) {
                    "off", "0" -> WindowMode.OFF
                    "full", "fullscreen", "1" -> WindowMode.FULL_SCREEN
                    "embedding", "2" -> WindowMode.EMBEDDING
                    "fixed", "fixed_orientation", "3" -> WindowMode.FIXED_ORIENTATION
                    else -> WindowMode.EMBEDDING
                }
                json.put("mode", mode.key)
            } else {
                // 尝试自动推断类型
                val typedValue = try {
                    when {
                        value.lowercase() == "true" -> true
                        value.lowercase() == "false" -> false
                        value.matches(Regex("^-?\\d+$")) -> value.toInt()
                        value.matches(Regex("^-?\\d+\\.\\d+$")) -> value.toDouble()
                        else -> value
                    }
                } catch (e: Exception) {
                    value
                }
                json.put(field, typedValue)
            }
            // 从 JSON 重新创建规则
            return com.github.lsposed.magicwindow.common.model.AppRule.fromJson(json)
        } catch (e: Exception) {
            // 忽略无效字段，返回原规则
            return rule
        }
    }
}

private fun android.widget.EditText.addTextChangedListener(onChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChanged(s?.toString().orEmpty())
    })
}
