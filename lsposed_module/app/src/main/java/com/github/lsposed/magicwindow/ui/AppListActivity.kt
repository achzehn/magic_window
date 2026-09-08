package com.github.lsposed.magicwindow.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
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
                else -> Filter.ALL
            }
            apply()
        }

        binding.btnBatchApply.setOnClickListener { applyBatch() }
        binding.btnBatchClear.setOnClickListener { clearBatch() }

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
                Filter.CONFIGURED -> configured.containsKey(item.packageName)
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
                // 批量设置一律走主模式推导，避免手动覆盖导致互斥冲突
                it.overrideUserSwitch = false
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
}

private fun android.widget.EditText.addTextChangedListener(onChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChanged(s?.toString().orEmpty())
    })
}
