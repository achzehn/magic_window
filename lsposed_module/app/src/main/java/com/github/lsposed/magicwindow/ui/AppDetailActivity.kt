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
import com.github.lsposed.magicwindow.data.AiSuggestCache
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

        /** AI 批量分析页面时每批的最大页面数，避免单次请求/响应过大卡死 */
        private const val AI_BATCH_SIZE = 20
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

        binding.modeGroup.check(ModeUi.buttonOf(pendingMode))
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            dirty = true
            pendingMode = ModeUi.modeOf(checkedId)
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
            binding.modeGroup.check(ModeUi.buttonOf(pendingMode))
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
        binding.modeGroup.check(ModeUi.buttonOf(pendingMode))
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

        // 预加载本地缓存的推荐标签：打开对话框不再自动请求 AI，
        // 已分析过的页面直接显示 🤖 标签，点「勾选AI推荐」即时生效
        AiSuggestCache.getAllForPackage(this@AppDetailActivity, pkg, fieldContext).forEach { (act, tag) ->
            aiSuggestions[act] = tag
            if (tag == "适合") aiSuggestedNames.add(act)
        }

        // 过滤框
        val search = EditText(this).also { stylePickerSearch(it) }

        // 列表：使用自定义布局，优化长类名和中文说明显示
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
                } else {
                    tvDesc.visibility = View.GONE
                }

                // AI 建议标签（彩色胶囊）
                bindAiTag(tvAiTag, aiSuggestions[item.name])

                cb.isChecked = checkedMap[item.name] == true
                cb.buttonTintList =
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(this@AppDetailActivity, R.color.brand_primary)
                    )
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

            // AI 勾选推荐 / 补全中文说明 + 执行状态行
            var aiRecommendBtn: com.google.android.material.button.MaterialButton? = null
            var aiFillDescBtn: com.google.android.material.button.MaterialButton? = null
            var aiStatus: AiStatusRow? = null
            if (ModelManager.getCurrent(this@AppDetailActivity) != null) {
                aiStatus = createAiStatusRow()

                aiRecommendBtn = aiActionButton("🤖 AI 推荐", true) {
                        val status = aiStatus!!
                        val btns = arrayOf(aiRecommendBtn!!, aiFillDescBtn!!)
                        lifecycleScope.launch {
                            // 增量分析：已有标签（缓存或本次已分析）的页面跳过，只请求缺失项
                            val pending = items.filter {
                                !aiSuggestions.containsKey(it.name)
                            }.map { it.name }
                            if (pending.isEmpty()) {
                                updateAiStatus(
                                    status, false,
                                    "已从缓存加载 ${items.size} 个页面推荐", *btns
                                )
                            } else {
                                val nameLabel = items.associate { it.name to it.label }
                                updateAiStatus(
                                    status, true,
                                    "AI 分析中 0/${pending.size}（准备请求…）", *btns
                                )
                                val totalBatches =
                                    (pending.size + AI_BATCH_SIZE - 1) / AI_BATCH_SIZE
                                val okBatches = runAiBatches(
                                    names = pending,
                                    onStatus = { done, total, no, cnt ->
                                        updateAiStatus(
                                            status, true,
                                            "AI 分析中 $done/$total（第 $no/$cnt 批）", *btns
                                        )
                                    },
                                    buildMessages = { batch ->
                                        val prompt = buildContextAwarePrompt(
                                            pkg,
                                            batch.map { it to (nameLabel[it] ?: "") },
                                            fieldContext
                                        )
                                        listOf(
                                            AiClient.ChatMessage(
                                                "system",
                                                "你是 Android Activity 用途分析助手。只返回格式化的标签列表，不要解释。"
                                            ),
                                            AiClient.ChatMessage("user", prompt)
                                        )
                                    },
                                    onBatch = { reply ->
                                        parseAiSuggestions(
                                            reply, aiSuggestions, aiSuggestedNames,
                                            checkedMap, pkg, fieldContext, zhDescCache
                                        )
                                        listAdapter.notifyDataSetChanged()
                                    }
                                )
                                updateAiStatus(
                                    status, false,
                                    when {
                                        okBatches == 0 -> "AI 分析失败，请检查网络或模型配置后重试"
                                        okBatches < totalBatches ->
                                            "AI 分析完成（部分批次失败），推荐 ${aiSuggestedNames.size} 个页面"
                                        else -> "AI 分析完成，推荐 ${aiSuggestedNames.size} 个页面"
                                    },
                                    *btns
                                )
                            }
                            // 应用勾选
                            aiSuggestedNames.forEach { name -> checkedMap[name] = true }
                            listAdapter.notifyDataSetChanged()
                            Snackbar.make(
                                binding.root,
                                getString(R.string.ai_suggestion_applied, aiSuggestedNames.size),
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                }

                // 补全中文说明：页面多时自动分批，状态行实时显示进度
                aiFillDescBtn = aiActionButton("补全中文", false) {
                        val status = aiStatus!!
                        val btns = arrayOf(aiRecommendBtn!!, aiFillDescBtn!!)
                        lifecycleScope.launch {
                            // 增量补全：已有中文说明的页面直接用缓存，只请求缺失项
                            val pending = items.filter {
                                it.zhDesc.isEmpty() && zhDescCache[it.name].isNullOrEmpty()
                            }.map { it.name }
                            if (pending.isEmpty()) {
                                updateAiStatus(
                                    status, false,
                                    "已从缓存加载 ${items.size} 个页面说明", *btns
                                )
                                return@launch
                            }
                            updateAiStatus(
                                status, true, "AI 补全中 0/${pending.size}（准备请求…）", *btns
                            )
                            val totalBatches =
                                (pending.size + AI_BATCH_SIZE - 1) / AI_BATCH_SIZE
                            val okBatches = runAiBatches(
                                names = pending,
                                onStatus = { done, total, no, cnt ->
                                    updateAiStatus(
                                        status, true,
                                        "AI 补全中 $done/$total（第 $no/$cnt 批）", *btns
                                    )
                                },
                                buildMessages = { batch -> buildFillDescMessages(batch) },
                                onBatch = { reply ->
                                    parseFillDescReply(reply, zhDescCache)
                                    listAdapter.notifyDataSetChanged()
                                }
                            )
                            if (zhDescCache.isNotEmpty()) {
                                ActivityLabelCache.putAll(
                                    this@AppDetailActivity, pkg, HashMap(zhDescCache)
                                )
                            }
                            listAdapter.notifyDataSetChanged()
                            updateAiStatus(
                                status, false,
                                when {
                                    okBatches == 0 -> "补全失败，请检查网络或模型配置后重试"
                                    okBatches < totalBatches ->
                                        "已补全 ${zhDescCache.size} 个页面（部分批次失败）"
                                    else -> "中文说明已补全 ${zhDescCache.size} 个页面"
                                },
                                *btns
                            )
                        }
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
                addView(aiStatus.row)
            }

            // 批量操作按钮行
            val batchBtnRow = LinearLayout(this@AppDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (dp * 8).toInt() }
            }

            fun batchBtn(label: String, action: () -> Unit) =
                textActionButton(label, action).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
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

            addView(
                search,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (dp * 4).toInt() }
            )
            addView(list)

            list.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.45f).toInt()
            ).apply { topMargin = (dp * 8).toInt() }
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

        val search = EditText(this).also { stylePickerSearch(it) }

        // 单选列表适配器，复用 item_page_picker 布局，选择框用圆形单选样式
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
                } else {
                    tvDesc.visibility = View.GONE
                }
                v.findViewById<TextView>(R.id.tvAiTag).visibility = View.GONE
                v.findViewById<CheckBox>(R.id.cb).apply {
                    // 单选圆点，区别于多选场景的方形勾选框
                    setButtonDrawable(
                        ContextCompat.getDrawable(this@AppDetailActivity, R.drawable.selector_radio)
                    )
                    isChecked = selected == item.name
                }
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
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AppDetailActivity, R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (dp * 12).toInt(), 0, (dp * 4).toInt())
        }

        // AI 已配置时提供「推荐配对」与「补全中文说明」（分批，带状态显示）
        val aiConfigured = ModelManager.getCurrent(this) != null
        var aiBtnRow: LinearLayout? = null
        var aiStatus: AiStatusRow? = null
        if (aiConfigured) {
            aiStatus = createAiStatusRow()

            // 角色归类结果：类名 → 主页面/占位页面
            val roleMap = HashMap<String, String>()

            var btnRecommend: com.google.android.material.button.MaterialButton? = null
            var btnFillDesc: com.google.android.material.button.MaterialButton? = null

            btnRecommend = aiActionButton("🤖 AI 推荐配对", true) {
                    val status = aiStatus!!
                    val btns = arrayOf(btnRecommend!!, btnFillDesc!!)
                    lifecycleScope.launch {
                        updateAiStatus(
                            status, true,
                            "AI 分析配对中 0/${items.size}（准备请求…）", *btns
                        )
                        val totalBatches =
                            (items.size + AI_BATCH_SIZE - 1) / AI_BATCH_SIZE
                        val okBatches = runAiBatches(
                            names = items.map { it.name },
                            onStatus = { done, total, no, cnt ->
                                updateAiStatus(
                                    status, true,
                                    "AI 分析配对中 $done/$total（第 $no/$cnt 批）", *btns
                                )
                            },
                            buildMessages = { batch ->
                                val prompt = buildString {
                                    appendLine("请分析 $pkg 的以下 Android Activity 页面，逐个标注角色。")
                                    appendLine("角色取值：")
                                    appendLine("主页面：应用启动后首先进入的核心主页面/首页（通常是标 ★ 的启动页）")
                                    appendLine("占位页面：适合作为右栏默认显示的页面；优先内容简单的列表页、引导页、空白页；登录、验证码、网页验证类不要选")
                                    appendLine("其他：其余页面")
                                    appendLine("页面列表：")
                                    batch.forEach { appendLine("  $it") }
                                    appendLine()
                                    appendLine("每行格式：Activity类名|角色|中文说明（无说明可省略第三段）")
                                }
                                listOf(
                                    AiClient.ChatMessage(
                                        "system",
                                        "你是 Android 应用分析助手，只按规定格式逐行返回，不要解释。"
                                    ),
                                    AiClient.ChatMessage("user", prompt)
                                )
                            },
                            onBatch = { reply ->
                                reply.lines().forEach { line ->
                                    val parts = line.trim().split("|", "：", ":", limit = 3)
                                    if (parts.size >= 2) {
                                        val actName = parts[0].trim().removePrefix("★").trim()
                                        if (items.none { it.name == actName }) return@forEach
                                        val tag = parts[1].trim()
                                        when {
                                            tag.contains("占位") -> roleMap[actName] = "占位页面"
                                            tag.contains("主") -> roleMap[actName] = "主页面"
                                        }
                                        if (parts.size >= 3 && parts[2].trim().isNotEmpty()) {
                                            zhDescMap[actName] = parts[2].trim()
                                        }
                                    }
                                }
                            }
                        )
                        // 汇总选择：主页面优先启动页，其次第一个「主页面」；占位页不能与主页面相同
                        var pickedPrimary: String? =
                            items.firstOrNull { it.isMain && roleMap[it.name] == "主页面" }?.name
                                ?: items.firstOrNull { roleMap[it.name] == "主页面" }?.name
                                ?: launcher?.takeIf { name -> items.any { it.name == name } }
                        var pickedPlaceholder: String? =
                            items.firstOrNull { roleMap[it.name] == "占位页面" && it.name != pickedPrimary }?.name
                        if (pickedPrimary != null) primaryAdapter.selected = pickedPrimary
                        if (pickedPlaceholder != null) placeholderAdapter.selected = pickedPlaceholder
                        if (zhDescMap.isNotEmpty()) {
                            ActivityLabelCache.putAll(
                                this@AppDetailActivity, pkg, HashMap(zhDescMap)
                            )
                        }
                        primaryAdapter.notifyDataSetChanged()
                        placeholderAdapter.notifyDataSetChanged()
                        updateAiStatus(
                            status, false,
                            when {
                                okBatches == 0 -> "推荐失败，请检查网络或模型配置后重试"
                                pickedPrimary != null && pickedPlaceholder != null ->
                                    "已选好主页面和占位页面，请确认"
                                pickedPrimary != null || pickedPlaceholder != null ->
                                    "只识别出一个页面，请手动补选另一个"
                                okBatches < totalBatches -> "部分批次失败，结果可能不完整"
                                else -> "AI 未能识别合适的页面，请手动选择"
                            },
                            *btns
                        )
                    }
                }

            btnFillDesc = aiActionButton("补全中文", false) {
                val status = aiStatus!!
                val btns = arrayOf(btnRecommend!!, btnFillDesc!!)
                lifecycleScope.launch {
                    // 增量补全：已有中文说明的页面直接用缓存，只请求缺失项
                    val pending = items.filter {
                        zhDescMap[it.name].isNullOrEmpty()
                    }.map { it.name }
                    if (pending.isEmpty()) {
                        updateAiStatus(
                            status, false,
                            "已从缓存加载 ${items.size} 个页面说明", *btns
                        )
                        return@launch
                    }
                    updateAiStatus(
                        status, true,
                        "AI 补全中 0/${pending.size}（准备请求…）", *btns
                    )
                    val totalBatches =
                        (pending.size + AI_BATCH_SIZE - 1) / AI_BATCH_SIZE
                    val okBatches = runAiBatches(
                        names = pending,
                        onStatus = { done, total, no, cnt ->
                            updateAiStatus(
                                status, true,
                                "AI 补全中 $done/$total（第 $no/$cnt 批）", *btns
                            )
                        },
                        buildMessages = { batch -> buildFillDescMessages(batch) },
                        onBatch = { reply ->
                            parseFillDescReply(reply, zhDescMap)
                            primaryAdapter.notifyDataSetChanged()
                            placeholderAdapter.notifyDataSetChanged()
                        }
                    )
                    if (zhDescMap.isNotEmpty()) {
                        ActivityLabelCache.putAll(
                            this@AppDetailActivity, pkg, HashMap(zhDescMap)
                        )
                    }
                    primaryAdapter.notifyDataSetChanged()
                    placeholderAdapter.notifyDataSetChanged()
                    // 只统计本次新补全的数量（缓存项已在 pending 中剔除）
                    val fetched = zhDescMap.size - (items.size - pending.size)
                    updateAiStatus(
                        status, false,
                        when {
                            okBatches == 0 -> "补全失败，请检查网络或模型配置后重试"
                            okBatches < totalBatches ->
                                "已补全 ${fetched} 个页面（部分批次失败）"
                            else -> "中文说明已补全 ${fetched} 个页面"
                        },
                        *btns
                    )
                }
            }

            aiBtnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (dp * 8).toInt() }
                addView(btnRecommend)
                addView(btnFillDesc)
            }
        }

        // 清空两段选择：轻量文字按钮，右对齐，不再独占一个大色块
        val clearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(textActionButton("清空选择") {
                primaryAdapter.selected = null
                placeholderAdapter.selected = null
                primaryAdapter.notifyDataSetChanged()
                placeholderAdapter.notifyDataSetChanged()
            })
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((dp * 20).toInt(), (dp * 8).toInt(), (dp * 20).toInt(), (dp * 4).toInt())
            aiBtnRow?.let { addView(it) }
            aiStatus?.let { addView(it.row) }
            addView(clearRow)
            addView(
                search,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (dp * 4).toInt() }
            )
            addView(sectionTitle("① 主页面（左栏打开的页面，通常是 ★ 主界面）"))
            addView(primaryList)
            addView(sectionTitle("② 右栏默认显示的页面（占位页面）"))
            addView(placeholderList)
            addView(TextView(this@AppDetailActivity).apply {
                text = "效果：主页面在左栏打开时，右栏自动显示占位页面。最终填写格式为「主页面:占位页面」。"
                textSize = 12f
                setTextColor(gray)
                setPadding(0, (dp * 8).toInt(), 0, (dp * 4).toInt())
            })
        }
        // 外层包一层滚动，避免小屏上第二段列表被对话框按钮裁切
        val scroll = android.widget.ScrollView(this).apply {
            addView(
                container,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // 用 create + 自定义确定按钮，校验未通过时不关闭
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择占位页面配对")
            .setView(scroll)
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
        fieldContext: String = "",
        zhDescCache: HashMap<String, String> = HashMap()
    ) {
        val cachedLabels = mutableMapOf<String, String>()
        val cachedTags = mutableMapOf<String, String>()
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
                if (fieldContext.isNotEmpty()) cachedTags[actName] = tag
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
        // 推荐标签按「包名+场景」维度缓存，下次打开不再重复请求
        if (packageName.isNotEmpty() && fieldContext.isNotEmpty()) {
            AiSuggestCache.putAll(this, packageName, fieldContext, cachedTags)
        }
    }

    /** AI 状态行：转圈 + 文案，放在 AI 按钮行下方，实时展示批处理进度 */
    private class AiStatusRow(val row: LinearLayout, val progress: View, val text: TextView)

    // ==================== 抓取页面对话框统一样式 ====================

    /** 搜索框：圆角浅底 + 左侧搜索图标，去掉裸下划线 */
    private fun stylePickerSearch(et: EditText) {
        val dp = resources.displayMetrics.density
        et.background = ContextCompat.getDrawable(this, R.drawable.bg_picker_search)
        et.setPadding((dp * 14).toInt(), (dp * 10).toInt(), (dp * 12).toInt(), (dp * 10).toInt())
        et.textSize = 13f
        et.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        et.hint = getString(R.string.picker_filter_hint)
        et.setSingleLine()
        val iconSize = (dp * 17).toInt()
        ContextCompat.getDrawable(this, R.drawable.ic_search)?.mutate()?.apply {
            setBounds(0, 0, iconSize, iconSize)
            et.setCompoundDrawables(this, null, null, null)
        }
        et.compoundDrawablePadding = (dp * 8).toInt()
    }

    /** AI 操作按钮：tonal（推荐）或 outlined（补全），等高、单行、紧凑 */
    private fun aiActionButton(label: String, tonal: Boolean, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(
            androidx.appcompat.view.ContextThemeWrapper(
                this,
                if (tonal)
                    com.google.android.material.R.style.Widget_Material3_Button_TonalButton
                else
                    com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton
            )
        ).apply {
            text = label
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            insetTop = 0
            insetBottom = 0
            val dp = resources.displayMetrics.density
            minimumHeight = (dp * 40).toInt()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = (dp * 8).toInt() }
        }

    /** 轻量文字按钮：用于全选/反选/清空等辅助操作，不再用大色块 */
    private fun textActionButton(label: String, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(
            androidx.appcompat.view.ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_Material3_Button_TextButton
            )
        ).apply {
            text = label
            textSize = 12f
            maxLines = 1
            insetTop = 0
            insetBottom = 0
            val dp = resources.displayMetrics.density
            minimumHeight = (dp * 36).toInt()
            setOnClickListener { onClick() }
        }

    /** AI 标签胶囊：适合=绿，不适合=红，其余=灰 */
    private fun bindAiTag(tv: TextView, tag: String?) {
        if (tag.isNullOrBlank()) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        tv.text = tag
        val negative = tag.contains("不适合")
        val positive = tag.contains("适合") && !negative
        val bg = ContextCompat.getDrawable(
            this,
            if (negative) R.drawable.bg_tag_negative else R.drawable.bg_tag_neutral
        )?.mutate()
        if (positive) bg?.setTint(ContextCompat.getColor(this, R.color.ok_green_bg))
        tv.background = bg
        tv.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    negative -> R.color.conflict_error
                    positive -> R.color.ok_green
                    else -> R.color.field_en
                }
            )
        )
    }

    private fun createAiStatusRow(): AiStatusRow {
        val dp = resources.displayMetrics.density
        val size = (dp * 18).toInt()
        val progress = com.google.android.material.progressindicator.CircularProgressIndicator(this).apply {
            indicatorSize = size
            trackThickness = (dp * 2).toInt()
            isIndeterminate = true
            visibility = View.GONE
        }
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AppDetailActivity, R.color.field_en))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, (dp * 6).toInt(), 0, (dp * 2).toInt())
            addView(progress, LinearLayout.LayoutParams(size, size).apply { marginEnd = (dp * 8).toInt() })
            addView(tv)
        }
        return AiStatusRow(row, progress, tv)
    }

    /** 更新 AI 状态行：busy 显示转圈并禁用按钮；结束后可保留一行结果摘要 */
    private fun updateAiStatus(
        status: AiStatusRow,
        busy: Boolean,
        message: String?,
        vararg buttons: android.widget.TextView
    ) {
        status.row.visibility = if (message.isNullOrEmpty() && !busy) View.GONE else View.VISIBLE
        status.progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text.text = message.orEmpty()
        buttons.forEach { it.isEnabled = !busy }
    }

    /**
     * 大批量页面分批送 AI：每批 [AI_BATCH_SIZE] 个，串行调用轻量 complete()，
     * 逐批回调进度与解析结果；单批失败跳过，不影响其余批次。
     * @return 成功批次数（调用方可与总批次数比较判断是否全部失败）
     */
    private suspend fun runAiBatches(
        names: List<String>,
        onStatus: suspend (done: Int, total: Int, batchNo: Int, batchCount: Int) -> Unit,
        buildMessages: (batch: List<String>) -> List<AiClient.ChatMessage>,
        onBatch: (reply: String) -> Unit
    ): Int {
        val client = AiClient(this)
        val batches = names.chunked(AI_BATCH_SIZE)
        var ok = 0
        batches.forEachIndexed { index, batch ->
            onStatus(index * AI_BATCH_SIZE, names.size, index + 1, batches.size)
            val reply = try {
                client.complete(buildMessages(batch))
            } catch (_: Exception) {
                null
            }
            if (reply != null) {
                ok++
                onBatch(reply)
            }
        }
        onStatus(names.size, names.size, batches.size, batches.size)
        return ok
    }

    /** 「补全中文说明」固定格式请求消息，两个选择器共用 */
    private fun buildFillDescMessages(batch: List<String>): List<AiClient.ChatMessage> = listOf(
        AiClient.ChatMessage(
            "system",
            "你是 Android 应用分析助手，只返回格式化的中文说明列表，不要解释。"
        ),
        AiClient.ChatMessage(
            "user",
            buildString {
                appendLine("请分析 $pkg 的以下 Android Activity 页面，为每个页面补充简短的中文功能说明。")
                appendLine("页面列表：")
                batch.forEach { appendLine("  $it") }
                appendLine("每行格式：Activity类名|中文说明")
            }
        )
    )

    /** 解析「类名|中文说明」回复到 [out] */
    private fun parseFillDescReply(reply: String, out: HashMap<String, String>) {
        reply.lines().forEach { line ->
            val parts = line.trim().split("|", "：", ":", limit = 2)
            if (parts.size >= 2) {
                val name = parts[0].trim().removePrefix("★").trim()
                val desc = parts[1].trim()
                if (name.isNotEmpty() && desc.isNotEmpty()) out[name] = desc
            }
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
                binding.modeGroup.check(ModeUi.buttonOf(rule.mode))
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
