package com.github.lsposed.magicwindow.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.databinding.ActivityExperimentalBinding

/**
 * 实验性设置页：集中承载全部进阶开关。
 *
 * 普通用户日常用不到这里的选项——规则注入默认已开启且配置后自动热重载。
 * 只有在云控注入对个别应用不生效时，才需要来「降级备用」里开运行时 hook。
 */
class ExperimentalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExperimentalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExperimentalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        buildOptions()
    }

    private fun buildOptions() {
        val c = ConfigRepository.global()
        val box = binding.container
        box.removeAllViews()

        // ── 规则注入（云控方案，核心生效方式） ──
        val cloud = UiKit.section(box, getString(R.string.section_cloud_inject))
        UiKit.switchRow(
            cloud, getString(R.string.opt_autoui_inject), getString(R.string.opt_autoui_inject_desc),
            c.autoUiCloudInject
        ) { c.autoUiCloudInject = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            cloud, getString(R.string.opt_embedded_inject), getString(R.string.opt_embedded_inject_desc),
            c.embeddedCloudInject
        ) { c.embeddedCloudInject = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            cloud, getString(R.string.opt_fixed_inject), getString(R.string.opt_fixed_inject_desc),
            c.fixedCloudInject
        ) { c.fixedCloudInject = it; ConfigRepository.saveGlobal(c) }
        UiKit.textRow(
            cloud,
            label = getString(R.string.opt_cloud_version),
            value = c.cloudDataVersion.toString(),
            hint = getString(R.string.opt_cloud_version_desc)
        ) { v ->
            v.trim().toLongOrNull()?.let { c.cloudDataVersion = it; ConfigRepository.saveGlobal(c) }
        }

        // ── 系统开关（前置条件） ──
        val gate = UiKit.section(box, getString(R.string.section_gate))
        UiKit.switchRow(
            gate, getString(R.string.opt_verify_gates), getString(R.string.opt_verify_gates_desc),
            c.verifyGates
        ) { c.verifyGates = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            gate, getString(R.string.opt_force_ae), getString(R.string.opt_force_ae_desc),
            c.forceActivityEmbedding
        ) { c.forceActivityEmbedding = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            gate, getString(R.string.opt_force_autoui), getString(R.string.opt_force_autoui_desc),
            c.forceAutoUi
        ) { c.forceAutoUi = it; ConfigRepository.saveGlobal(c) }

        // ── 降级备用（运行时 hook，默认关闭） ──
        val fallback = UiKit.section(box, getString(R.string.section_fallback))
        UiKit.switchRow(
            fallback, getString(R.string.opt_flip_switch), getString(R.string.opt_flip_switch_desc),
            c.flipUserSwitches
        ) { c.flipUserSwitches = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            fallback, getString(R.string.opt_query_hook), getString(R.string.opt_query_hook_desc),
            c.queryHookFallback
        ) { c.queryHookFallback = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            fallback, getString(R.string.opt_enable_query_hook),
            getString(R.string.opt_enable_query_hook_desc),
            c.enableQueryHook
        ) { c.enableQueryHook = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            fallback, getString(R.string.opt_override_disable),
            getString(R.string.opt_override_disable_desc),
            c.overrideSystemDisable
        ) { c.overrideSystemDisable = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            fallback, getString(R.string.opt_rule_table_inject),
            getString(R.string.opt_rule_table_inject_desc),
            c.ruleTableInject
        ) { c.ruleTableInject = it; ConfigRepository.saveGlobal(c) }
        UiKit.switchRow(
            fallback, getString(R.string.opt_hook_cloud_string),
            getString(R.string.opt_hook_cloud_string_desc),
            c.hookCloudDataString
        ) { c.hookCloudDataString = it; ConfigRepository.saveGlobal(c) }

        // ── 其它 ──
        val misc = UiKit.section(box, getString(R.string.section_misc))
        UiKit.switchRow(
            misc, getString(R.string.opt_verbose), getString(R.string.opt_verbose_desc),
            c.verboseLog
        ) { c.verboseLog = it; ConfigRepository.saveGlobal(c) }
    }
}
