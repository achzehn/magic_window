package com.github.lsposed.magicwindow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.lsposed.magicwindow.R
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 动态表单构建工具：把 45+ 个配置项按分组渲染，避免维护巨型静态 XML。 */
object UiKit {

    /**
     * 动态行在同一棵视图树里被复用了几十次同一个 id（til/et/chip/sw）。
     * 旋转重建 Activity 时框架会按 id 自动恢复保存的状态，导致同 id 控件互相串值
     * （表现为所有输入框都变成 "1"）。这里统一禁用自动状态保存，
     * 界面状态一律以内存里的 AppRule 为准、由 buildForm() 重建。
     */
    private fun View.disableStateSaving() {
        isSaveEnabled = false
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).disableStateSaving()
        }
    }

    /** 在容器中追加一张分组卡片，返回卡片内部的 body 容器 */
    fun section(parent: ViewGroup, title: String, desc: String? = null): LinearLayout {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_section, parent, false)
        card.disableStateSaving()
        card.findViewById<TextView>(R.id.tvSection).text = title
        card.findViewById<TextView>(R.id.tvSectionDesc).apply {
            if (desc.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = desc
            }
        }
        parent.addView(card)
        return card.findViewById(R.id.body)
    }

    /** 开关行 */
    fun switchRow(
        parent: ViewGroup,
        title: String,
        desc: String? = null,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val row = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_switch, parent, false)
        row.disableStateSaving()
        row.findViewById<TextView>(R.id.tvTitle).text = title
        row.findViewById<TextView>(R.id.tvDesc).apply {
            if (desc.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = desc
            }
        }
        val sw = row.findViewById<MaterialSwitch>(R.id.sw)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.setOnClickListener { sw.toggle() }
        parent.addView(row)
        return row
    }

    /** 追加一个可勾选标签容器，用来承载若干开关型配置 */
    fun chipBox(parent: ViewGroup): ChipGroup {
        val group = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_chips, parent, false) as ChipGroup
        group.disableStateSaving()
        parent.addView(group)
        return group
    }

    /**
     * 可勾选标签：点一下选中（等于开），再点一下取消（等于关）。
     * [en] 为原始英文属性名，以灰色小字附在中文后面，方便与系统规则文件对照。
     */
    fun chip(
        group: ChipGroup,
        title: String,
        en: String? = null,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): Chip {
        val chip = LayoutInflater.from(group.context)
            .inflate(R.layout.item_chip, group, false) as Chip
        chip.disableStateSaving()
        chip.text = withEnglish(chip, title, en)
        chip.isChecked = checked
        chip.setOnCheckedChangeListener { _, v -> onChange(v) }
        group.addView(chip)
        return chip
    }

    /** 分组内的补充说明小字 */
    fun note(parent: ViewGroup, text: String): TextView {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_note, parent, false) as TextView
        tv.isSaveEnabled = false
        tv.text = text
        parent.addView(tv)
        return tv
    }

    /**
     * 文本输入行：[en] 为原始英文属性名，与提示文字一起显示在输入框下方。
     * [onCapture] 非空时在输入框尾部显示「抓取页面」按钮（用于填 Activity 类名的字段）。
     * 长按输入框把当前值复制到剪贴板。
     */
    fun textRow(
        parent: ViewGroup,
        label: String,
        en: String? = null,
        value: String,
        hint: String? = null,
        onCapture: (() -> Unit)? = null,
        onChange: (String) -> Unit
    ): TextInputLayout {
        val til = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_text, parent, false) as TextInputLayout
        til.disableStateSaving()
        til.hint = label
        til.helperText = helper(en, hint)
        val et = til.findViewById<TextInputEditText>(R.id.et)
        et.setText(value)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
        })
        et.setOnLongClickListener { v ->
            val cm = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, et.text?.toString().orEmpty()))
            Toast.makeText(v.context, R.string.capture_copied, Toast.LENGTH_SHORT).show()
            true
        }
        if (onCapture != null) {
            til.endIconMode = TextInputLayout.END_ICON_CUSTOM
            til.setEndIconDrawable(R.drawable.ic_search)
            til.setEndIconContentDescription(R.string.capture_pages)
            til.setEndIconOnClickListener { onCapture() }
        }
        parent.addView(til)
        return til
    }

    /**
     * 下拉选择行：取值固定的配置项用它，避免手填出错。
     * [options] 为「实际写入值 to 界面显示文案」，空字符串代表「不设置」。
     */
    fun dropdownRow(
        parent: ViewGroup,
        label: String,
        en: String? = null,
        options: List<Pair<String, String>>,
        value: String,
        hint: String? = null,
        onChange: (String) -> Unit
    ): TextInputLayout {
        val til = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_dropdown, parent, false) as TextInputLayout
        til.disableStateSaving()
        til.hint = label
        til.helperText = helper(en, hint)
        val et = til.findViewById<MaterialAutoCompleteTextView>(R.id.et)
        et.setSimpleItems(options.map { it.second }.toTypedArray())
        val index = options.indexOfFirst { it.first == value }
        et.setText(options.getOrNull(index)?.second ?: value, false)
        et.setOnItemClickListener { _, _, position, _ ->
            onChange(options[position].first)
        }
        parent.addView(til)
        return til
    }

    private fun helper(en: String?, hint: String?): String? = when {
        en.isNullOrEmpty() -> hint
        hint.isNullOrEmpty() -> en
        else -> "$en · $hint"
    }

    private fun withEnglish(view: TextView, title: String, en: String?): CharSequence {
        if (en.isNullOrEmpty()) return title
        val sb = SpannableStringBuilder(title).append("  ").append(en)
        val start = title.length + 2
        val gray = view.context.getColor(R.color.field_en)
        sb.setSpan(RelativeSizeSpan(0.78f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(gray), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /**
     * 触发一次短震动反馈。
     * 用于滑块到达节点、开关切换、Chip 选择等交互。
     */
    fun vibrate(context: Context, durationMs: Long = 15) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    /**
     * 滑块行：支持无极拖动，在指定节点处触发震动反馈。
     * @param value 当前值
     * @param valueRange 取值范围
     * @param steps 震动节点列表（拖到这些值时触发震动）
     * @param onChange 值变化回调（连续触发）
     * @param label 标签
     * @param en 英文属性名
     * @param hint 提示文字
     */
    fun sliderRow(
        parent: ViewGroup,
        label: String,
        en: String? = null,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: List<Float>,
        hint: String? = null,
        onChange: (Float) -> Unit
    ): View {
        val layout = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_slider, parent, false)

        val titleTv = layout.findViewById<TextView>(R.id.tvTitle)
        titleTv.text = label

        val descTv = layout.findViewById<TextView>(R.id.tvDesc)
        descTv.text = helper(en, hint)
        if (descTv.text.isNullOrEmpty()) descTv.visibility = View.GONE

        val valueTv = layout.findViewById<TextView>(R.id.tvValue)
        valueTv.text = "%.2f".format(value)

        val slider = layout.findViewById<Slider>(R.id.slider)
        slider.valueFrom = valueRange.start
        slider.valueTo = valueRange.endInclusive
        slider.value = value
        slider.stepSize = 0.05f

        var lastStep: Float? = null
        slider.addOnChangeListener { _, sliderValue, fromUser ->
            if (!fromUser) return@addOnChangeListener
            valueTv.text = "%.2f".format(sliderValue)
            // 检查是否到达震动节点
            val matchedStep = steps.minByOrNull { Math.abs(it - sliderValue) }
            if (matchedStep != null && Math.abs(matchedStep - sliderValue) < 0.03f
                && matchedStep != lastStep) {
                lastStep = matchedStep
                vibrate(parent.context)
            }
            if (matchedStep == null || Math.abs(matchedStep - sliderValue) > 0.05f) {
                lastStep = null
            }
            onChange(sliderValue)
        }

        layout.disableStateSaving()
        parent.addView(layout)
        return layout
    }
}
