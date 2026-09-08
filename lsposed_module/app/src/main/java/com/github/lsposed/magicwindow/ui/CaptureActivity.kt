package com.github.lsposed.magicwindow.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.CaptureStore
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.databinding.ActivityCaptureBinding
import com.google.android.material.snackbar.Snackbar

/**
 * 页面抓取工具页：
 *   1. 打开记录开关（写进全局配置，system_server 侧的 ActivityTracker 会读到）；
 *   2. 回到桌面把目标应用的页面都点一遍；
 *   3. 回到本页勾选抓到的页面，一键回填到详情页的某个字段。
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_LABEL = "label"

        /** 回传给详情页的类名列表，英文逗号分隔 */
        const val EXTRA_RESULT = "result"
    }

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var pkg: String

    private val picked = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg.isEmpty()) {
            finish()
            return
        }

        binding.toolbar.subtitle = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnClear.setOnClickListener {
            CaptureStore.clear(this, pkg)
            picked.clear()
            render()
        }
        binding.btnFill.setOnClickListener { fill() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val box = binding.container
        box.removeAllViews()

        val config = ConfigRepository.global()
        UiKit.switchRow(
            box, getString(R.string.capture_switch), getString(R.string.capture_switch_desc),
            config.captureEnabled
        ) { on ->
            config.captureEnabled = on
            ConfigRepository.saveGlobal(config)
            Snackbar.make(binding.root, R.string.capture_need_reboot, Snackbar.LENGTH_SHORT).show()
        }

        val list = CaptureStore.activitiesOf(this, pkg)
        if (list.isEmpty()) {
            UiKit.note(box, getString(R.string.capture_empty))
            return
        }

        val body = UiKit.section(box, getString(R.string.capture_count, list.size))
        val chips = UiKit.chipBox(body)
        list.forEach { cls ->
            UiKit.chip(chips, cls.substringAfterLast('.'), cls, picked.contains(cls)) { on ->
                if (on) picked.add(cls) else picked.remove(cls)
            }
        }
    }

    private fun fill() {
        if (picked.isEmpty()) {
            Snackbar.make(binding.root, R.string.capture_none_selected, Snackbar.LENGTH_SHORT)
                .show()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_RESULT, picked.joinToString(","))
        )
        finish()
    }
}
