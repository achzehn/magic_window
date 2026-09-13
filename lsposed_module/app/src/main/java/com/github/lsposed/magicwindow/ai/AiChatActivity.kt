package com.github.lsposed.magicwindow.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.databinding.ActivityAiChatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory
import android.net.Uri

class AiChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private val messages = mutableListOf<MessageUi>()
    private lateinit var adapter: MessageAdapter
    private val aiClient by lazy { AiClient(this) }
    private var isGenerating = false
    private var currentConversation: ChatHistory.Conversation? = null
    private val pendingAttachments = mutableListOf<Attachment>()

    data class Attachment(
        val uri: Uri,
        val name: String,
        val type: String, // "image" | "text"
        val content: String? = null // 文本文件内容
    )

    data class MessageUi(
        val role: String,       // "user" | "assistant"
        val content: String,
        val toolCalls: String? = null,
        val attachments: List<Attachment> = emptyList()
    )

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handleFilePicked(uri)
    }

    // 图片选择器
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) handleImagePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 模型选择下拉
        setupModelSelector()

        adapter = MessageAdapter(messages)
        binding.recycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recycler.adapter = adapter

        // 检查是否有传入的对话ID（继续历史对话）
        val convId = intent.getStringExtra("conversation_id")
        if (convId != null) {
            loadConversation(convId)
        } else if (messages.isEmpty()) {
            showWelcome()
        }

        binding.fabSend.setOnClickListener { sendMessage() }

        // 附件按钮
        binding.btnAttach.setOnClickListener { showAttachDialog() }

        // 模型切换按钮
        updateModelSwitchButton()
        binding.btnModelSwitch.setOnClickListener { showModelSwitchDialog() }

        // 欢迎信息（tvWelcome 和 chipGroup 已从布局中移除）
    }

    private fun setupModelSelector() {
        val models = ModelManager.getEnabled(this)
        if (models.size <= 1) return

        val names = models.map { it.name }.toTypedArray()
        val currentModel = ModelManager.getCurrent(this)
        val currentIdx = models.indexOfFirst { it.id == currentModel?.id }.coerceAtLeast(0)

        binding.toolbar.menu?.clear()
        val submenu = binding.toolbar.menu?.addSubMenu(0, 100, 0, getString(R.string.ai_manage_models))
        models.forEachIndexed { idx, model ->
            submenu?.add(0, 200 + idx, idx, model.name)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when {
                item.itemId == 100 -> { showModelManager(); true }
                item.itemId >= 200 -> {
                    val idx = item.itemId - 200
                    if (idx < models.size) {
                        ModelManager.setCurrent(this, models[idx].id)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun showModelManager() {
        val models = ModelManager.getEnabled(this)
        val items = models.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_manage_models)
            .setItems(items) { _, which ->
                ModelManager.setCurrent(this, models[which].id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadConversation(convId: String) {
        val conv = ChatHistory.get(this, convId) ?: return
        currentConversation = conv
        messages.clear()
        conv.messages.forEach { msg ->
            messages.add(MessageUi(msg.role, msg.content))
        }
        adapter.notifyDataSetChanged()
        binding.recycler.scrollToPosition(messages.size - 1)
    }

    private fun showWelcome() {
        // tvWelcome 和 chipGroup 已从布局中移除
    }

    private fun quickAction(text: String) {
        binding.etInput.setText(text)
        sendMessage()
    }

    private fun updateModelSwitchButton() {
        val model = ModelManager.getCurrent(this)
        binding.btnModelSwitch.text = model?.name ?: model?.modelId ?: "未配置"
    }

    private fun showModelSwitchDialog() {
        val models = ModelManager.getEnabled(this)
        if (models.isEmpty()) {
            Toast.makeText(this, R.string.ai_model_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        val currentId = ModelManager.getCurrent(this)?.id
        val items = models.map { model ->
            val check = if (model.id == currentId) " ✓" else ""
            "${model.name}（${model.modelId}）$check"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_model_switch)
            .setItems(items) { _, which ->
                ModelManager.setCurrent(this, models[which].id)
                updateModelSwitchButton()
                setupModelSelector()
            }
            .setNeutralButton(R.string.ai_model_add) { _, _ ->
                // 跳转到主页的模型配置
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAttachDialog() {
        val items = arrayOf("选择图片", "选择文件（JSON/XML/TXT等）")
        MaterialAlertDialogBuilder(this)
            .setTitle("添加附件")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> imagePickerLauncher.launch("image/*")
                    1 -> filePickerLauncher.launch(arrayOf(
                        "application/json", "application/xml", "text/plain",
                        "text/xml", "text/csv", "application/octet-stream"
                    ))
                }
            }
            .show()
    }

    private fun handleImagePicked(uri: Uri) {
        val name = uri.lastPathSegment ?: "image.jpg"
        pendingAttachments.add(Attachment(uri, name, "image"))
        updateAttachmentPreview()
    }

    private fun handleFilePicked(uri: Uri) {
        val name = uri.lastPathSegment ?: "file.txt"
        // 尝试读取文本内容
        val content = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        } catch (_: Exception) { null }

        pendingAttachments.add(Attachment(uri, name, "text", content))
        updateAttachmentPreview()
        Toast.makeText(this, "已添加：$name", Toast.LENGTH_SHORT).show()
    }

    private fun updateAttachmentPreview() {
        if (pendingAttachments.isEmpty()) {
            binding.rvAttachments.visibility = View.GONE
            return
        }
        binding.rvAttachments.visibility = View.VISIBLE
        binding.rvAttachments.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvAttachments.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pendingAttachments.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    setPadding(24, 12, 24, 12)
                    textSize = 12f
                    setCompoundDrawablesWithIntrinsicBounds(
                        if (viewType == 0) android.R.drawable.ic_menu_camera else android.R.drawable.ic_menu_save,
                        0, android.R.drawable.ic_menu_close_clear_cancel, 0
                    )
                    compoundDrawablePadding = 8
                    setBackgroundResource(R.drawable.bg_chip)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun getItemViewType(position: Int) =
                if (pendingAttachments[position].type == "image") 0 else 1
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val att = pendingAttachments[position]
                (holder.itemView as TextView).text = att.name
                holder.itemView.setOnClickListener {
                    pendingAttachments.removeAt(position)
                    notifyDataSetChanged()
                    updateAttachmentPreview()
                }
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if ((text.isEmpty() && pendingAttachments.isEmpty()) || isGenerating) return

        if (ModelManager.getCurrent(this) == null) {
            Toast.makeText(this, R.string.ai_model_not_configured, Toast.LENGTH_SHORT).show()
            return
        }

        // 如果没有当前对话，创建新对话
        if (currentConversation == null) {
            currentConversation = ChatHistory.create(
                this,
                ChatHistory.generateTitle(text),
                ModelManager.getCurrent(this)?.id ?: ""
            )
        }

        val displayText = if (text.isNotEmpty()) text else {
            // 纯附件消息，自动生成描述
            val attNames = pendingAttachments.joinToString(", ") { it.name }
            "已上传附件：$attNames"
        }

        addMessage(MessageUi("user", displayText, attachments = pendingAttachments.toList()))
        binding.etInput.setText("")
        val sentAttachments = pendingAttachments.toList()
        pendingAttachments.clear()
        updateAttachmentPreview()

        isGenerating = true
        binding.fabSend.isEnabled = false
        addLoading()

        lifecycleScope.launch {
            try {
                val aiMessages = buildAiMessages()
                val reply = aiClient.chat(aiMessages) { name, args ->
                    runOnUiThread { updateToolStatus("正在执行: $name...") }
                    val result = AiToolExecutor.execute(this@AiChatActivity, name, args)
                    runOnUiThread { updateToolStatus(null) }
                    result
                }

                removeLoading()
                addMessage(MessageUi("assistant", reply))

                // 保存到历史
                currentConversation?.let { conv ->
                    conv.messages.add(ChatHistory.Message("user", text))
                    conv.messages.add(ChatHistory.Message("assistant", reply))
                    ChatHistory.save(this@AiChatActivity, conv)
                }
            } catch (e: Exception) {
                removeLoading()
                addMessage(MessageUi("assistant", "抱歉，出错了：${e.message}\n\n请检查 API 配置是否正确。"))
            } finally {
                isGenerating = false
                binding.fabSend.isEnabled = true
            }
        }
    }

    private fun buildAiMessages(): List<AiClient.ChatMessage> {
        val result = mutableListOf<AiClient.ChatMessage>()
        result.add(AiClient.ChatMessage("system", AiSystemPrompt.SYSTEM_PROMPT))
        messages.forEach { msg ->
            val fullContent = buildString {
                append(msg.content)
                // 附加文件内容
                msg.attachments.filter { it.type == "text" && it.content != null }.forEach { att ->
                    append("\n\n--- 附件：${att.name} ---\n")
                    append(att.content)
                    append("\n--- 附件结束 ---")
                }
                // 附加图片说明
                msg.attachments.filter { it.type == "image" }.forEach { att ->
                    append("\n\n[已上传图片：${att.name}]")
                }
            }
            result.add(AiClient.ChatMessage(msg.role, fullContent))
        }
        return result
    }

    private fun addMessage(msg: MessageUi) {
        messages.add(msg)
        adapter.notifyItemInserted(messages.size - 1)
        binding.recycler.scrollToPosition(messages.size - 1)
    }

    private fun addLoading() {
        messages.add(MessageUi("assistant", "", toolCalls = "loading"))
        adapter.notifyItemInserted(messages.size - 1)
        binding.recycler.scrollToPosition(messages.size - 1)
    }

    private fun removeLoading() {
        val idx = messages.indexOfLast { it.toolCalls == "loading" }
        if (idx >= 0) {
            messages.removeAt(idx)
            adapter.notifyItemRemoved(idx)
        }
    }

    private fun updateToolStatus(status: String?) {
        if (status != null) {
            binding.tvToolStatus.visibility = View.VISIBLE
            binding.tvToolStatus.text = status
        } else {
            binding.tvToolStatus.visibility = View.GONE
        }
    }

    // ── 菜单 ──

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ai_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_history -> { showHistoryDialog(); true }
        R.id.action_new_chat -> { newChat(); true }
        R.id.action_clear -> { clearChat(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun newChat() {
        currentConversation = null
        messages.clear()
        adapter.notifyDataSetChanged()
        showWelcome()
    }

    private fun showHistoryDialog() {
        val conversations = ChatHistory.getAll(this)
        if (conversations.isEmpty()) {
            Toast.makeText(this, R.string.ai_no_history, Toast.LENGTH_SHORT).show()
            return
        }

        val items = conversations.map { conv ->
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(conv.lastModified))
            val model = ModelManager.getAll(this).find { it.id == conv.modelId }?.name ?: ""
            val modelTag = if (model.isNotEmpty()) " [$model]" else ""
            "$time | ${conv.title}$modelTag"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_history)
            .setItems(items) { _, which ->
                loadConversation(conversations[which].id)
            }
            .setNeutralButton(R.string.ai_clear_history) { _, _ ->
                ChatHistory.clearAll(this)
                Toast.makeText(this, R.string.ai_history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearChat() {
        messages.clear()
        adapter.notifyDataSetChanged()
        showWelcome()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
        }

        fun addField(label: String, value: String, hint: String): TextInputEditText {
            val til = TextInputEditText(this).apply {
                this.hint = hint
                setText(value)
                setTextIsSelectable(true)
            }
            layout.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(0, 24, 0, 4)
            })
            layout.addView(til)
            return til
        }

        val etApiBase = addField("API 地址", AiSettings.apiBase(this), "https://api.openai.com/v1")
        val etModelId = addField("模型 ID", AiSettings.modelId(this), "gpt-4o-mini")
        val etApiKey = addField("API 密钥", AiSettings.apiKey(this), "sk-...")
        val etDisplayName = addField("显示名称（可选）", AiSettings.displayName(this), "我的 AI 助手")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_settings)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_save) { _, _ ->
                AiSettings.save(this) {
                    putString("api_base", etApiBase.text.toString().trim())
                    putString("model_id", etModelId.text.toString().trim())
                    putString("api_key", etApiKey.text.toString().trim())
                    putString("display_name", etDisplayName.text.toString().trim())
                }
                Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ── 适配器 ──

    inner class MessageAdapter(private val items: List<MessageUi>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_USER = 0
        private val TYPE_AI = 1
        private val TYPE_LOADING = 2

        override fun getItemViewType(position: Int): Int = when {
            items[position].toolCalls == "loading" -> TYPE_LOADING
            items[position].role == "user" -> TYPE_USER
            else -> TYPE_AI
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ai_message, parent, false)
            return MsgViewHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val vh = holder as MsgViewHolder
            val item = items[position]

            when (getItemViewType(position)) {
                TYPE_USER -> {
                    vh.layoutUser.visibility = View.VISIBLE
                    vh.layoutAi.visibility = View.GONE
                    vh.layoutLoading.visibility = View.GONE
                    vh.tvUserMessage.text = item.content
                }
                TYPE_AI -> {
                    vh.layoutUser.visibility = View.GONE
                    vh.layoutAi.visibility = View.VISIBLE
                    vh.layoutLoading.visibility = View.GONE
                    vh.tvAiMessage.text = item.content
                    if (item.toolCalls != null) {
                        vh.tvToolCall.visibility = View.VISIBLE
                        vh.tvToolCall.text = item.toolCalls
                    } else {
                        vh.tvToolCall.visibility = View.GONE
                    }
                }
                TYPE_LOADING -> {
                    vh.layoutUser.visibility = View.GONE
                    vh.layoutAi.visibility = View.GONE
                    vh.layoutLoading.visibility = View.VISIBLE
                }
            }
        }

        inner class MsgViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val layoutUser: LinearLayout = view.findViewById(R.id.layoutUser)
            val layoutAi: LinearLayout = view.findViewById(R.id.layoutAi)
            val layoutLoading: LinearLayout = view.findViewById(R.id.layoutLoading)
            val tvUserMessage: TextView = view.findViewById(R.id.tvUserMessage)
            val tvAiMessage: TextView = view.findViewById(R.id.tvAiMessage)
            val tvToolCall: TextView = view.findViewById(R.id.tvToolCall)
        }
    }
}
