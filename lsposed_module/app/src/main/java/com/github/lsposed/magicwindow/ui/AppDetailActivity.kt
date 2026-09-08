package com.github.lsposed.magicwindow.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.ConflictChecker
import com.github.lsposed.magicwindow.common.model.WindowMode
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource
import com.github.lsposed.magicwindow.databinding.ActivityAppDetailBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout

/**
 * 单应用详情：暴露 4.10.4 实测出的全部规则属性，并实时做互斥优先级冲突检测。
 *
 * 控件选型：
 *  - 开关型属性 → 可勾选标签（点一下选中即为开启）
 *  - 取值固定的属性 → 下拉选择框
 *  - 需要填页面类名 / 比例数值的属性 → 输入框
 */
class AppDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_LABEL = "label"

        /** 空值统一显示为「跟随系统默认」 */
        private const val UNSET = "跟随系统默认"
    }

    private lateinit var binding: ActivityAppDetailBinding
    private lateinit var pkg: String
    private lateinit var rule: AppRule

    /** 字段 key → 控件，用于冲突高亮定位 */
    private val fieldViews = linkedMapOf<String, View>()

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cls = result.data?.getStringExtra(CaptureActivity.EXTRA_RESULT) ?: return@registerForActivityResult
        askTargetField(cls)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg.isEmpty()) {
            finish()
            return
        }

        // 优先读取已有规则；没有则用系统内置值做初值
        val existing = ConfigRepository.rule(pkg)
        rule = if (existing != null) {
            existing
        } else {
            AppRule(pkg).also { SystemRuleSource.applyDefaults(it) }
        }

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.title = intent.getStringExtra(EXTRA_LABEL) ?: pkg

        binding.tvLabel.text = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        binding.tvPkg.text = pkg
        binding.icon.setImageDrawable(
            runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
        )

        // 系统内置规则提示
        val kinds = SystemRuleSource.kindsOf(pkg)
        if (kinds.isNotEmpty()) {
            binding.cardBuiltin.visibility = View.VISIBLE
            val names = kinds.joinToString(" · ") { kind ->
                when (kind) {
                    SystemRuleSource.Kind.EMBEDDING -> getString(R.string.builtin_embedding)
                    SystemRuleSource.Kind.FIXED -> getString(R.string.builtin_fixed)
                    SystemRuleSource.Kind.AUTO_UI -> getString(R.string.builtin_autoui)
                }
            }
            binding.tvBuiltin.text = getString(R.string.detail_builtin_hint, names)
        }

        binding.switchEnabled.isChecked = rule.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, v ->
            rule.enabled = v
            validate()
        }

        binding.modeGroup.check(buttonOf(rule.mode))
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            rule.mode = modeOf(checkedId)
            buildForm()
            validate()
        }

        binding.btnFix.setOnClickListener {
            ConflictChecker.autoFix(rule)
            binding.modeGroup.check(buttonOf(rule.mode))
            buildForm()
            validate()
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnReset.setOnClickListener {
            rule = AppRule(pkg)
            binding.switchEnabled.isChecked = rule.enabled
            binding.modeGroup.check(buttonOf(rule.mode))
            buildForm()
            validate()
        }

        buildForm()
        validate()
    }

    // ── 菜单与「抓取页面」回填 ───────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_app_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_capture -> {
            captureLauncher.launch(
                android.content.Intent(this, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_PACKAGE, pkg)
                    .putExtra(CaptureActivity.EXTRA_LABEL, binding.tvLabel.text.toString())
            )
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** 抓到类名后，让用户选一个字段把它填进去 */
    private fun askTargetField(cls: String) {
        val targets = listOf<Triple<String, () -> String, (String) -> Unit>>(
            Triple(
                "参与分屏的页面（activityRule）",
                { rule.activityRule },
                { rule.activityRule = it }
            ),
            Triple(
                "不做切换动画的页面（transitionRules）",
                { rule.transitionRules },
                { rule.transitionRules = it }
            ),
            Triple(
                "始终竖着显示的页面（平行窗口 forcePortraitActivity）",
                { rule.forcePortraitActivity },
                { rule.forcePortraitActivity = it }
            ),
            Triple(
                "始终竖着显示的页面（固定横屏 forcePortraitActivity）",
                { rule.foForcePortraitActivity },
                { rule.foForcePortraitActivity = it }
            ),
            Triple(
                "全屏档下仍竖着显示的页面（fullForcePortraitActivity）",
                { rule.foFullForcePortraitActivity },
                { rule.foFullForcePortraitActivity = it }
            ),
            Triple(
                "需要适配的页面（界面适配 activityRule）",
                { rule.autoUiActivityRule },
                { rule.autoUiActivityRule = it }
            ),
            Triple(
                "跳过适配的页面（界面适配 skippedActivityRule）",
                { rule.autoUiSkippedActivityRule },
                { rule.autoUiSkippedActivityRule = it }
            )
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_pick_field)
            .setItems(targets.map { it.first }.toTypedArray()) { _, which ->
                val (_, getter, setter) = targets[which]
                setter(appendCsv(getter(), cls))
                buildForm()
                validate()
                val count = cls.split(',').count { it.isNotBlank() }
                Snackbar.make(
                    binding.root,
                    getString(R.string.capture_filled, count),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 以英文逗号并入并去重 */
    private fun appendCsv(old: String, add: String): String {
        val set = linkedSetOf<String>()
        (old.split(',') + add.split(',')).forEach { item ->
            val t = item.trim()
            if (t.isNotEmpty()) set += t
        }
        return set.joinToString(",")
    }

    // ── 主模式按钮映射 ───────────────────────────────────────

    private fun buttonOf(mode: WindowMode): Int = when (mode) {
        WindowMode.OFF -> R.id.btnModeOff
        WindowMode.FULL_SCREEN -> R.id.btnModeFull
        WindowMode.EMBEDDING -> R.id.btnModeEmbedding
        WindowMode.FIXED_ORIENTATION -> R.id.btnModeFixed
    }

    private fun modeOf(id: Int): WindowMode = when (id) {
        R.id.btnModeOff -> WindowMode.OFF
        R.id.btnModeFull -> WindowMode.FULL_SCREEN
        R.id.btnModeFixed -> WindowMode.FIXED_ORIENTATION
        else -> WindowMode.EMBEDDING
    }

    // ── 表单构建 ─────────────────────────────────────────────

    private fun buildForm() {
        val box = binding.container
        box.removeAllViews()
        fieldViews.clear()

        buildEmbeddingSection(box)
        buildFixedSection(box)
        buildAutoUiSection(box)
        buildOverrideSection(box)
    }

    private fun inactiveHint(active: Boolean): String? =
        if (active) null else getString(R.string.hint_inactive_section)

    private fun buildEmbeddingSection(box: LinearLayout) {
        val body = UiKit.section(
            box, getString(R.string.section_embedding),
            inactiveHint(rule.mode == WindowMode.EMBEDDING)
        )

        val chips = UiKit.chipBox(body)
        chip(chips, "supportFullSize", "可放大到整屏", "supportFullSize", rule.supportFullSize) {
            rule.supportFullSize = it
        }
        chip(chips, "isShowDivider", "显示中间分割线", "isShowDivider", rule.isShowDivider) {
            rule.isShowDivider = it
        }
        chip(chips, "skipSelfAdaptive", "跳过应用自适应", "skipSelfAdaptive", rule.skipSelfAdaptive) {
            rule.skipSelfAdaptive = it
        }
        chip(
            chips, "supportCameraPreview", "允许相机预览分屏", "supportCameraPreview",
            rule.supportCameraPreview
        ) { rule.supportCameraPreview = it }
        chip(chips, "relaunch", "切换时重启应用", "relaunch", rule.relaunch) { rule.relaunch = it }
        chip(chips, "clearTop", "返回时清空上层页面", "clearTop", rule.clearTop) { rule.clearTop = it }
        chip(
            chips, "finishSecondaryWithPrimary", "左栏关闭时右栏一起关",
            "finishSecondaryWithPrimary", rule.finishSecondaryWithPrimary
        ) { rule.finishSecondaryWithPrimary = it }
        UiKit.note(body, getString(R.string.note_embedding))

        UiKit.note(body, getString(R.string.group_pages))
        txt(
            body, "activityRule", "参与分屏的页面", "activityRule", rule.activityRule,
            "填 Activity 全类名，多个用英文逗号隔开"
        ) { rule.activityRule = it }
        txt(
            body, "splitPairRule", "左右两栏的配对方式", "splitPairRule", rule.splitPairRule,
            "格式 左栏页面:右栏页面，如 MainActivity:*"
        ) { rule.splitPairRule = it }
        txt(
            body, "placeholder", "右栏默认打开的页面", "placeholder", rule.placeholder,
            "格式 主页面:占位页面"
        ) { rule.placeholder = it }
        txt(
            body, "transitionRules", "不做切换动画的页面", "transitionRules", rule.transitionRules,
            "多个用英文逗号隔开"
        ) { rule.transitionRules = it }
        txt(
            body, "forcePortraitActivity", "始终竖着显示的页面", "forcePortraitActivity",
            rule.forcePortraitActivity, "这些页面不参与分屏"
        ) { rule.forcePortraitActivity = it }

        UiKit.note(body, getString(R.string.group_layout))
        txt(
            body, "splitRatio", "左栏宽度占比", "splitRatio", rule.splitRatio,
            "0~1 的小数，如 0.35"
        ) { rule.splitRatio = it }
        txt(
            body, "splitMinWidth", "分栏最小宽度", "splitMinWidth", rule.splitMinWidth,
            "低于该宽度不分栏，单位 dp"
        ) { rule.splitMinWidth = it }
        dropdown(
            body, "fullRule", "整屏显示方式", "fullRule", rule.fullRule,
            listOf(
                "" to UNSET,
                "*" to "所有页面都可整屏（*）",
                "nra" to "整屏时不重建页面（nra）",
                "nra:cr:rcr" to "不重建 + 裁剪圆角（nra:cr:rcr）"
            )
        ) { rule.fullRule = it }
        dropdown(
            body, "scaleMode", "画面缩放方式", "scaleMode", rule.scaleMode,
            listOf("" to UNSET, "1" to "等比缩放（1）")
        ) { rule.scaleMode = it }
        dropdown(
            body, "middleRule", "居中显示范围", "middleRule", rule.middleRule,
            listOf("" to UNSET, "*" to "所有页面都居中（*）")
        ) { rule.middleRule = it }
        txt(
            body, "splitLineColor", "分割线颜色", "splitLineColor", rule.splitLineColor,
            "浅色:深色，如 #E6E6E6:#323232"
        ) { rule.splitLineColor = it }

        UiKit.note(body, getString(R.string.group_advanced))
        txt(
            body, "minSupportVersion", "生效的最低应用版本", "minSupportVersion",
            rule.minSupportVersion, "格式 序号:日期，如 1:20230510"
        ) { rule.minSupportVersion = it }
        txt(
            body, "flags", "额外标记", "flags", rule.flags,
            "如 forceResumeOnFocus:com.x.MainActivity"
        ) { rule.flags = it }
        txt(
            body, "procCompat", "进程级兼容处理", "procCompat", rule.procCompat,
            "按应用进程名做兼容，一般留空"
        ) { rule.procCompat = it }
        txt(
            body, "autoUiRule", "顺带启用的界面适配规则", "autoUiRule", rule.autoUiRule,
            "格式 页面:数值，多个用分号隔开"
        ) { rule.autoUiRule = it }
        txt(
            body, "defaultSettings", "默认档位", "defaultSettings", rule.defaultSettings,
            "应用第一次打开时用哪一档"
        ) { rule.defaultSettings = it }
    }

    private fun buildFixedSection(box: LinearLayout) {
        val body = UiKit.section(
            box, getString(R.string.section_fixed),
            inactiveHint(rule.mode == WindowMode.FIXED_ORIENTATION)
        )

        val chips = UiKit.chipBox(body)
        chip(chips, "foSupportFullSize", "可放大到整屏", "supportFullSize", rule.foSupportFullSize) {
            rule.foSupportFullSize = it
        }
        chip(chips, "foIsScale", "按比例放大画面", "isScale", rule.foIsScale) { rule.foIsScale = it }
        chip(chips, "foRelaunch", "切换时重启应用", "relaunch", rule.foRelaunch) {
            rule.foRelaunch = it
        }
        chip(
            chips, "foSupportCameraPreview", "允许相机预览", "supportCameraPreview",
            rule.foSupportCameraPreview
        ) { rule.foSupportCameraPreview = it }
        chip(chips, "foSkipCompatMode", "跳过兼容模式", "skipCompatMode", rule.foSkipCompatMode) {
            rule.foSkipCompatMode = it
        }
        chip(
            chips, "foAllowEmbInPortrait", "竖屏也允许分栏", "allowEmbInPortrait",
            rule.foAllowEmbInPortrait
        ) { rule.foAllowEmbInPortrait = it }
        chip(
            chips, "foForceKillWhenSwitch", "换档时结束进程", "forceKillWhenSwitch",
            rule.foForceKillWhenSwitch
        ) { rule.foForceKillWhenSwitch = it }
        chip(chips, "foOverrideDisable", "无视系统禁用名单", "disable", rule.foOverrideDisable) {
            rule.foOverrideDisable = it
        }
        UiKit.note(body, getString(R.string.note_fixed))

        dropdown(
            body, "foSupportModes", "支持哪些档位", "supportModes", rule.foSupportModes,
            listOf("full,fo" to "全屏拉伸 + 横屏信箱（full,fo）", "" to UNSET),
            "系统内置规则里这一项固定是 full,fo"
        ) { rule.foSupportModes = it }
        dropdown(
            body, "foDefaultSettings", "默认用哪一档", "defaultSettings", rule.foDefaultSettings,
            listOf(
                "fo" to "横屏信箱（fo，画面居中留黑边）",
                "full" to "全屏拉伸（full，铺满屏幕）",
                "" to UNSET
            )
        ) { rule.foDefaultSettings = it }
        dropdown(
            body, "foCompatChange", "系统兼容性开关", "compatChange", rule.foCompatChange,
            listOf(
                "" to UNSET,
                "OVERRIDE_MIN_ASPECT_RATIO,OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN,OVERRIDE_MIN_ASPECT_RATIO_MEDIUM"
                    to "限制最小长宽比（中等，最常用）",
                "OVERRIDE_MIN_ASPECT_RATIO,OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN,OVERRIDE_MIN_ASPECT_RATIO_LARGE"
                    to "限制最小长宽比（较大）",
                "OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT" to "未声明方向时按竖屏处理",
                "FORCE_RESIZE_APP" to "强制允许改变窗口大小"
            ),
            "对应安卓自带的应用兼容开关"
        ) { rule.foCompatChange = it }
        txt(
            body, "foForcePortraitActivity", "始终竖着显示的页面", "forcePortraitActivity",
            rule.foForcePortraitActivity, "多个用英文逗号隔开"
        ) { rule.foForcePortraitActivity = it }
        txt(
            body, "foFullForcePortraitActivity", "全屏档下仍竖着显示的页面",
            "fullForcePortraitActivity", rule.foFullForcePortraitActivity,
            "只在全屏拉伸档生效"
        ) { rule.foFullForcePortraitActivity = it }
    }

    private fun buildAutoUiSection(box: LinearLayout) {
        val body = UiKit.section(
            box, getString(R.string.section_autoui), getString(R.string.section_autoui_desc)
        )

        val chips = UiKit.chipBox(body)
        chip(chips, "autoUiEnable", "开启界面自动适配", null, rule.autoUiEnable) {
            rule.autoUiEnable = it
        }
        chip(
            chips, "autoUiOptimizeWebView", "顺带优化网页内容", "optimizeWebView",
            rule.autoUiOptimizeWebView
        ) { rule.autoUiOptimizeWebView = it }

        txt(
            body, "autoUiActivityRule", "需要适配的页面", "activityRule", rule.autoUiActivityRule,
            "格式 页面:数值，多个用分号隔开"
        ) { rule.autoUiActivityRule = it }
        txt(
            body, "autoUiSkippedActivityRule", "跳过适配的页面", "skippedActivityRule",
            rule.autoUiSkippedActivityRule, "适配后显示异常的页面填这里"
        ) { rule.autoUiSkippedActivityRule = it }
        txt(
            body, "autoUiSkippedAppConfigChange", "跳过的屏幕变化事件", "skippedAppConfigChange",
            rule.autoUiSkippedAppConfigChange, "一般留空"
        ) { rule.autoUiSkippedAppConfigChange = it }
        txt(
            body, "autoUiVersionCode", "本条规则版本号", "versionCode", rule.autoUiVersionCode,
            "改了规则想让系统重新读取时才需要加 1"
        ) { rule.autoUiVersionCode = it }
    }

    private fun buildOverrideSection(box: LinearLayout) {
        val body = UiKit.section(
            box, getString(R.string.section_override), getString(R.string.section_override_desc)
        )

        val chips = UiKit.chipBox(body)
        chip(chips, "overrideUserSwitch", "手动指定系统开关", null, rule.overrideUserSwitch) {
            rule.overrideUserSwitch = it
        }
        chip(chips, "swEmbedded", "平行窗口开关", "embeddedEnable", rule.swEmbedded) {
            rule.swEmbedded = it
        }
        chip(
            chips, "swFixedOrientation", "固定横屏开关", "fixedOrientationEnable",
            rule.swFixedOrientation
        ) { rule.swFixedOrientation = it }
        chip(chips, "swFullScreen", "全屏拉伸开关", "fullScreenEnable", rule.swFullScreen) {
            rule.swFullScreen = it
        }
        UiKit.note(body, getString(R.string.note_override))
    }

    // ── 控件封装（登记 key 以支持冲突高亮） ─────────────────

    private fun chip(
        group: ChipGroup,
        key: String,
        title: String,
        en: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        fieldViews[key] = UiKit.chip(group, title, en, checked) {
            onChange(it)
            validate()
        }
    }

    private fun txt(
        parent: LinearLayout,
        key: String,
        label: String,
        en: String?,
        value: String,
        hint: String? = null,
        onChange: (String) -> Unit
    ) {
        fieldViews[key] = UiKit.textRow(parent, label, en, value, hint) {
            onChange(it)
            validate()
        }
    }

    private fun dropdown(
        parent: LinearLayout,
        key: String,
        label: String,
        en: String?,
        value: String,
        options: List<Pair<String, String>>,
        hint: String? = null,
        onChange: (String) -> Unit
    ) {
        fieldViews[key] = UiKit.dropdownRow(parent, label, en, options, value, hint) {
            onChange(it)
            validate()
        }
    }

    // ── 冲突检测与高亮 ───────────────────────────────────────

    private fun validate() {
        val result = ConflictChecker.check(rule)

        // 清除旧高亮
        fieldViews.values.forEach { v ->
            when (v) {
                is TextInputLayout -> {
                    v.error = null
                    v.isErrorEnabled = false
                }

                is Chip -> {
                    v.chipStrokeColor = ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.chip_stroke_normal)
                    )
                    v.chipStrokeWidth = resources.displayMetrics.density
                }

                else -> Unit
            }
        }

        result.issues.forEach { issue ->
            val error = issue.level == ConflictChecker.Level.ERROR
            val fg = ContextCompat.getColor(
                this, if (error) R.color.conflict_error else R.color.conflict_warn
            )
            issue.fields.forEach { key ->
                when (val v = fieldViews[key]) {
                    is TextInputLayout -> {
                        v.isErrorEnabled = true
                        v.error = issue.message
                        v.setErrorTextColor(ColorStateList.valueOf(fg))
                        v.setErrorIconTintList(ColorStateList.valueOf(fg))
                        v.boxStrokeErrorColor = ColorStateList.valueOf(fg)
                    }

                    is Chip -> {
                        v.chipStrokeColor = ColorStateList.valueOf(fg)
                        v.chipStrokeWidth = resources.displayMetrics.density * 2
                    }

                    else -> Unit
                }
            }
        }

        if (result.isClean) {
            binding.cardConflict.visibility = View.GONE
        } else {
            binding.cardConflict.visibility = View.VISIBLE
            val error = result.hasError
            binding.cardConflict.setCardBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (error) R.color.conflict_error_bg else R.color.conflict_warn_bg
                )
            )
            val fg = ContextCompat.getColor(
                this, if (error) R.color.conflict_error else R.color.conflict_warn
            )
            binding.tvConflictTitle.setTextColor(fg)
            binding.tvConflict.setTextColor(fg)
            binding.tvConflict.text = result.issues.joinToString("\n") { "• ${it.message}" }
            binding.btnFix.visibility =
                if (rule.overrideUserSwitch && error) View.VISIBLE else View.GONE
        }
    }

    private fun save() {
        if (ConflictChecker.check(rule).hasError) {
            Snackbar.make(binding.root, R.string.save_blocked, Snackbar.LENGTH_SHORT).show()
            return
        }
        ConfigRepository.saveRule(rule)
        Snackbar.make(binding.root, R.string.saved, Snackbar.LENGTH_SHORT).show()
    }
}
