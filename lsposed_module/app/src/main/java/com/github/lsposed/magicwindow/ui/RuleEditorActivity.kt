package com.github.lsposed.magicwindow.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.common.model.RuleCodec
import org.json.JSONException

/**
 * 全规则文本编辑器
 * - 以 JSON 形式整体展示所有规则
 * - 支持粘贴/编辑、格式化、校验、保存
 * - 傻瓜化：自动缩进、即时校验、错误提示
 */
class RuleEditorActivity : AppCompatActivity() {

    private lateinit var etRules: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.rule_editor_title)
        }

        etRules = findViewById(R.id.etRules)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnFormat).setOnClickListener { formatRules() }
        findViewById<Button>(R.id.btnValidate).setOnClickListener { validateRules() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveRules() }

        ConfigRepository.init(this)
        loadRules()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadRules() {
        try {
            val json = ConfigRepository.allRulesJson()
            etRules.setText(json)
            tvStatus.text = getString(R.string.rule_editor_loaded)
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } catch (e: Exception) {
            tvStatus.text = "${getString(R.string.error)}: ${e.localizedMessage}"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun formatRules() {
        val input = etRules.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, getString(R.string.rule_editor_empty), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val formatted = RuleCodec.prettyJson(input)
            etRules.setText(formatted)
            tvStatus.text = getString(R.string.rule_editor_formatted)
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } catch (e: Exception) {
            tvStatus.text = "${getString(R.string.format_error)}: ${e.localizedMessage}"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun validateRules() {
        val input = etRules.text.toString().trim()
        if (input.isEmpty()) {
            tvStatus.text = getString(R.string.rule_editor_empty)
            tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            return
        }
        try {
            RuleCodec.decodeRules(input)
            tvStatus.text = getString(R.string.rule_editor_valid)
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } catch (e: JSONException) {
            tvStatus.text = "${getString(R.string.json_error)}: ${e.message}"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            return
        } catch (e: Exception) {
            tvStatus.text = "${getString(R.string.validate_error)}: ${e.localizedMessage}"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            return
        }

        // 额外检查：冲突检测
        try {
            val rules = RuleCodec.decodeRules(input)
            val conflicts = rules.values.flatMap { rule ->
                val result = com.github.lsposed.magicwindow.common.model.ConflictChecker.check(rule)
                if (result.issues.isNotEmpty()) {
                    result.issues.map { "${rule.packageName}: ${it.message}" }
                } else {
                    emptyList()
                }
            }
            if (conflicts.isEmpty()) {
                tvStatus.text = getString(R.string.rule_editor_valid_no_conflict)
                tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            } else {
                val msg = "${getString(R.string.conflicts_found)}: ${conflicts.size}\n${conflicts.take(3).joinToString("\n")}"
                tvStatus.text = msg
                tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun saveRules() {
        val input = etRules.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, getString(R.string.rule_editor_empty), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val rulesMap = RuleCodec.decodeRules(input)
            ConfigRepository.saveRules(rulesMap.values)
            tvStatus.text = getString(R.string.rule_editor_saved)
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
        } catch (e: JSONException) {
            tvStatus.text = "${getString(R.string.json_error)}: ${e.message}"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            Toast.makeText(this, getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
            return
        } catch (e: Exception) {
            tvStatus.text = "${getString(R.string.save_error)}: ${e.localizedMessage}"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            Toast.makeText(this, getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
            return
        }
    }
}
