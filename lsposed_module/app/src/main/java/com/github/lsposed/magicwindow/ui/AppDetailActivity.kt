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

    /** true 表示该应用已有用户保存的规则，系统内置值只做徽标，不覆盖任何字段 */
    private var userSaved = false

    /** 用户进入页面后是否动过任何配置；动过之后异步读到的内置值就不再回填，避免覆盖输入 */
    private var dirty = false

    /** 简单模式只显示当前模式的常用项；高级模式显示完整参数表。记忆在全局配置里 */
    private var simpleMode = true

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

        // 系统内置规则提示（若规则表还在后台加载，加载完成后 onSystemRulesReady() 再刷新一次）
        showBuiltinCard()
        if (!SystemRuleSource.loaded) {
            SystemRuleSource.whenLoadedOnMain { onSystemRulesReady() }
        }

        binding.switchEnabled.isChecked = rule.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, v ->
            dirty = true
            rule.enabled = v
            validate()
        }

        binding.modeGroup.check(buttonOf(rule.mode))
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            dirty = true
            rule.mode = modeOf(checkedId)
            updateModeDesc()
            buildForm()
            validate()
        }
        updateModeDesc()

        // 简单 / 高级切换：记忆在全局配置，切换只重建表单，不动数据
        simpleMode = ConfigRepository.global().detailSimpleMode
        binding.detailModeGroup.check(
            if (simpleMode) R.id.btnDetailSimple else R.id.btnDetailAdvanced
        )
        binding.detailModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            simpleMode = checkedId == R.id.btnDetailSimple
            ConfigRepository.global().let { it.detailSimpleMode = simpleMode; ConfigRepository.saveGlobal(it) }
            buildForm()
            validate()
        }

        binding.btnFix.setOnClickListener {
            ConflictChecker.autoFix(rule)
            dirty = true
            binding.modeGroup.check(buttonOf(rule.mode))
            buildForm()
            validate()
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnReset.setOnClickListener { resetToBuiltin() }

        buildForm()
        validate()
    }

    /** 系统规则表后台加载完成：未动过的新规则用内置值重建表单，并刷新徽标与读取提示 */
    private fun onSystemRulesReady() {
        if (isFinishing) return
        if (!userSaved && !dirty) {
            SystemRuleSource.applyDefaults(rule)
            binding.modeGroup.check(buttonOf(rule.mode))
            buildForm()
            validate()
        }
        showBuiltinCard()
        SystemRuleSource.errorMessage?.let {
            Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * 恢复默认：有内置规则的应用恢复成系统内置值，没有内置规则的恢复成空默认。
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
        rule = AppRule(pkg).also { SystemRuleSource.applyDefaults(it) }
        userSaved = false
        dirty = false
        binding.switchEnabled.isChecked = rule.enabled
        binding.modeGroup.check(buttonOf(rule.mode))
        buildForm()
        validate()
        Snackbar.make(binding.root, R.string.reset_done, Snackbar.LENGTH_SHORT).show()
    }

    private fun showBuiltinCard() {
        val kinds = SystemRuleSource.kindsOf(pkg)
        if (kinds.isEmpty()) {
            binding.cardBuiltin.visibility = View.GONE
            return
        }
        binding.cardBuiltin.visibility = View.VISIBLE
        val names = kinds.joinToString(" · ") { kind ->
            when (kind) {
                SystemRuleSource.Kind.EMBEDDING -> getString(R.string.builtin_embedding)
                SystemRuleSource.Kind.FIXED ->
                    if (SystemRuleSource.isFixedDisabled(pkg)) getString(R.string.builtin_fixed_disabled)
                    else getString(R.string.builtin_fixed)
                SystemRuleSource.Kind.AUTO_UI -> getString(R.string.builtin_autoui)
            }
        }
        binding.tvBuiltin.text = getString(R.string.detail_builtin_hint, names)
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

        if (simpleMode) {
            buildSimpleForm(box)
            return
        }

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
                chip(chips, "foOverrideDisable", "无视系统禁用名单", "disable", rule.foOverrideDisable) {
                    rule.foOverrideDisable = it
                }
                chip(chips, "foIsShowDivider", "显示中间分割线", "isShowDivider", rule.foIsShowDivider) {
                    rule.foIsShowDivider = it
                }
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
        chip(chips, "foOverrideDisable", "无视系统禁用名单", "disable", rule.foOverrideDisable) {
            rule.foOverrideDisable = it
        }
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
            rule.foForcePortraitActivity, "多个用英文逗号隔开"
        ) { rule.foForcePortraitActivity = it }
        txt(
            body, "foFullForcePortraitActivity", "全屏档下仍竖着显示的页面",
            "fullForcePortraitActivity", rule.foFullForcePortraitActivity,
            "只在全屏拉伸档生效"
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
            "格式 包名/类名:1，多个逗号隔开"
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
            dirty = true
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
            binding.btnFix.visibility = if (error) View.VISIBLE else View.GONE
        }
    }

    private fun save() {
        // 手动系统开关已不在界面暴露：保存时按所选模式自动推导，交给系统按
        // 内置优先级（固定横屏 > 平行窗口 > 全屏）命中，避免开关与模式不一致。
        rule.swEmbedded = rule.mode == WindowMode.EMBEDDING
        rule.swFixedOrientation = rule.mode == WindowMode.FIXED_ORIENTATION
        rule.swFullScreen = rule.mode == WindowMode.FULL_SCREEN

        if (ConflictChecker.check(rule).hasError) {
            Snackbar.make(binding.root, R.string.save_blocked, Snackbar.LENGTH_SHORT).show()
            return
        }
        ConfigRepository.saveRule(rule)
        userSaved = true
        dirty = false
        Snackbar.make(binding.root, R.string.saved, Snackbar.LENGTH_SHORT).show()
    }
}
