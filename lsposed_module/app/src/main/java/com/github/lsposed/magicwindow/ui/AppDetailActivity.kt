package com.github.lsposed.magicwindow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource
import com.github.lsposed.magicwindow.ai.ModelManager
import com.github.lsposed.magicwindow.ai.AiClient
import com.github.lsposed.magicwindow.ai.AiToolExecutor
import com.github.lsposed.magicwindow.data.ActivityLabelCache
import com.github.lsposed.magicwindow.databinding.ActivityAppDetailBinding
import com.github.lsposed.magicwindow.mcp.McpServer
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 单应用详情：暴露 4.10.4 实测出的全部规则属性。
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

    /** true 表示该应用已有用户保存的规则，系统内置值只做徽标，不覆盖任何字段 */
    private var userSaved = false

    /** 用户进入页面后是否动过任何配置；动过之后异步读到的内置值就不再回填，避免覆盖输入 */
    private var dirty = false

    /** 简单模式只显示当前模式的常用项；高级模式显示完整参数表。记忆在全局配置里 */
    private var simpleMode = true

    /** 暂存模式：只在保存时才写入 rule.mode，避免未保存返回时模式已变 */
    private var pendingMode: WindowMode = WindowMode.EMBEDDING

    /** 字段 key → 控件，用于互斥选项（如显示比例三选一）联动 */
    private val fieldViews = linkedMapOf<String, View>()

    /** 页面类名字段的填充方式：LIST 逗号并列；PAIR 写成「页面:*」配对 */
    private enum class Fill { LIST, PAIR, PLACEHOLDER }

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
        pendingMode = rule.mode

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.title = intent.getStringExtra(EXTRA_LABEL) ?: pkg

        binding.tvLabel.text = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        binding.tvPkg.text = pkg
        binding.icon.setImageDrawable(
            runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
        )

        // 系统内置规则提示（若规则表还在后台加载，加载完成后 onSystemRulesReady() 再刷新一次）
        showBuiltinCard()
        if (!SystemRuleSource.loaded) {
            SystemRuleSource.whenLoadedOnMain { onSystemRulesReady() }
        }

        // 系统明确禁用的应用：默认锁定，开关强制修改后才可编辑
        binding.cardForceEdit.visibility =
            if (SystemRuleSource.isFixedDisabled(pkg)) View.VISIBLE else View.GONE
        binding.swForceEdit.isChecked = rule.forceEdit
        binding.swForceEdit.setOnCheckedChangeListener { _, v ->
            rule.forceEdit = v
            applyEditState()
        }

        binding.switchEnabled.isChecked = rule.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, v ->
            dirty = true
            rule.enabled = v
        }

        binding.modeGroup.check(buttonOf(pendingMode))
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            dirty = true
            pendingMode = modeOf(checkedId)
            UiKit.vibrate(this)
            applyModeDefaults()
            updateModeDesc()
            buildForm()
        }
        updateModeDesc()

        // 简单 / 高级切换：默认简单模式，切换只重建表单，不动数据
        binding.detailModeGroup.check(R.id.btnDetailSimple)
        binding.detailModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            simpleMode = checkedId == R.id.btnDetailSimple
            buildForm()
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnReset.setOnClickListener { resetToBuiltin() }
        binding.btnExport.setOnClickListener { exportCurrentRule() }
        binding.btnImport.setOnClickListener { showRuleImportDialog() }

        buildForm()
    }

    /** 系统规则表后台加载完成：未动过的新规则用内置值重建表单，并刷新徽标与读取提示 */
    private fun onSystemRulesReady() {
        if (isFinishing) return
        if (!userSaved && !dirty) {
            SystemRuleSource.applyDefaults(rule)
            pendingMode = rule.mode
            binding.modeGroup.check(buttonOf(pendingMode))
            buildForm()
        }
        showBuiltinCard()
        // 禁用状态要等规则表加载完成后才可知，这里再刷新一次卡片与锁定状态
        binding.cardForceEdit.visibility =
            if (SystemRuleSource.isFixedDisabled(pkg)) View.VISIBLE else View.GONE
        applyEditState()
        SystemRuleSource.errorMessage?.let {
            Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * 系统禁用的应用默认锁定整张表单（模式按钮、开关、输入框、保存），
     * 「强制修改」打开后才解锁；简单/高级切换与恢复默认始终可用。
     */
    private fun applyEditState() {
        val locked = SystemRuleSource.isFixedDisabled(pkg) && !rule.forceEdit
        fun deep(root: View, enabled: Boolean) {
            root.isEnabled = enabled
            (root as? ViewGroup)?.let { g ->
                for (i in 0 until g.childCount) deep(g.getChildAt(i), enabled)
            }
        }
        deep(binding.container, !locked)
        deep(binding.modeGroup, !locked)
        binding.switchEnabled.isEnabled = !locked
        binding.btnSave.isEnabled = !locked
        binding.btnImport.isEnabled = !locked
        binding.container.alpha = if (locked) 0.45f else 1f
    }

    /**
     * 按说明文档的常用样例补默认值（只在用户切换模式/重置时调用，不覆盖内置读取）：
     *   通用全屏 → fullRule="nra:cr:rcr:nr"（文档推荐：不重建 + 裁圆角）
     *   固定横屏 → supportModes="full,fo" defaultSettings="fo"
     *   全屏档   → defaultSettings="full"
     *   平行窗口 → 清掉 fullRule（embedding 规则带上它会被系统判定为不支持平行窗口）
     */
    private fun applyModeDefaults() {
        when (pendingMode) {
            WindowMode.FULL_SCREEN -> {
                rule.fullRule = "nra:cr:rcr:nr"
                rule.foSupportModes = "full,fo"
                rule.foDefaultSettings = "full"
            }

            WindowMode.FIXED_ORIENTATION -> {
                rule.foSupportModes = "full,fo"
                rule.foDefaultSettings = "full"
            }

            WindowMode.EMBEDDING -> {
                rule.fullRule = ""
                rule.foSupportModes = "full,fo"
                // 自动填充 launcher activity 作为默认参与分屏的页面
                if (rule.activityRule.isEmpty()) {
                    val launcher = packageManager.getLaunchIntentForPackage(pkg)
                        ?.component?.className
                    if (launcher != null) {
                        rule.activityRule = launcher
                        rule.splitPairRule = "$launcher:*"
                    }
                }
            }

            WindowMode.OFF -> {}
        }
    }

    /**
     * 恢复默认：有内置规则的应用恢复成系统内置值，没有内置规则的恢复成空默认 + 文档样例值。
     * 内置规则表可能还在后台加载，先等它就绪再取值，避免误恢复成空。
     */
    private fun resetToBuiltin() {
        if (!SystemRuleSource.loaded) {
            Snackbar.make(binding.root, R.string.builtin_loading, Snackbar.LENGTH_SHORT).show()
            SystemRuleSource.whenLoadedOnMain { if (!isFinishing) applyBuiltinDefaults() }
        } else {
            applyBuiltinDefaults()
        }
    }

    private fun applyBuiltinDefaults() {
        val isBuiltin = SystemRuleSource.anyOf(pkg)
        rule = AppRule(pkg)
        if (isBuiltin) {
            SystemRuleSource.applyDefaults(rule)
            // 恢复默认时重置禁用标记，确保规则组合有效
            if (SystemRuleSource.isFixedDisabled(pkg)) {
                rule.foDisable = false
            }
        }
        pendingMode = rule.mode
        applyModeDefaults()
        userSaved = false
        dirty = false
        binding.switchEnabled.isChecked = rule.enabled
        binding.swForceEdit.isChecked = rule.forceEdit
        binding.modeGroup.check(buttonOf(pendingMode))
        buildForm()
        applyEditState()
        UiKit.vibrate(this)
        Snackbar.make(
            binding.root,
            if (isBuiltin) R.string.reset_builtin_done else R.string.reset_default_done,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showBuiltinCard() {
        val kinds = SystemRuleSource.kindsOf(pkg)
        if (kinds.isEmpty()) {
            binding.cardBuiltin.visibility = View.GONE
            return
        }
        binding.cardBuiltin.visibility = View.VISIBLE
        val names = kinds.joinToString(" · ") { kind ->
            BuiltinUi.kindName(this, kind, pkg)
        }
        binding.tvBuiltin.text = getString(R.string.detail_builtin_hint, names)
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

    /** 模式按钮下的人话说明 */
    private fun updateModeDesc() {
        binding.tvModeDesc.text = getString(
            when (pendingMode) {
                WindowMode.OFF -> R.string.mode_off_desc
                WindowMode.FULL_SCREEN -> R.string.mode_full_desc
                WindowMode.EMBEDDING -> R.string.mode_embedding_desc
                WindowMode.FIXED_ORIENTATION -> R.string.mode_fixed_desc
            }
        )
    }

    // ── 表单构建 ─────────────────────────────────────────────

    private fun buildForm() {
        val box = binding.container
        box.removeAllViews()
        fieldViews.clear()

        // 系统禁用且未解锁：置灰提示，控件由 applyEditState 统一禁用
        if (SystemRuleSource.isFixedDisabled(pkg) && !rule.forceEdit) {
            UiKit.note(box, getString(R.string.locked_hint))
        }

        if (simpleMode) {
            buildSimpleForm(box)
        } else {
            // 高级模式：按优先级只显示当前模式的专属参数，低优先级分区直接隐藏
            when (pendingMode) {
                WindowMode.EMBEDDING -> buildEmbeddingSection(box)
                WindowMode.FIXED_ORIENTATION -> buildFixedSection(box)
                WindowMode.FULL_SCREEN -> buildFullScreenSection(box)

                WindowMode.OFF -> UiKit.note(box, getString(R.string.note_mode_no_detail))
            }
            // 界面适配不属于三套互斥机制，任何模式下都保留。
            // 「手动系统开关」分区已取消：选好模式后由系统按内置优先级自动打开对应开关，
            // 不再让用户手动 swEmbedded/swFixedOrientation/swFullScreen，避免与模式推导冲突。
            buildAutoUiSection(box)
        }
        applyEditState()
    }

    /** 简单模式：只给当前模式最常用的几项，配一句话说明，小白照做即可 */
    private fun buildSimpleForm(box: LinearLayout) {
        when (pendingMode) {
            WindowMode.EMBEDDING -> {
                val body = UiKit.section(box, getString(R.string.simple_common))
                val chips = UiKit.chipBox(body)
                chip(chips, "supportFullSize", "可放大到整屏", "supportFullSize", rule.supportFullSize) {
                    rule.supportFullSize = it
                }
                chip(chips, "isShowDivider", "显示中间分割线", "isShowDivider", rule.isShowDivider) {
                    rule.isShowDivider = it
                }
                chip(chips, "relaunch", "切换时重启应用", "relaunch", rule.relaunch) {
                    rule.relaunch = it
                }
                chip(
                    chips, "finishSecondaryWithPrimary", "左栏关闭时右栏一起关",
                    "finishSecondaryWithPrimary", rule.finishSecondaryWithPrimary
                ) { rule.finishSecondaryWithPrimary = it }
                UiKit.note(body, getString(R.string.group_pages))
                txt(
                    body, "splitPairRule", "左右两栏的配对方式", "splitPairRule", rule.splitPairRule,
                    "点放大镜抓取页面；默认填成 页面:*，可再改",
                    Fill.PAIR
                ) { rule.splitPairRule = it }
                txt(
                    body, "placeholder", "右栏默认打开的页面", "placeholder", rule.placeholder,
                    "主页面在左栏打开时，右栏默认显示的页面；点放大镜选择两个页面",
                    Fill.PLACEHOLDER
                ) { rule.placeholder = it }
                UiKit.note(body, getString(R.string.note_embedding))
            }

            WindowMode.FIXED_ORIENTATION -> {
                val body = UiKit.section(box, getString(R.string.simple_common))
                dropdown(
                    body, "foDefaultSettings", "默认用哪一档", "defaultSettings", rule.foDefaultSettings,
                    listOf(
                        "fo" to "横屏信箱（画面居中留黑边）",
                        "full" to "全屏拉伸（铺满屏幕）",
                        "" to UNSET
                    )
                ) { rule.foDefaultSettings = it }
                val chips = UiKit.chipBox(body)
                chip(chips, "foRelaunch", "切换时重启应用", "relaunch", rule.foRelaunch) {
                    rule.foRelaunch = it
                }
                chip(chips, "foIsShowDivider", "显示中间分割线", "isShowDivider", rule.foIsShowDivider) {
                    rule.foIsShowDivider = it
                }
                txt(
                    body, "foRatio", "显示比例（宽高比）", "ratio", rule.foRatio,
                    "1.x~2.x 的小数，如 1.1 接近大折叠屏比例；0 或留空不限制"
                ) { rule.foRatio = it }
                UiKit.note(body, getString(R.string.note_fixed))
            }

            WindowMode.FULL_SCREEN -> buildFullScreenSection(box)

            WindowMode.OFF -> UiKit.note(box, getString(R.string.note_mode_no_detail))
        }

        // 界面自动适配：最常用的一个开关
        val au = UiKit.section(box, getString(R.string.section_autoui))
        val auChips = UiKit.chipBox(au)
        chip(auChips, "autoUiEnable", "开启界面自动适配", null, rule.autoUiEnable) {
            rule.autoUiEnable = it
        }
        UiKit.note(au, getString(R.string.section_autoui_desc))

        UiKit.note(box, getString(R.string.advanced_hint))
    }

    private fun buildEmbeddingSection(box: LinearLayout) {
        val body = UiKit.section(box, getString(R.string.section_embedding))

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
        chip(
            chips, "finishPrimaryWithSecondary", "右栏关闭时左栏一起关",
            "finishPrimaryWithSecondary", rule.finishPrimaryWithSecondary
        ) { rule.finishPrimaryWithSecondary = it }
        chip(chips, "disableSensor", "禁用重力感应旋屏", "disableSensor", rule.disableSensor) {
            rule.disableSensor = it
        }
        chip(chips, "allowRepeatPage", "允许重复打开同一页面", "allowRepeatPage", rule.allowRepeatPage) {
            rule.allowRepeatPage = it
        }
        chip(chips, "isShowDialog", "模式切换时弹提示框", "isShowDialog", rule.isShowDialog) {
            rule.isShowDialog = it
        }
        chip(chips, "useMiuiSplit", "改用系统分屏实现", "useMiuiSplit", rule.useMiuiSplit) {
            rule.useMiuiSplit = it
        }
        chip(
            chips, "miuiMagicWinEnabled", "应用内魔法窗开关", "miuiMagicWinEnabled",
            rule.miuiMagicWinEnabled
        ) { rule.miuiMagicWinEnabled = it }
        chip(
            chips, "embForceKillWhenSwitch", "换档时结束进程", "forceKillWhenSwitch",
            rule.embForceKillWhenSwitch
        ) { rule.embForceKillWhenSwitch = it }
        UiKit.note(body, getString(R.string.note_embedding))

        UiKit.note(body, getString(R.string.group_pages))
        txt(
            body, "activityRule", "参与分屏的页面", "activityRule", rule.activityRule,
            "填 Activity 全类名，多个用英文逗号隔开；可点放大镜抓取",
            Fill.LIST
        ) { rule.activityRule = it }
        txt(
            body, "splitPairRule", "左右两栏的配对方式", "splitPairRule", rule.splitPairRule,
            "抓取后填成 页面:*，可再改右栏",
            Fill.PAIR
        ) { rule.splitPairRule = it }
        txt(
            body, "placeholder", "右栏默认打开的页面", "placeholder", rule.placeholder,
            "主页面在左栏打开时，右栏默认显示的页面；点放大镜选择两个页面", Fill.PLACEHOLDER
        ) { rule.placeholder = it }
        txt(
            body, "transitionRules", "不做切换动画的页面", "transitionRules", rule.transitionRules,
            "多个用英文逗号隔开", Fill.LIST
        ) { rule.transitionRules = it }
        txt(
            body, "forcePortraitActivity", "始终竖着显示的页面", "forcePortraitActivity",
            rule.forcePortraitActivity, "这些页面不参与分屏", Fill.LIST
        ) { rule.forcePortraitActivity = it }

        UiKit.note(body, getString(R.string.group_layout))
        // splitRatio 使用滑块，支持无极拖动和节点震动反馈
        UiKit.sliderRow(
            body, "左栏宽度占比", "splitRatio",
            rule.splitRatio.toFloatOrNull() ?: 0.35f,
            valueRange = 0.1f..0.9f,
            steps = listOf(0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f, 0.45f, 0.5f, 0.55f, 0.6f, 0.65f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f),
            hint = "0~1 的小数，如 0.35"
        ) { rule.splitRatio = it.toString() }
        txt(
            body, "splitMinWidth", "分栏最小宽度", "splitMinWidth", rule.splitMinWidth,
            "低于该宽度不分栏，单位 dp"
        ) { rule.splitMinWidth = it }
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
            "如 forceResumeOnFocus:com.x.MainActivity", Fill.LIST
        ) { rule.flags = it }
        txt(
            body, "procCompat", "进程级兼容处理", "procCompat", rule.procCompat,
            "按应用进程名做兼容，一般留空"
        ) { rule.procCompat = it }
        txt(
            body, "autoUiRule", "顺带启用的界面适配规则", "autoUiRule", rule.autoUiRule,
            "格式 页面:数值，多个用分号隔开", Fill.LIST
        ) { rule.autoUiRule = it }
        txt(
            body, "defaultSettings", "默认档位", "defaultSettings", rule.defaultSettings,
            "应用第一次打开时用哪一档"
        ) { rule.defaultSettings = it }

        txt(
            body, "splitMinSmallestWidth", "分栏最小最短边", "splitMinSmallestWidth",
            rule.splitMinSmallestWidth, "低于该最短边（dp）不分栏"
        ) { rule.splitMinSmallestWidth = it }
        txt(
            body, "layoutDirection", "分栏方向", "layoutDirection", rule.layoutDirection,
            "留空跟随系统；一般填 0/1/2"
        ) { rule.layoutDirection = it }
        txt(
            body, "killApps", "换档时结束的应用", "killApps", rule.killApps,
            "包名列表，多个用英文逗号隔开"
        ) { rule.killApps = it }
        txt(
            body, "forcePortraitWhenSwitch", "换档时强制竖屏的页面", "forcePortraitWhenSwitch",
            rule.forcePortraitWhenSwitch, "多个用英文逗号隔开", Fill.LIST
        ) { rule.forcePortraitWhenSwitch = it }
        txt(
            body, "sizecompatRatio", "兼容模式比例", "sizecompatRatio", rule.sizecompatRatio,
            "留空不设置"
        ) { rule.sizecompatRatio = it }
        txt(
            body, "sizecompatRule", "兼容模式页面规则", "sizecompatRule", rule.sizecompatRule,
            "格式 页面:数值，多个逗号隔开", Fill.LIST
        ) { rule.sizecompatRule = it }
        txt(
            body, "transparentBar", "透明导航栏", "transparentBar", rule.transparentBar,
            "填 true / false，留空跟随系统默认"
        ) { rule.transparentBar = it }
        txt(
            body, "embAdaptCutout", "挖孔屏适配", "adaptCutout", rule.embAdaptCutout,
            "-1 跟随系统，0 始终，1 短边，2 从不"
        ) { rule.embAdaptCutout = it }
        txt(
            body, "embRelaunchRule", "重启规则", "relaunchRule", rule.embRelaunchRule,
            "格式 DefaultScenario:true:页面名"
        ) { rule.embRelaunchRule = it }
        txt(
            body, "version", "规则生效的应用版本上限", "version", rule.version,
            "应用版本号低于此值规则才生效，纯数字，如 500；留空不限制"
        ) { rule.version = it }
    }

    /** 通用全屏：唯一专属参数是 fullRule（决定哪些页面整屏显示，缺省按 "*" 全部整屏） */
    private fun buildFullScreenSection(box: LinearLayout) {
        val body = UiKit.section(box, getString(R.string.section_fullscreen))
        dropdown(
            body, "fullRule", "整屏显示方式", "fullRule", rule.fullRule,
            listOf(
                "nra:cr:rcr:nr" to "推荐：不重建+裁圆角（nra:cr:rcr:nr）",
                "" to "默认：所有页面都整屏（*）",
                "nra" to "整屏时不重建页面（nra）",
                "nra:cr:rcr" to "不重建 + 裁剪圆角（nra:cr:rcr）"
            )
        ) { rule.fullRule = it }
        UiKit.note(body, getString(R.string.note_fullscreen))
    }

    private fun buildFixedSection(box: LinearLayout) {
        val body = UiKit.section(box, getString(R.string.section_fixed))

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
        chip(chips, "foIsShowDivider", "显示中间分割线", "isShowDivider", rule.foIsShowDivider) {
            rule.foIsShowDivider = it
        }
        chip(chips, "foSkipSelfAdaptive", "跳过应用自适应", "skipSelfAdaptive", rule.foSkipSelfAdaptive) {
            rule.foSkipSelfAdaptive = it
        }
        chip(chips, "foAllPortrait", "所有页面都竖屏显示", "allPortrait", rule.foAllPortrait) {
            rule.foAllPortrait = it
        }
        chip(chips, "foAutoUI", "顺带启用界面适配", "autoUI", rule.foAutoUI) {
            rule.foAutoUI = it
        }
        chip(chips, "foDisable", "停用该应用的固定横屏", "disable", rule.foDisable) {
            rule.foDisable = it
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

        UiKit.note(body, "显示比例（对应系统设置里的画面比例，三选一，都不勾则按默认档）")
        val ratioChips = UiKit.chipBox(body)
        // 三选一：勾上一个要清掉另外两个（view 与 model 同步）
        fun pickRatio(others: List<String>, checked: Boolean, set: (Boolean) -> Unit) {
            set(checked)
            if (checked) others.forEach { k ->
                (fieldViews[k] as? Chip)?.isChecked = false  // 监听会顺带把 model 置 false
            }
        }
        chip(ratioChips, "ratio43Enable", "4:3 比例", "ratio_4_3", rule.ratio43Enable) {
            pickRatio(listOf("ratio169Enable", "ratioFullScreenEnable"), it) { v -> rule.ratio43Enable = v }
        }
        chip(ratioChips, "ratio169Enable", "16:9 比例", "ratio_16_9", rule.ratio169Enable) {
            pickRatio(listOf("ratio43Enable", "ratioFullScreenEnable"), it) { v -> rule.ratio169Enable = v }
        }
        chip(ratioChips, "ratioFullScreenEnable", "全屏拉伸", "full", rule.ratioFullScreenEnable) {
            pickRatio(listOf("ratio43Enable", "ratio169Enable"), it) { v -> rule.ratioFullScreenEnable = v }
        }

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
            rule.foForcePortraitActivity, "多个用英文逗号隔开", Fill.LIST
        ) { rule.foForcePortraitActivity = it }
        txt(
            body, "foFullForcePortraitActivity", "全屏档下仍竖着显示的页面",
            "fullForcePortraitActivity", rule.foFullForcePortraitActivity,
            "只在全屏拉伸档生效", Fill.LIST
        ) { rule.foFullForcePortraitActivity = it }

        UiKit.note(body, getString(R.string.group_advanced))
        dropdown(
            body, "foDisableCameraPreview", "禁用相机预览", "disableCameraPreview",
            rule.foDisableCameraPreview,
            listOf("" to UNSET, "true" to "禁用（true）", "false" to "不禁用（false）"),
            "应用打开相机时画面异常可以试"
        ) { rule.foDisableCameraPreview = it }
        dropdown(
            body, "foAdjustmentOrientation", "旋转方向调整", "adjustmentOrientation",
            rule.foAdjustmentOrientation,
            listOf(
                "" to UNSET,
                "0" to "不调整（0，系统默认）",
                "1" to "调整旋转方向（1）"
            ),
            "部分应用旋转方向相反时用"
        ) { rule.foAdjustmentOrientation = it }
        txt(
            body, "foAdjustmentOrientationActivity", "单独调整旋转方向的页面",
            "adjustmentOrientationActivity", rule.foAdjustmentOrientationActivity,
            "格式 包名/类名:1，可抓取后补 :1", Fill.LIST
        ) { rule.foAdjustmentOrientationActivity = it }
        txt(
            body, "foRatio", "宽高比", "ratio", rule.foRatio,
            "1.x~2.x 的小数；0 或留空表示不限制"
        ) { rule.foRatio = it }
        txt(
            body, "foRelaunchRule", "重启规则", "relaunchRule", rule.foRelaunchRule,
            "格式 DefaultScenario:true:页面名", Fill.LIST
        ) { rule.foRelaunchRule = it }
        dropdown(
            body, "foTransparentBar", "透明导航栏", "transparentBar",
            rule.foTransparentBar,
            listOf("" to UNSET, "true" to "透明（true，系统默认）", "false" to "不透明（false）")
        ) { rule.foTransparentBar = it }
        txt(
            body, "foAdaptCutout", "挖孔屏适配", "adaptCutout", rule.foAdaptCutout,
            "-1 跟随系统，0 始终，1 短边，2 从不"
        ) { rule.foAdaptCutout = it }
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
            "格式 页面:数值，多个用分号隔开；可先抓取页面", Fill.LIST
        ) { rule.autoUiActivityRule = it }
        txt(
            body, "autoUiSkippedActivityRule", "跳过适配的页面", "skippedActivityRule",
            rule.autoUiSkippedActivityRule, "适配后显示异常的页面填这里", Fill.LIST
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

    // ── 控件封装 ─────────────────────────────────────────────

    private fun chip(
        group: ChipGroup,
        key: String,
        title: String,
        en: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        fieldViews[key] = UiKit.chip(group, title, en, checked) {
            dirty = true
            UiKit.vibrate(this)
            onChange(it)
        }
    }

    private fun txt(
        parent: LinearLayout,
        key: String,
        label: String,
        en: String?,
        value: String,
        hint: String? = null,
        fill: Fill? = null,
        onChange: (String) -> Unit
    ) {
        var tilRef: TextInputLayout? = null
        val til = UiKit.textRow(parent, label, en, value, hint,
            onCapture = if (fill == null) null else {
                {
                    val tilNow = tilRef ?: return@textRow
                    showPagePicker(fill, currentTextOf(tilNow), key) { picked ->
                        tilNow.findViewById<TextInputEditText>(R.id.et).setText(picked)
                    }
                }
            }
        ) {
            dirty = true
            onChange(it)
        }
        tilRef = til
        fieldViews[key] = til
    }

    private fun currentTextOf(til: TextInputLayout): String =
        til.findViewById<TextInputEditText>(R.id.et)?.text?.toString().orEmpty()

    /**
     * 抓取目标应用的页面（Activity）列表并多选填充。
     * LIST 填充把选中的类名用英文逗号并列；PAIR 填充写成「页面:*」（配对规则的常用写法）。
     * 支持按类名/功能名实时筛选；启动页标为「★ 主界面」置顶；每行小字显示页面功能名。
     */
    /**
     * 显示页面抓取对话框。
     * @param fieldContext 入口字段名，用于 AI 上下文感知推荐（如 "activityRule", "forcePortraitActivity" 等）
     */
    private fun showPagePicker(fill: Fill, current: String, fieldContext: String = "", onDone: (String) -> Unit) {
        // placeholder 是「主页面:占位页面」配对，走专用的两段单选选择器
        if (fill == Fill.PLACEHOLDER) {
            showPlaceholderPairPicker(current, onDone)
            return
        }
        val pm = packageManager
        val acts = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
        }.getOrNull()
        if (acts.isNullOrEmpty()) {
            Snackbar.make(binding.root, R.string.capture_none, Snackbar.LENGTH_SHORT).show()
            return
        }
        val launcher = runCatching {
            pm.getLaunchIntentForPackage(pkg)?.component?.className
        }.getOrNull()

        data class Item(val name: String, val label: String, val isMain: Boolean, val zhDesc: String = "")

        // 加载本地缓存的中文描述
        val cachedLabels = ActivityLabelCache.getAllForPackage(this@AppDetailActivity, pkg)

        val items = acts.mapNotNull { a ->
            a.name?.let { name ->
                val label = runCatching { a.loadLabel(pm).toString() }.getOrDefault("")
                val zhDesc = cachedLabels[name] ?: ""
                Item(name, label, name == launcher, zhDesc)
            }
        }.distinctBy { it.name }
            .sortedWith(compareByDescending<Item> { it.isMain }.thenBy { it.name })

        val currentItems = current.split(',', ';')
            .map { it.trim().let { s -> if (fill == Fill.PAIR) s.substringBefore(':') else s } }
            .filter { it.isNotEmpty() }
            .toSet()
        val checkedMap = HashMap<String, Boolean>()
        items.forEach { if (it.name in currentItems) checkedMap[it.name] = true }

        // AI 建议结果缓存（key=Activity名，value=AI建议标签）
        val aiSuggestions = HashMap<String, String>()
        val aiSuggestedNames = HashSet<String>()
        // AI 中文描述缓存（用于更新列表显示）
        val zhDescCache = HashMap<String, String>()

        // 如果 AI 已配置（存在已启用模型），后台获取 AI 建议（延迟到 listAdapter 定义后启动）
        val aiConfigured = ModelManager.getCurrent(this@AppDetailActivity) != null

        // 根据入口字段生成上下文提示
        val contextHint = when (fieldContext) {
            "activityRule" -> "参与分屏的页面"
            "splitPairRule" -> "左右栏配对的页面"
            "placeholder" -> "右栏默认显示的页面"
            "relaunchRule", "relunchRule" -> "需要重启的页面"
            "forcePortraitActivity" -> "始终竖着显示的页面"
            "sizeCompatRule" -> "需要尺寸兼容的页面"
            "autoUiActivityRule" -> "需要界面自动适配的页面"
            "flags" -> "有特殊标记需求的页面"
            else -> "适合该功能的页面"
        }

        // 过滤框
        val search = EditText(this).apply {
            hint = getString(R.string.picker_filter_hint)
            setSingleLine()
        }

        // 列表：使用自定义布局，优化长类名和中文说明显示
        val gray = ContextCompat.getColor(this, R.color.field_en)
        val greenColor = ContextCompat.getColor(this, R.color.ok_green)
        val errorColor = ContextCompat.getColor(this, R.color.conflict_error)
        val listAdapter = object : BaseAdapter() {
            var shown: List<Item> = items

            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Item = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(this@AppDetailActivity)
                    .inflate(R.layout.item_page_picker, parent, false)

                val item = shown[position]
                val tvClassName = v.findViewById<TextView>(R.id.tvClassName)
                val tvDesc = v.findViewById<TextView>(R.id.tvDesc)
                val tvAiTag = v.findViewById<TextView>(R.id.tvAiTag)
                val cb = v.findViewById<CheckBox>(R.id.cb)

                // 类名：主界面加 ★ 标记
                val displayName = if (item.isMain) "★ ${item.name}" else item.name
                tvClassName.text = displayName

                // 中文说明：优先用缓存，其次用 AI 实时结果
                val zhDesc = item.zhDesc.ifEmpty { zhDescCache[item.name] ?: "" }
                if (zhDesc.isNotEmpty()) {
                    tvDesc.visibility = View.VISIBLE
                    tvDesc.text = zhDesc
                    tvDesc.setTextColor(gray)
                } else {
                    tvDesc.visibility = View.GONE
                }

                // AI 建议标签
                val aiTag = aiSuggestions[item.name]
                if (aiTag != null) {
                    tvAiTag.visibility = View.VISIBLE
                    tvAiTag.text = "🤖 $aiTag"
                    val tagColor = when {
                        aiTag.contains("适合") || aiTag.contains("主") || aiTag.contains("详情") || aiTag.contains("播放") -> greenColor
                        aiTag.contains("不参与") -> errorColor
                        else -> gray
                    }
                    tvAiTag.setTextColor(tagColor)
                } else {
                    tvAiTag.visibility = View.GONE
                }

                cb.isChecked = checkedMap[item.name] == true
                cb.setOnClickListener {
                    checkedMap[item.name] = cb.isChecked
                }
                return v
            }
        }
        val list = ListView(this).apply {
            adapter = listAdapter
            dividerHeight = 0
            setOnItemClickListener { _, _, pos, _ ->
                val item = listAdapter.shown[pos]
                checkedMap[item.name] = !(checkedMap[item.name] ?: false)
                listAdapter.notifyDataSetChanged()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                listAdapter.shown = if (q.isEmpty()) items else items.filter {
                    it.name.contains(q, true) || it.label.contains(q, true)
                }
                listAdapter.notifyDataSetChanged()
            }
        })

        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((dp * 20).toInt(), (dp * 8).toInt(), (dp * 20).toInt(), 0)

            // AI 勾选推荐按钮
            if (ModelManager.getCurrent(this@AppDetailActivity) != null) {
                val aiRecommendBtn = com.google.android.material.button.MaterialButton(
                    this@AppDetailActivity
                ).apply {
                    text = "🤖 勾选AI推荐（$contextHint）"
                    setOnClickListener {
                        aiSuggestedNames.forEach { name -> checkedMap[name] = true }
                        listAdapter.notifyDataSetChanged()
                        Snackbar.make(
                            binding.root,
                            getString(R.string.ai_suggestion_applied, aiSuggestedNames.size),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginEnd = (dp * 4).toInt() }
                }

                // 补全中文说明按钮
                val aiFillDescBtn = com.google.android.material.button.MaterialButton(
                    androidx.appcompat.view.ContextThemeWrapper(this@AppDetailActivity, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)
                ).apply {
                    text = "补全中文说明"
                    setOnClickListener {
                        lifecycleScope.launch {
                            try {
                                val client = AiClient(this@AppDetailActivity)
                                // 专用 prompt：只补全中文说明，不影响勾选状态
                                val activityList = items.joinToString("\n") { "  ${it.name}" }
                                val prompt = buildString {
                                    appendLine("请分析 $pkg 的以下 Android Activity 页面，为每个页面补充简短的中文功能说明。")
                                    appendLine()
                                    appendLine("页面列表：")
                                    appendLine(activityList)
                                    appendLine()
                                    appendLine("请按以下格式返回（每行一个）：")
                                    appendLine("Activity类名|中文说明")
                                    appendLine()
                                    appendLine("中文说明要求：简短准确，描述该页面的功能用途。")
                                    appendLine("示例：")
                                    appendLine("com.example.MainActivity|应用首页")
                                    appendLine("com.example.LoginActivity|登录注册页面")
                                }
                                val messages = listOf(
                                    AiClient.ChatMessage("system", "你是 Android 应用分析助手，只返回格式化的中文说明列表，不要解释。"),
                                    AiClient.ChatMessage("user", prompt)
                                )
                                val reply = client.chat(messages) { name, args ->
                                    AiToolExecutor.execute(this@AppDetailActivity, name, args)
                                }
                                // 只解析中文说明，不修改勾选状态和推荐集合
                                reply.lines().forEach { line ->
                                    val parts = line.split("|", "：", ":", limit = 2)
                                    if (parts.size >= 2) {
                                        val actName = parts[0].trim().removePrefix("★").trim()
                                        val zhDesc = parts[1].trim()
                                        if (actName.isNotEmpty() && zhDesc.isNotEmpty()) {
                                            zhDescCache[actName] = zhDesc
                                        }
                                    }
                                }
                                // 缓存到本地，下次抓取直接使用
                                if (zhDescCache.isNotEmpty()) {
                                    ActivityLabelCache.putAll(this@AppDetailActivity, pkg, HashMap(zhDescCache))
                                }
                                listAdapter.notifyDataSetChanged()
                                Snackbar.make(binding.root, "AI 已补全 ${zhDescCache.size} 个页面的中文说明", Snackbar.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Snackbar.make(binding.root, "补全失败：${e.message}", Snackbar.LENGTH_SHORT).show()
                            }
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }

                // AI 按钮行（横排）
                val aiBtnRow = LinearLayout(this@AppDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (dp * 8).toInt() }
                    addView(aiRecommendBtn)
                    addView(aiFillDescBtn)
                }
                addView(aiBtnRow)
            }

            // 批量操作按钮行
            val batchBtnRow = LinearLayout(this@AppDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (dp * 8).toInt() }
            }

            fun batchBtn(label: String, action: () -> Unit): com.google.android.material.button.MaterialButton {
                return com.google.android.material.button.MaterialButton(
                    androidx.appcompat.view.ContextThemeWrapper(this@AppDetailActivity, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)
                ).apply {
                    text = label
                    textSize = 12f
                    setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginEnd = (dp * 4).toInt() }
                }
            }

            batchBtnRow.addView(batchBtn("全选") {
                items.forEach { checkedMap[it.name] = true }
                listAdapter.notifyDataSetChanged()
            })
            batchBtnRow.addView(batchBtn("取消全选") {
                items.forEach { checkedMap[it.name] = false }
                listAdapter.notifyDataSetChanged()
            })
            batchBtnRow.addView(batchBtn("反选") {
                items.forEach { checkedMap[it.name] = checkedMap[it.name] != true }
                listAdapter.notifyDataSetChanged()
            })
            addView(batchBtnRow)

            addView(search)
            addView(list)

            // AI 已配置时，后台获取建议
            if (aiConfigured) {
                lifecycleScope.launch {
                    try {
                        val client = AiClient(this@AppDetailActivity)
                        val prompt = buildContextAwarePrompt(pkg, items.map { it.name to it.label }, fieldContext)
                        val messages = listOf(
                            AiClient.ChatMessage("system", "你是 Android Activity 用途分析助手。只返回格式化的标签列表，不要解释。"),
                            AiClient.ChatMessage("user", prompt)
                        )
                        val reply = client.chat(messages) { name, args ->
                            AiToolExecutor.execute(this@AppDetailActivity, name, args)
                        }
                        parseAiSuggestions(reply, aiSuggestions, aiSuggestedNames, checkedMap, pkg, zhDescCache)
                        listAdapter.notifyDataSetChanged()
                    } catch (_: Exception) { }
                }
            }
            list.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.45f).toInt()
            ).apply { topMargin = (dp * 12).toInt() }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_pages)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val picked = items.filter { checkedMap[it.name] == true }.map { it.name }
                if (picked.isEmpty()) return@setPositiveButton
                onDone(
                    when (fill) {
                        Fill.PAIR -> picked.joinToString(",") { "$it:*" }
                        Fill.LIST -> picked.joinToString(",")
                        // PLACEHOLDER 已在函数入口分流到专用选择器
                        Fill.PLACEHOLDER -> picked.joinToString(",")
                    }
                )
                Snackbar.make(
                    binding.root, getString(R.string.capture_done, picked.size),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * placeholder 专用选择器：分别单选「主页面（左栏）」和「占位页面（右栏默认页）」，
     * 确认后生成「主页面:占位页面」；已有的其他配对会保留。
     */
    private fun showPlaceholderPairPicker(current: String, onDone: (String) -> Unit) {
        val pm = packageManager
        val acts = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
        }.getOrNull()
        if (acts.isNullOrEmpty()) {
            Snackbar.make(binding.root, R.string.capture_none, Snackbar.LENGTH_SHORT).show()
            return
        }
        val launcher = runCatching {
            pm.getLaunchIntentForPackage(pkg)?.component?.className
        }.getOrNull()

        data class PairItem(val name: String, val label: String, val isMain: Boolean, val zhDesc: String = "")

        val cachedLabels = ActivityLabelCache.getAllForPackage(this, pkg)
        // 可变中文说明表：初始取本地缓存，AI 补全后实时更新
        val zhDescMap = HashMap(cachedLabels)
        val items = acts.mapNotNull { a ->
            a.name?.let { name ->
                val label = runCatching { a.loadLabel(pm).toString() }.getOrDefault("")
                PairItem(name, label, name == launcher, cachedLabels[name] ?: "")
            }
        }.distinctBy { it.name }
            .sortedWith(compareByDescending<PairItem> { it.isMain }.thenBy { it.name })

        // 解析已有配对（主页面:占位页面，多对用逗号分隔）
        val existingPairs = current.split(',', ';').mapNotNull { seg ->
            val p = seg.trim().split(':', limit = 2)
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) p[0].trim() to p[1].trim()
            else null
        }

        val dp = resources.displayMetrics.density
        val gray = ContextCompat.getColor(this, R.color.field_en)
        val listHeight = (resources.displayMetrics.heightPixels * 0.20f).toInt()

        val search = EditText(this).apply {
            hint = getString(R.string.picker_filter_hint)
            setSingleLine()
        }

        // 单选列表适配器，复用 item_page_picker 布局，CheckBox 当作单选圆点用
        fun makeAdapter(initialSelection: String?) = object : BaseAdapter() {
            var shown: List<PairItem> = items
            var selected: String? = initialSelection
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): PairItem = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(this@AppDetailActivity)
                    .inflate(R.layout.item_page_picker, parent, false)
                val item = shown[position]
                v.findViewById<TextView>(R.id.tvClassName).text =
                    if (item.isMain) "★ ${item.name}" else item.name
                val tvDesc = v.findViewById<TextView>(R.id.tvDesc)
                val zhDesc = zhDescMap[item.name].orEmpty()
                if (zhDesc.isNotEmpty()) {
                    tvDesc.visibility = View.VISIBLE
                    tvDesc.text = zhDesc
                    tvDesc.setTextColor(gray)
                } else {
                    tvDesc.visibility = View.GONE
                }
                v.findViewById<TextView>(R.id.tvAiTag).visibility = View.GONE
                v.findViewById<CheckBox>(R.id.cb).isChecked = selected == item.name
                return v
            }
        }

        val primaryAdapter = makeAdapter(existingPairs.firstOrNull()?.first)
        val placeholderAdapter = makeAdapter(existingPairs.firstOrNull()?.second)

        fun makeList(adapter: BaseAdapter, onSelect: (String) -> Unit) = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeight
            )
            setOnItemClickListener { _, _, pos, _ ->
                @Suppress("UNCHECKED_CAST")
                val item = (adapter.getItem(pos) as PairItem)
                onSelect(item.name)
                adapter.notifyDataSetChanged()
            }
        }

        val primaryList = makeList(primaryAdapter) { name ->
            primaryAdapter.selected = name
        }
        val placeholderList = makeList(placeholderAdapter) { name ->
            placeholderAdapter.selected = name
        }

        // 筛选同时作用于两个列表
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                val filtered = if (q.isEmpty()) items else items.filter {
                    it.name.contains(q, true) || it.label.contains(q, true)
                }
                primaryAdapter.shown = filtered
                placeholderAdapter.shown = filtered
                primaryAdapter.notifyDataSetChanged()
                placeholderAdapter.notifyDataSetChanged()
            }
        })

        fun sectionTitle(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (dp * 10).toInt(), 0, (dp * 4).toInt())
        }

        fun outlinedBtn(label: String, action: () -> Unit) =
            com.google.android.material.button.MaterialButton(
                androidx.appcompat.view.ContextThemeWrapper(
                    this@AppDetailActivity,
                    com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton
                )
            ).apply {
                text = label
                textSize = 12f
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = (dp * 4).toInt() }
            }

        // AI 已配置时提供「推荐配对」与「补全中文说明」
        val aiConfigured = ModelManager.getCurrent(this) != null
        var aiBtnRow: LinearLayout? = null
        if (aiConfigured) {
            aiBtnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // AI 推荐配对：自动选出主页面和占位页面
                addView(com.google.android.material.button.MaterialButton(this@AppDetailActivity).apply {
                    text = "🤖 AI推荐配对"
                    textSize = 12f
                    setOnClickListener {
                        lifecycleScope.launch {
                            try {
                                val client = AiClient(this@AppDetailActivity)
                                val prompt = buildString {
                                    appendLine("请分析 $pkg 的以下 Android Activity 页面，为平行窗口（左右分栏）选出一对页面：")
                                    appendLine("1. 主页面：应用启动后首先进入的核心主页面（通常是标 ★ 的启动页）")
                                    appendLine("2. 占位页面：主页面在左栏打开时，适合在右栏默认显示的页面；优先选内容简单的列表页、引导页或空白页，不要选登录、验证码、网页验证等页面，也不要与主页面相同")
                                    appendLine()
                                    appendLine("页面列表：")
                                    items.forEach { appendLine("  ${it.name}") }
                                    appendLine()
                                    appendLine("只返回两行，每行格式：Activity类名|角色|中文说明")
                                    appendLine("角色只能是「主页面」或「占位页面」，示例：")
                                    appendLine("com.example.MainActivity|主页面|应用首页")
                                    appendLine("com.example.PlaceholderActivity|占位页面|右栏默认列表")
                                }
                                val messages = listOf(
                                    AiClient.ChatMessage("system", "你是 Android 应用分析助手，只按规定格式返回两行结果，不要解释。"),
                                    AiClient.ChatMessage("user", prompt)
                                )
                                val reply = client.chat(messages) { name, args ->
                                    AiToolExecutor.execute(this@AppDetailActivity, name, args)
                                }
                                var pickedPrimary: String? = null
                                var pickedPlaceholder: String? = null
                                reply.lines().forEach { line ->
                                    val parts = line.split("|", "：", ":", limit = 3)
                                    if (parts.size >= 2) {
                                        val actName = parts[0].trim().removePrefix("★").trim()
                                        if (items.none { it.name == actName }) return@forEach
                                        val tag = parts[1]
                                        when {
                                            tag.contains("占位") -> pickedPlaceholder = actName
                                            tag.contains("主") -> pickedPrimary = actName
                                        }
                                        if (parts.size >= 3 && parts[2].trim().isNotEmpty()) {
                                            zhDescMap[actName] = parts[2].trim()
                                        }
                                    }
                                }
                                pickedPrimary?.let { primaryAdapter.selected = it }
                                pickedPlaceholder?.let { placeholderAdapter.selected = it }
                                if (zhDescMap.isNotEmpty()) {
                                    ActivityLabelCache.putAll(this@AppDetailActivity, pkg, HashMap(zhDescMap))
                                }
                                primaryAdapter.notifyDataSetChanged()
                                placeholderAdapter.notifyDataSetChanged()
                                Snackbar.make(
                                    binding.root,
                                    when {
                                        pickedPrimary != null && pickedPlaceholder != null ->
                                            "AI 已选好主页面和占位页面，请确认"
                                        pickedPrimary != null || pickedPlaceholder != null ->
                                            "AI 只识别出一个页面，请手动补选另一个"
                                        else -> "AI 未能识别合适的页面，请手动选择"
                                    },
                                    Snackbar.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Snackbar.make(binding.root, "推荐失败：${e.message}", Snackbar.LENGTH_SHORT).show()
                            }
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginEnd = (dp * 4).toInt() }
                })
                // 补全中文说明：只更新说明，不影响选择
                addView(outlinedBtn("补全中文说明") {
                    lifecycleScope.launch {
                        try {
                            val client = AiClient(this@AppDetailActivity)
                            val prompt = buildString {
                                appendLine("请分析 $pkg 的以下 Android Activity 页面，为每个页面补充简短的中文功能说明。")
                                appendLine()
                                appendLine("页面列表：")
                                items.forEach { appendLine("  ${it.name}") }
                                appendLine()
                                appendLine("请按以下格式返回（每行一个）：")
                                appendLine("Activity类名|中文说明")
                                appendLine("示例：com.example.MainActivity|应用首页")
                            }
                            val messages = listOf(
                                AiClient.ChatMessage("system", "你是 Android 应用分析助手，只返回格式化的中文说明列表，不要解释。"),
                                AiClient.ChatMessage("user", prompt)
                            )
                            val reply = client.chat(messages) { name, args ->
                                AiToolExecutor.execute(this@AppDetailActivity, name, args)
                            }
                            reply.lines().forEach { line ->
                                val parts = line.split("|", "：", ":", limit = 2)
                                if (parts.size >= 2) {
                                    val actName = parts[0].trim().removePrefix("★").trim()
                                    val zh = parts[1].trim()
                                    if (actName.isNotEmpty() && zh.isNotEmpty()) zhDescMap[actName] = zh
                                }
                            }
                            if (zhDescMap.isNotEmpty()) {
                                ActivityLabelCache.putAll(this@AppDetailActivity, pkg, HashMap(zhDescMap))
                            }
                            primaryAdapter.notifyDataSetChanged()
                            placeholderAdapter.notifyDataSetChanged()
                            Snackbar.make(binding.root, "AI 已补全 ${zhDescMap.size} 个页面的中文说明", Snackbar.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Snackbar.make(binding.root, "补全失败：${e.message}", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        }

        // 清空两段选择
        val clearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(outlinedBtn("清空选择") {
                primaryAdapter.selected = null
                placeholderAdapter.selected = null
                primaryAdapter.notifyDataSetChanged()
                placeholderAdapter.notifyDataSetChanged()
            })
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((dp * 20).toInt(), (dp * 8).toInt(), (dp * 20).toInt(), 0)
            aiBtnRow?.let { addView(it) }
            addView(clearRow)
            addView(search)
            addView(sectionTitle("① 主页面（在左栏打开的页面，通常是 ★ 主界面）"))
            addView(primaryList)
            addView(sectionTitle("② 右栏默认显示的页面（占位页面）"))
            addView(placeholderList)
            addView(TextView(this@AppDetailActivity).apply {
                text = "效果：主页面在左栏打开时，右栏自动显示占位页面。最终填写格式为「主页面:占位页面」。"
                textSize = 12f
                setTextColor(gray)
                setPadding(0, (dp * 8).toInt(), 0, 0)
            })
        }

        // 用 create + 自定义确定按钮，校验未通过时不关闭
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择占位页面配对")
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val p = primaryAdapter.selected
                    val q = placeholderAdapter.selected
                    when {
                        p == null || q == null ->
                            Snackbar.make(binding.root, "请分别选择主页面和占位页面", Snackbar.LENGTH_SHORT).show()
                        p == q ->
                            Snackbar.make(binding.root, "主页面和占位页面不能是同一个页面", Snackbar.LENGTH_SHORT).show()
                        else -> {
                            // 保留已有的其他配对
                            val others = existingPairs.drop(1).joinToString(",") { "${it.first}:${it.second}" }
                            onDone(if (others.isEmpty()) "$p:$q" else "$p:$q,$others")
                            dialog.dismiss()
                        }
                    }
                }
        }
        dialog.show()
    }

    /** 根据入口上下文生成 AI 分析 prompt */
    private fun buildContextAwarePrompt(
        pkg: String,
        items: List<Pair<String, String>>,
        fieldContext: String
    ): String {
        val contextHint = when (fieldContext) {
            "activityRule" -> "应该参与分屏的页面"
            "splitPairRule" -> "应该参与左右栏配对的页面"
            "placeholder" -> "适合作为右栏默认显示的页面"
            "relaunchRule", "relunchRule" -> "需要重启才能正常显示的页面"
            "forcePortraitActivity" -> "应该始终竖着显示的页面"
            "sizeCompatRule" -> "需要尺寸兼容处理的页面"
            "autoUiActivityRule" -> "需要界面自动适配的页面"
            "flags" -> "有特殊标记需求的页面"
            else -> "适合该功能的页面"
        }

        val tagHint = when (fieldContext) {
            "activityRule", "splitPairRule" -> "主页面、详情页、列表页、播放页"
            "placeholder" -> "主页面、启动页"
            "forcePortraitActivity" -> "登录页、设置页、弹窗、表单页"
            "relaunchRule", "relunchRule" -> "设置页、特殊功能页"
            else -> ""
        }

        return buildString {
            appendLine("请分析 $pkg 的以下 Activity 页面列表，为每个页面标注功能用途和中文说明。")
            appendLine()
            appendLine("页面列表：")
            items.forEach { item ->
                appendLine("  ${item.first} (${item.second})")
            }
            appendLine()
            appendLine("请按以下格式返回（每行一个，格式：Activity类名|功能标签|中文说明）：")
            if (tagHint.isNotEmpty()) {
                appendLine("功能标签只能是：$tagHint、不参与、其他")
                appendLine("中文说明：用简短的中文描述该页面的功能（如：首页信息流、商品详情、登录注册、系统设置等）")
                appendLine("其中适合「$contextHint」的页面请标记为：适合")
            } else {
                appendLine("功能标签描述该页面的用途（如：主页、列表、详情、登录、设置、播放等）")
                appendLine("中文说明：用简短的中文描述该页面的功能")
            }
            appendLine()
            appendLine("示例格式：")
            appendLine("com.example.MainActivity|适合|首页信息流")
            appendLine("com.example.LoginActivity|登录页|登录注册页面")
        }
    }

    /** 解析 AI 返回的建议结果（支持3段格式：类名|标签|中文说明） */
    private fun parseAiSuggestions(
        reply: String,
        aiSuggestions: HashMap<String, String>,
        aiSuggestedNames: HashSet<String>,
        checkedMap: HashMap<String, Boolean>,
        packageName: String = "",
        zhDescCache: HashMap<String, String> = HashMap()
    ) {
        val cachedLabels = mutableMapOf<String, String>()
        reply.lines().forEach { line ->
            val parts = line.split("|", "：", ":", limit = 3)
            if (parts.size >= 2) {
                val actName = parts[0].trim().removePrefix("★").trim()
                val tag = parts[1].trim()
                aiSuggestions[actName] = tag
                if (tag == "适合") {
                    aiSuggestedNames.add(actName)
                    checkedMap[actName] = true
                }
                // 解析中文说明（第三段）
                if (parts.size >= 3) {
                    val zhDesc = parts[2].trim()
                    if (zhDesc.isNotEmpty()) {
                        zhDescCache[actName] = zhDesc
                        cachedLabels[actName] = zhDesc
                    }
                }
            }
        }
        // 批量缓存到本地
        if (packageName.isNotEmpty() && cachedLabels.isNotEmpty()) {
            ActivityLabelCache.putAll(this, packageName, cachedLabels)
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
            dirty = true
            onChange(it)
        }
    }

    private fun save() {
        rule.mode = pendingMode  // 只在保存时才写入模式
        ConfigRepository.saveRule(rule)
        userSaved = true
        dirty = false
        UiKit.vibrate(this)
        Snackbar.make(binding.root, R.string.saved, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * 导出当前应用规则：写入模块外部存储（可用文件管理器/adb 取），
     * 同时弹窗展示 JSON，长按或点「复制」可复制内容。
     */
    private fun exportCurrentRule() {
        val dir = java.io.File(getExternalFilesDir(null), "export").apply { mkdirs() }
        val file = java.io.File(dir, "magicwindow_${pkg}_${System.currentTimeMillis()}.json")
        val json = McpServer.buildRulesJson(listOf(rule)).toString(2)
        runCatching { file.writeText(json) }.onFailure {
            Snackbar.make(binding.root, R.string.config_export_failed, Snackbar.LENGTH_SHORT).show()
            return
        }

        val dp = resources.displayMetrics.density
        val pathView = TextView(this).apply {
            text = getString(R.string.export_rule_done, file.absolutePath)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.field_en))
            setOnLongClickListener { copyText(file.absolutePath); true }
        }
        val jsonView = TextView(this).apply {
            text = json
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setOnLongClickListener { copyText(json); true }
        }
        val scroll = ScrollView(this).apply { addView(jsonView) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((dp * 20).toInt(), (dp * 8).toInt(), (dp * 20).toInt(), 0)
            addView(pathView)
            addView(scroll)
            scroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.45f).toInt()
            ).apply { topMargin = (dp * 12).toInt() }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_export_rule)
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

    /**
     * 导入单应用规则：粘贴 JSON（支持导出格式 rules 数组取同包名/第一条，
     * 或单个规则对象）。导入后只填充表单，仍需点「保存」才持久生效。
     */
    private fun showRuleImportDialog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipboard = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()

        val input = EditText(this).apply {
            hint = getString(R.string.import_rule_hint)
            gravity = android.view.Gravity.TOP
            minLines = 4
            maxLines = 12
            typeface = Typeface.MONOSPACE
            textSize = 12f
            // 剪贴板内容看起来是规则 JSON 时预填，省去粘贴
            if (clipboard.contains("\"packageName\"") || clipboard.contains("\"mode\"")) {
                setText(clipboard)
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_import_rule)
            .setView(container)
            .setPositiveButton(R.string.action_import_rule) { _, _ ->
                val imported = parseImportedRule(input.text?.toString().orEmpty())
                if (imported == null) {
                    Snackbar.make(binding.root, R.string.import_rule_failed, Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                rule = imported
                userSaved = true
                dirty = true
                binding.switchEnabled.isChecked = rule.enabled
                binding.modeGroup.check(buttonOf(rule.mode))
                updateModeDesc()
                buildForm()
                Snackbar.make(binding.root, R.string.import_rule_done, Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /** 解析导入 JSON → [AppRule]；兼容「导出格式」与「单个规则对象」两种输入 */
    private fun parseImportedRule(text: String): AppRule? {
        val obj = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null
        val ruleJson: org.json.JSONObject? = when {
            obj.optJSONArray("rules") != null -> {
                val arr = obj.getJSONArray("rules")
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("packageName") == pkg }
                    ?: arr.optJSONObject(0)
            }

            obj.has("packageName") || obj.has("mode") -> obj
            else -> null
        } ?: return null
        return runCatching {
            AppRule.fromJson(ruleJson!!.put("packageName", pkg))
        }.getOrNull()
    }
}
