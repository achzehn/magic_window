package com.github.lsposed.magicwindow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.lsposed.magicwindow.ai.AiClient
import com.github.lsposed.magicwindow.ai.ModelManager
import kotlinx.coroutines.launch
import com.github.lsposed.magicwindow.ModuleStatus
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.ai.AiChatActivity
import com.github.lsposed.magicwindow.data.ConfigExporter
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.data.SystemRuleSource
import com.github.lsposed.magicwindow.databinding.ActivityMainBinding
import com.github.lsposed.magicwindow.mcp.McpServer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        /** 进程内缓存：root 一旦确认成功，后续进页面不再弹 su 授权 */
        @Volatile
        private var rootConfirmed = false
    }

    private lateinit var binding: ActivityMainBinding

    /** 桌面图标入口，停用它就等于隐藏图标 */
    private val launcherAlias by lazy {
        ComponentName(this, "com.github.lsposed.magicwindow.ui.LauncherAlias")
    }

    /** 导出配置文件选择器 */
    private val configExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            if (ConfigExporter.exportConfig(this, it)) {
                Snackbar.make(binding.root, R.string.config_export_success, Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, R.string.config_export_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** 导入配置文件选择器 */
    private val configImporter = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = ConfigExporter.importConfig(this, it)
            if (result.success && result.rules != null) {
                ConfigExporter.showImportConfirm(this, result) {
                    ConfigRepository.replaceAllRules(result.rules!!)
                    Snackbar.make(binding.root, R.string.config_import_success, Snackbar.LENGTH_SHORT).show()
                    renderStatus()
                }
            } else {
                val message = result.errorMessage ?: getString(R.string.config_import_failed)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 已确认过 root（本进程）或后台预热已通过 su 读取云控，直接进入
        if (rootConfirmed || SystemRuleSource.rootAvailable || hasRootIndicatorFile()) {
            rootConfirmed = true
            setupUi()
            return
        }

        // 首次使用需要等 Magisk / KernelSU / APatch 弹授权框，放后台线程检测避免卡住界面
        val waiting = MaterialAlertDialogBuilder(this)
            .setTitle("正在获取 Root 权限")
            .setMessage("如弹出 Magisk / KernelSU / APatch 的授权请求，请点击「允许」。")
            .setCancelable(false)
            .show()
        Thread({
            val ok = verifySuGrant()
            runOnUiThread {
                waiting.dismiss()
                if (ok) {
                    rootConfirmed = true
                    setupUi()
                } else {
                    showRootRequiredDialog()
                }
            }
        }, "root-check").start()
    }

    private fun setupUi() {
        binding.cardApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        binding.swMcp.setOnCheckedChangeListener { _, on ->
            if (on) {
                if (!McpServer.start(applicationContext)) {
                    binding.swMcp.isChecked = false
                    Snackbar.make(binding.root, R.string.mcp_start_failed, Snackbar.LENGTH_SHORT).show()
                }
            } else {
                McpServer.stop()
            }
            renderMcpState()
        }

        // 地址点击复制；端口/令牌点击进入编辑
        fun bindCopy(view: TextView, text: () -> String) {
            view.setOnClickListener { copyText(text()) }
            view.setOnLongClickListener { copyText(text()); true }
        }
        bindCopy(binding.tvMcpLoopback) { McpServer.loopbackUrl() }
        bindCopy(binding.tvMcpLan) { McpServer.localUrl() }
        binding.tvMcpPort.setOnClickListener { showMcpPortDialog() }
        binding.tvMcpToken.setOnClickListener { showMcpTokenDialog() }
        binding.tvMcpToken.setOnLongClickListener {
            McpServer.token(this).takeIf { it.isNotEmpty() }?.let { copyText(it) }
            true
        }

        binding.btnMcpJson.setOnClickListener { showMcpClientJsonDialog() }

        // AI 模型管理卡片
        renderAiModelStatus()
        binding.btnAiModels.setOnClickListener { showModelManagerDialog() }

        // AI 悬浮聊天按钮
        binding.fabAiChat.setOnClickListener {
            if (ModelManager.getCurrent(this) == null) {
                showModelConfigDialog()
            } else {
                startActivity(Intent(this, AiChatActivity::class.java))
            }
        }
    }

    /** 修改端口：保存后若服务在运行则自动重启换端口 */
    private fun showMcpPortDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.mcp_port_hint)
            setText(McpServer.port(this@MainActivity).toString())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_port_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mcp_action_save) { _, _ ->
                val value = input.text.toString().toIntOrNull() ?: 0
                if (value !in 1024..65535) {
                    Snackbar.make(binding.root, R.string.mcp_port_invalid, Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!McpServer.setPort(applicationContext, value)) {
                    Snackbar.make(binding.root, R.string.mcp_start_failed, Snackbar.LENGTH_SHORT).show()
                }
                renderMcpState()
            }
            .show()
    }

    /** 修改令牌：可随机生成，留空表示不校验 */
    private fun showMcpTokenDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.mcp_token_hint)
            setText(McpServer.token(this@MainActivity))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_token_title)
            .setView(input)
            .setNeutralButton(R.string.mcp_action_random, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mcp_action_save, null)
            .create()
            .apply {
                setOnShowListener {
                    // 随机生成只填入输入框，不直接保存
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        input.setText(McpServer.randomToken())
                    }
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        McpServer.setToken(applicationContext, input.text.toString())
                        renderMcpState()
                        dismiss()
                    }
                }
            }
            .show()
    }

    /**
     * 预览 MCP 客户端接入配置 JSON（自动带上当前局域网 IP），
     * 点击/长按 JSON 可复制，另带「复制」按钮。
     */
    private fun showMcpClientJsonDialog() {
        val json = McpServer.clientConfigJson(this)
        val jsonView = TextView(this).apply {
            text = json
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(false)
            setOnLongClickListener { copyText(json); true }
            setOnClickListener { copyText(json) }
        }
        val container = ScrollView(this).apply {
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = getString(R.string.mcp_json_hint)
                    setTextAppearance(R.style.Text_MagicWindow_Desc)
                })
                addView(jsonView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (resources.displayMetrics.density * 8).toInt()
                })
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_json_title)
            .setView(container)
            .setPositiveButton(R.string.action_copy) { _, _ -> copyText(json) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun renderAiModelStatus() {
        val model = ModelManager.getCurrent(this)
        if (model != null) {
            binding.tvAiModelName.text = model.name
            binding.tvAiModelName.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary))
            binding.tvAiModelStatus.text = "已配置"
            binding.tvAiModelStatus.setTextColor(ContextCompat.getColor(this, R.color.ok_green))
            binding.tvAiModelStatus.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.ok_green_bg)
        } else {
            // 区分「从未配置」和「模型被全部停用」
            val allDisabled = ModelManager.hasAny(this)
            binding.tvAiModelName.text = if (allDisabled) "模型已全部停用" else "未配置"
            binding.tvAiModelName.setTextColor(ContextCompat.getColor(this, R.color.field_en))
            binding.tvAiModelStatus.text = if (allDisabled) "已停用" else "未配置"
            binding.tvAiModelStatus.setTextColor(ContextCompat.getColor(this, R.color.conflict_error))
            binding.tvAiModelStatus.backgroundTintList = null
        }
    }

    /** 解析主题中的颜色属性（如 android.R.attr.textColorPrimary） */
    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun showModelConfigDialog(
        editModel: ModelManager.ModelConfig? = null,
        onSaved: (() -> Unit)? = null
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_ai_model, null)
        val etApiBase = view.findViewById<EditText>(R.id.etApiBase)
        val etModelId = view.findViewById<EditText>(R.id.etModelId)
        val etDisplayName = view.findViewById<EditText>(R.id.etDisplayName)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val etMaxInputTokens = view.findViewById<EditText>(R.id.etMaxInputTokens)
        val etMaxOutputTokens = view.findViewById<EditText>(R.id.etMaxOutputTokens)
        val etToolRounds = view.findViewById<EditText>(R.id.etToolRounds)
        val etTemperature = view.findViewById<EditText>(R.id.etTemperature)
        val etTopP = view.findViewById<EditText>(R.id.etTopP)
        val etTopK = view.findViewById<EditText>(R.id.etTopK)
        val btnTest = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestConnection)
        val tvTestResult = view.findViewById<TextView>(R.id.tvTestResult)
        val btnAdvanced = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAdvanced)
        val layoutAdvanced = view.findViewById<LinearLayout>(R.id.layoutAdvanced)

        // 填入配置：仅编辑已有模型时回填；editModel 为 null 表示新增，留空表单
        val model = editModel
        if (model != null) {
            etApiBase.setText(model.apiBase)
            etModelId.setText(model.modelId)
            etDisplayName.setText(model.name)
            etApiKey.setText(model.apiKey)
            etMaxInputTokens.setText(model.maxInputTokens.toString())
            etMaxOutputTokens.setText(model.maxOutputTokens.toString())
            etToolRounds.setText(model.toolRounds.toString())
            etTemperature.setText(model.temperature.toString())
            etTopP.setText(model.topP.toString())
            etTopK.setText(model.topK.toString())
        }

        // 高级配置折叠
        btnAdvanced.setOnClickListener {
            if (layoutAdvanced.visibility == View.VISIBLE) {
                layoutAdvanced.visibility = View.GONE
                btnAdvanced.setIconResource(android.R.drawable.arrow_down_float)
            } else {
                layoutAdvanced.visibility = View.VISIBLE
                btnAdvanced.setIconResource(android.R.drawable.arrow_up_float)
            }
        }

        // 连通性测试门禁：记录测试通过时的连接三元组；任一字段改动即失效，保存前强制重测
        val client = AiClient(this)
        var testedTriple: Triple<String, String, String>? = null
        fun currentTriple() = Triple(
            etApiBase.text.toString().trim(),
            etModelId.text.toString().trim(),
            etApiKey.text.toString().trim()
        )
        val changedWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (testedTriple != null) {
                    testedTriple = null
                    if (tvTestResult.visibility == View.VISIBLE) {
                        tvTestResult.text = getString(R.string.ai_model_test_changed)
                        tvTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.field_en))
                    }
                }
            }
        }
        etApiBase.addTextChangedListener(changedWatcher)
        etModelId.addTextChangedListener(changedWatcher)
        etApiKey.addTextChangedListener(changedWatcher)

        btnTest.setOnClickListener {
            val triple = currentTriple()
            if (triple.first.isEmpty() || triple.second.isEmpty() || triple.third.isEmpty()) {
                tvTestResult.visibility = View.VISIBLE
                tvTestResult.text = getString(R.string.ai_model_test_empty)
                tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.conflict_error))
                return@setOnClickListener
            }
            btnTest.isEnabled = false
            btnTest.text = getString(R.string.ai_model_testing)
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = "测试中…"
            tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.field_en))
            lifecycleScope.launch {
                val result = client.testConnection(triple.first, triple.second, triple.third)
                btnTest.isEnabled = true
                btnTest.text = getString(R.string.ai_model_test)
                tvTestResult.text = result
                val ok = result.startsWith("成功")
                if (ok) testedTriple = triple
                tvTestResult.setTextColor(ContextCompat.getColor(
                    this@MainActivity,
                    if (ok) R.color.ok_green else R.color.conflict_error
                ))
            }
        }

        val isEdit = editModel != null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isEdit) R.string.ai_model_edit else R.string.ai_model_add)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (isEdit) R.string.ai_save else R.string.ai_model_add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val triple = currentTriple()
                // 必填校验
                if (triple.first.isEmpty() || triple.second.isEmpty() || triple.third.isEmpty()) {
                    tvTestResult.visibility = View.VISIBLE
                    tvTestResult.text = getString(R.string.ai_model_test_empty)
                    tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.conflict_error))
                    return@setOnClickListener
                }
                // 连通性门禁：新增必须测试通过；编辑时连接三元组改过也必须重测
                val tripleUnchanged = model != null &&
                    triple == Triple(model.apiBase, model.modelId, model.apiKey)
                if (!tripleUnchanged && testedTriple != triple) {
                    tvTestResult.visibility = View.VISIBLE
                    tvTestResult.text = getString(R.string.ai_model_test_required)
                    tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.conflict_error))
                    return@setOnClickListener
                }
                val newModel = ModelManager.ModelConfig(
                    id = model?.id ?: java.util.UUID.randomUUID().toString(),
                    name = etDisplayName.text.toString().trim().ifEmpty { etModelId.text.toString().trim() },
                    apiBase = triple.first,
                    modelId = triple.second,
                    apiKey = triple.third,
                    maxInputTokens = etMaxInputTokens.text.toString().toIntOrNull() ?: 131072,
                    maxOutputTokens = etMaxOutputTokens.text.toString().toIntOrNull() ?: 16384,
                    toolRounds = etToolRounds.text.toString().toIntOrNull() ?: 25,
                    temperature = etTemperature.text.toString().toFloatOrNull() ?: 0.4f,
                    topP = etTopP.text.toString().toFloatOrNull() ?: 0.95f,
                    topK = etTopK.text.toString().toIntOrNull() ?: 20,
                    enabled = model?.enabled ?: true
                )
                if (model != null) {
                    ModelManager.update(this, newModel)
                } else {
                    ModelManager.add(this, newModel)
                }
                renderAiModelStatus()
                Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showModelManagerDialog() {
        val models = ModelManager.getAll(this)
        if (models.isEmpty()) {
            showModelConfigDialog()
            return
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val iconTint = resolveThemeColor(android.R.attr.textColorSecondary)

        // 无边框水波纹背景（主题属性）
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, outValue, true
        )
        val borderlessBg = outValue.resourceId

        var managerDialog: AlertDialog? = null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }

        // 模型列表数据（删除/停用/增改后从 ModelManager 重新加载）
        val currentModels = ModelManager.getAll(this).toMutableList()

        val listView = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
        }

        /** 从持久层重新加载列表并刷新 */
        fun modelsReload() {
            currentModels.clear()
            currentModels.addAll(ModelManager.getAll(this@MainActivity))
            (listView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        }

        // 顶部「＋ 添加模型」按钮（配置对话框叠加在管理对话框之上）
        val addBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.ai_model_add)
            setOnClickListener {
                // 始终创建新模型
                showModelConfigDialog(editModel = null, onSaved = { modelsReload() })
            }
        }
        container.addView(addBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = currentModels.size
            override fun getItem(position: Int) = currentModels[position]
            override fun getItemId(position: Int) = position.toLong()


            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val model = currentModels[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(0, dp(6), 0, dp(6))
                }

                // 左侧信息列：模型名(+当前标记) / 模型ID
                val infoLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    // 点击已启用且非当前的模型 → 设为当前模型
                    val isCurrentModel = ModelManager.getCurrent(this@MainActivity)?.id == model.id
                    if (model.enabled && !isCurrentModel) {
                        val clickBg = android.util.TypedValue()
                        theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, clickBg, true
                        )
                        setBackgroundResource(clickBg.resourceId)
                        setOnClickListener {
                            ModelManager.setCurrent(this@MainActivity, model.id)
                            UiKit.vibrate(this@MainActivity)
                            renderAiModelStatus()
                            modelsReload()
                            Toast.makeText(
                                this@MainActivity,
                                "已切换到「${model.name.ifEmpty { model.modelId }}」",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                val nameRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val tvName = TextView(this@MainActivity).apply {
                    text = model.name.ifEmpty { model.modelId }
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (!model.enabled) {
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.field_en))
                        paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    }
                }
                nameRow.addView(tvName)

                // 当前模型标记
                val isCurrent = ModelManager.getCurrent(this@MainActivity)?.id == model.id
                if (isCurrent) {
                    nameRow.addView(TextView(this@MainActivity).apply {
                        text = "当前"
                        textSize = 10f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok_green))
                        setPadding(dp(6), 0, 0, 0)
                    })
                }
                infoLayout.addView(nameRow)

                infoLayout.addView(TextView(this@MainActivity).apply {
                    text = model.modelId
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.field_en))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), dp(8), 0)
                })
                row.addView(infoLayout)

                // 编辑（含高级设置）
                val btnEdit = ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_edit)
                    imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
                    setBackgroundResource(borderlessBg)
                    val size = dp(40)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    contentDescription = getString(R.string.ai_model_edit)
                    // 配置对话框叠加在管理对话框之上：取消即回到列表，保存后刷新
                    setOnClickListener {
                        showModelConfigDialog(model) { modelsReload() }
                    }
                }
                row.addView(btnEdit)

                // 删除
                val btnDelete = ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
                    setBackgroundResource(borderlessBg)
                    val size = dp(40)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    contentDescription = "删除"
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("删除模型")
                            .setMessage("确定要删除「${model.name}」吗？")
                            .setPositiveButton("删除") { _, _ ->
                                ModelManager.delete(this@MainActivity, model.id)
                                renderAiModelStatus()
                                if (ModelManager.getAll(this@MainActivity).isEmpty()) {
                                    managerDialog?.dismiss()
                                    showModelConfigDialog(
                                        onSaved = { showModelManagerDialog() }
                                    )
                                } else {
                                    modelsReload()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                row.addView(btnDelete)

                // 启用/停用开关
                val toggle = SwitchCompat(this@MainActivity).apply {
                    isChecked = model.enabled
                    setPadding(dp(12), 0, 0, 0)
                    setOnCheckedChangeListener { _, isChecked ->
                        ModelManager.update(this@MainActivity, model.copy(enabled = isChecked))
                        // 同步当前模型选择：启用时若无可用模型则顶上；
                        // 停用时 getCurrent 已自动回退，把回退结果持久化
                        val fallback = ModelManager.getCurrent(this@MainActivity)
                        ModelManager.setCurrent(this@MainActivity, fallback?.id)
                        renderAiModelStatus()
                        modelsReload()
                    }
                }
                row.addView(toggle)

                return row
            }
        }
        listView.adapter = adapter
        container.addView(listView)

        managerDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_manage_models)
            .setView(container)
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("magic-window", text))
        Snackbar.make(binding.root, R.string.capture_copied, Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_hide_icon).isChecked = isIconHidden()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_donate -> {
            showDonateDialog()
            true
        }
        R.id.action_hide_icon -> {
            if (item.isChecked) {
                setIconHidden(false)
                item.isChecked = false
                Snackbar.make(binding.root, R.string.show_icon_done, Snackbar.LENGTH_SHORT).show()
            } else {
                confirmHide(item)
            }
            true
        }
        R.id.action_export_config -> {
            configExporter.launch("magicwindow_config_${System.currentTimeMillis()}.json")
            true
        }
        R.id.action_import_config -> {
            configImporter.launch(
                arrayOf(
                    "application/json",
                    "text/plain"
                )
            )
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** 隐藏之后只能从管理器进来，所以先确认一次 */
    private fun confirmHide(item: MenuItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hide_icon_confirm_title)
            .setMessage(R.string.hide_icon_confirm_msg)
            .setNegativeButton(R.string.hide_icon_confirm_cancel, null)
            .setPositiveButton(R.string.hide_icon_confirm_ok) { _, _ ->
                setIconHidden(true)
                item.isChecked = true
                Snackbar.make(binding.root, R.string.hide_icon_done, Snackbar.LENGTH_LONG).show()
            }
            .show()
    }

    private fun isIconHidden(): Boolean =
        packageManager.getComponentEnabledSetting(launcherAlias) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun setIconHidden(hidden: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcherAlias,
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderMcpState()
        // 从 AI 对话页切换模型返回后，同步刷新「当前模型」显示
        renderAiModelStatus()
    }

    private fun renderMcpState() {
        binding.swMcp.isChecked = McpServer.isRunning()
        binding.tvMcpState.text =
            if (McpServer.isRunning()) getString(R.string.mcp_running)
            else getString(R.string.mcp_stopped)
        binding.tvMcpLoopback.text = McpServer.loopbackUrl()
        binding.tvMcpLan.text = McpServer.localUrl()
        binding.tvMcpPort.text = McpServer.port(this).toString()
        binding.tvMcpToken.text = McpServer.token(this).ifEmpty { getString(R.string.mcp_token_none) }
    }

    private fun renderStatus() {
        val active = ModuleStatus.isModuleActive()
        binding.tvStatus.text = getString(
            if (active) R.string.status_active else R.string.status_inactive
        )
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.ok_green else R.color.conflict_error
            )
        )
        binding.tvStatusDesc.text =
            getString(R.string.status_configured, ConfigRepository.configuredCount())
    }

    /** 快速检测：主流 Root 工具的特征文件 / 传统 su 路径是否存在（不触发授权弹窗） */
    private fun hasRootIndicatorFile(): Boolean {
        val rootIndicators = listOf(
            // Magisk
            "/data/adb/magisk",
            "/data/adb/magisk.db",
            // KernelSU
            "/data/adb/ksu",
            "/data/adb/ksud",
            "/data/adb/ksu/bin/ksud",
            // APatch
            "/data/adb/apatch",
            "/data/adb/apd",
            // 传统 su 路径
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su"
        )
        return rootIndicators.any { File(it).exists() }
    }

    /**
     * 实际执行 su 验证（需在后台线程调用），适配 Magisk / KernelSU / APatch。
     * 首次会弹授权框，因此第一次失败后等待 3 秒重试，最多 3 轮。
     */
    private fun verifySuGrant(): Boolean {
        val backoff = longArrayOf(0L, 3000L, 3000L)
        repeat(3) { attempt ->
            if (backoff[attempt] > 0) Thread.sleep(backoff[attempt])
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) {
                    val result = process.inputStream.bufferedReader().use { it.readText().trim() }
                    if (result.contains("uid=0")) return true
                }
                process.destroy()
            } catch (_: Exception) { }
        }

        // 兜底：通过 which su 判断 su 是否存在（某些方案的 su 不在传统路径）
        return try {
            val which = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val finished = which.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            finished && which.exitValue() == 0 &&
                which.inputStream.bufferedReader().use { it.readText() }.trim().contains("su")
        } catch (_: Exception) {
            false
        }
    }

    /** 显示 Root 权限必需对话框 */
    private fun showRootRequiredDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.root_required_title)
            .setMessage(R.string.root_required_message)
            .setPositiveButton(R.string.root_required_ok) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /** 显示打赏对话框 */
    private fun showDonateDialog() {
        val imageView = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_donate)
            val padding = (resources.displayMetrics.density * 16).toInt()
            setPadding(padding, padding, padding, padding)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.donate_title)
            .setMessage(R.string.donate_message)
            .setView(imageView)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }
}
