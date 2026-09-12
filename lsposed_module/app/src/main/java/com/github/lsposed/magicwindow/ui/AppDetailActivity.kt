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
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.common.model.AppRule
import com.github.lsposed.magicwindow.common.model.WindowMode
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource
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

    /** 系统明确禁用的应用默认锁定表单；用户打开「强制修改」后才可编辑 */
    private var forceEdit = false

    /** 字段 key → 控件，用于互斥选项（如显示比例三选一）联动 */
    private val fieldViews = linkedMapOf<String, View>()

    /** 页面类名字段的填充方式：LIST 逗号并列；PAIR 写成「页面:*」配对 */
    private enum class Fill { LIST, PAIR }

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

        // 系统内置规则提示（若规则表还在后台加载，加载完成后 onSystemRulesReady() 再刷新一次）
        showBuiltinCard()
        if (!SystemRuleSource.loaded) {
            SystemRuleSource.whenLoadedOnMain { onSystemRulesReady() }
        }

        // 系统明确禁用的应用：默认锁定，开关强制修改后才可编辑
        binding.cardForceEdit.visibility =
            if (SystemRuleSource.isFixedDisabled(pkg)) View.VISIBLE else View.GONE
        binding.swForceEdit.setOnCheckedChangeListener { _, v ->
            forceEdit = v
            applyEditState()
        }

        binding.switchEnabled.isChecked = rule.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, v ->
            dirty = true
            rule.enabled = v
        }

        binding.modeGroup.check(buttonOf(rule.mode))
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            dirty = true
            rule.mode = modeOf(checkedId)
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
            binding.modeGroup.check(buttonOf(rule.mode))
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
        val locked = SystemRuleSource.isFixedDisabled(pkg) && !forceEdit
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
        when (rule.mode) {
            WindowMode.FULL_SCREEN -> {
                if (rule.fullRule.isEmpty()) rule.fullRule = "nra:cr:rcr:nr"
                rule.foSupportModes = "full,fo"
                rule.foDefaultSettings = "full"
            }

            WindowMode.FIXED_ORIENTATION -> {
                rule.foSupportModes = "full,fo"
                rule.foDefaultSettings = "fo"
            }

            WindowMode.EMBEDDING -> {
                rule.fullRule = ""
                rule.foSupportModes = "full,fo"
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
        if (isBuiltin) SystemRuleSource.applyDefaults(rule)
        applyModeDefaults()
        userSaved = false
        dirty = false
        binding.switchEnabled.isChecked = rule.enabled
        binding.modeGroup.check(buttonOf(rule.mode))
        buildForm()
        applyEditState()
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
            when (rule.mode) {
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
        if (SystemRuleSource.isFixedDisabled(pkg) && !forceEdit) {
            UiKit.note(box, getString(R.string.locked_hint))
        }

        if (simpleMode) {
            buildSimpleForm(box)
        } else {
            // 高级模式：按优先级只显示当前模式的专属参数，低优先级分区直接隐藏
            when (rule.mode) {
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
        when (rule.mode) {
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
                    "格式 主页面:占位页面，可先抓取再改"
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
            "格式 主页面:占位页面，可先抓取再改"
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
        txt(
            body, "splitRatio", "左栏宽度占比", "splitRatio", rule.splitRatio,
            "0~1 的小数，如 0.35"
        ) { rule.splitRatio = it }
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
            rule.forcePortraitWhenSwitch, "多个用英文逗号隔开"
        ) { rule.forcePortraitWhenSwitch = it }
        txt(
            body, "sizecompatRatio", "兼容模式比例", "sizecompatRatio", rule.sizecompatRatio,
            "留空不设置"
        ) { rule.sizecompatRatio = it }
        txt(
            body, "sizecompatRule", "兼容模式页面规则", "sizecompatRule", rule.sizecompatRule,
            "格式 页面:数值，多个逗号隔开"
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
            "格式 DefaultScenario:true:页面名"
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
                    showPagePicker(fill, currentTextOf(tilNow)) { picked ->
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
    private fun showPagePicker(fill: Fill, current: String, onDone: (String) -> Unit) {
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

        data class Item(val name: String, val label: String, val isMain: Boolean)

        val items = acts.mapNotNull { a ->
            a.name?.let { name ->
                Item(
                    name,
                    runCatching { a.loadLabel(pm).toString() }.getOrDefault(""),
                    name == launcher
                )
            }
        }.distinctBy { it.name }
            .sortedWith(compareByDescending<Item> { it.isMain }.thenBy { it.name })

        val currentItems = current.split(',', ';')
            .map { it.trim().let { s -> if (fill == Fill.PAIR) s.substringBefore(':') else s } }
            .filter { it.isNotEmpty() }
            .toSet()
        val checkedMap = HashMap<String, Boolean>()
        items.forEach { if (it.name in currentItems) checkedMap[it.name] = true }

        // 过滤框
        val search = EditText(this).apply {
            hint = getString(R.string.picker_filter_hint)
            setSingleLine()
        }

        // 列表：第一行类名（主界面加 ★ 标记），第二行小字为页面功能名
        val gray = ContextCompat.getColor(this, R.color.field_en)
        val listAdapter = object : BaseAdapter() {
            var shown: List<Item> = items

            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Item = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView ?: LayoutInflater.from(this@AppDetailActivity)
                    .inflate(android.R.layout.simple_list_item_checked, parent, false)
                        ) as CheckedTextView
                val item = shown[position]
                val ssb = SpannableStringBuilder(item.name)
                if (item.isMain) {
                    ssb.insert(0, "★ ")
                    val markStart = ssb.length
                    ssb.append("\n").append(getString(R.string.picker_main_mark))
                    ssb.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(this@AppDetailActivity, R.color.ok_green)),
                        markStart + 1, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (item.label.isNotEmpty() && item.label != item.name && item.label != pkg) {
                    val start = ssb.length
                    ssb.append("\n").append(item.label)
                    ssb.setSpan(RelativeSizeSpan(0.75f), start + 1, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ForegroundColorSpan(gray), start + 1, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                v.text = ssb
                v.isChecked = checkedMap[item.name] == true
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
            addView(search)
            addView(list)
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
        ConfigRepository.saveRule(rule)
        userSaved = true
        dirty = false
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
