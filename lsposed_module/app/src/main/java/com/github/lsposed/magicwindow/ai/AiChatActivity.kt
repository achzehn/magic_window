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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.databinding.ActivityAiChatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView

class AiChatActivity : AppCompatActivity() {

    companion object {
        /** 发送图片时最长边限制（px），超过则降采样 */
        private const val MAX_IMAGE_DIMEN = 1280

        /** 单个文本附件注入提示词的最大字符数，防止超出模型上下文 */
        private const val MAX_TEXT_CHARS = 60_000
    }

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
        val content: String? = null, // 文本文件内容
        /** 图片压缩后的 data URI（data:image/...;base64,...），后台处理完成才有值 */
        val imageDataUri: String? = null,
        /** 图片转存到应用私有目录后的本地路径（缩略图/预览/历史恢复都走本地文件） */
        val localPath: String? = null
    )

    data class MessageUi(
        val role: String,       // "user" | "assistant"
        var content: String,
        val toolCalls: String? = null,
        val attachments: List<Attachment> = emptyList(),
        /** 该条助手消息为请求失败提示，展示「重试」按钮 */
        var isError: Boolean = false,
        /** 上下文压缩摘要消息：UI 用 AI 样式展示，发给模型时作为 user 角色 */
        val isSummary: Boolean = false
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

    /** 当前生成任务，用于手动停止 */
    private var genJob: kotlinx.coroutines.Job? = null

    /** 流式输出中正在更新的助手消息下标 */
    private var streamPos = -1

    /** 缩略图缓存：key = uri#targetSize */
    private val thumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(48) {}

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

        // 生成中点击 = 停止；空闲时点击 = 发送
        binding.fabSend.setOnClickListener {
            if (isGenerating) stopGeneration() else sendMessage()
        }

        // 附件按钮
        binding.btnAttach.setOnClickListener { showAttachDialog() }

        // 模型切换按钮
        updateModelSwitchButton()
        binding.btnModelSwitch.setOnClickListener { showModelSwitchDialog() }

        // 清理未被任何对话引用的残留图片（发送后未保存历史就退出等情况）
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ChatHistory.pruneOrphanImages(this@AiChatActivity)
        }
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
            val atts = msg.attachments.map { meta ->
                Attachment(
                    uri = meta.localPath?.let { Uri.fromFile(java.io.File(it)) } ?: Uri.EMPTY,
                    name = meta.name,
                    type = meta.type,
                    localPath = meta.localPath
                )
            }
            // 压缩摘要消息：展示用 AI 样式，协议角色恢复为 user（与 buildAiMessages 对应）
            messages.add(
                MessageUi(
                    role = if (msg.isSummary) "assistant" else msg.role,
                    content = msg.content,
                    attachments = atts,
                    isSummary = msg.isSummary
                )
            )
        }
        adapter.notifyDataSetChanged()
        binding.recycler.scrollToPosition(messages.size - 1)
        // 后台重水化图片 base64，后续请求能把历史图片重新发给模型（与实时会话行为一致）
        lifecycleScope.launch {
            // IO 线程只读文件，回到主线程再改 messages，避免与列表绑定并发
            val hydrated = withContext(Dispatchers.IO) { rehydrateImages() }
            if (hydrated.isNotEmpty()) {
                hydrated.forEach { (idx, atts) ->
                    if (idx < messages.size) messages[idx] = messages[idx].copy(attachments = atts)
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    /** 为缺少 base64 的图片附件从本地文件读取 data URI；返回 消息下标→新附件列表（纯 IO，不改 UI 状态） */
    private fun rehydrateImages(): Map<Int, List<Attachment>> {
        val out = mutableMapOf<Int, List<Attachment>>()
        messages.forEachIndexed { idx, msg ->
            if (msg.attachments.none { it.type == "image" && it.imageDataUri == null }) return@forEachIndexed
            val newAtts = msg.attachments.map { att ->
                if (att.type == "image" && att.imageDataUri == null && att.localPath != null) {
                    val file = java.io.File(att.localPath)
                    if (file.exists()) {
                        val mime = when (file.extension.lowercase()) {
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            else -> "image/jpeg"
                        }
                        val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                        att.copy(imageDataUri = "data:$mime;base64,$b64")
                    } else att
                } else att
            }
            out[idx] = newAtts
        }
        return out
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
        val att = Attachment(uri, name, "image")
        pendingAttachments.add(att)
        updateAttachmentPreview()
        // 后台转存私有目录 + base64：content:// URI 的临时授权会过期、
        // 云图/跨空间相册直接解码不可靠，转存后缩略图/预览/上传全部走本地文件
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { importImage(uri) }
                    .onFailure { android.util.Log.w("AiChat", "图片导入失败: $uri", it) }
                    .getOrNull()
            }
            val idx = pendingAttachments.indexOf(att)
            if (idx >= 0) {
                if (imported != null) {
                    pendingAttachments[idx] = att.copy(
                        imageDataUri = imported.second,
                        localPath = imported.first
                    )
                } else {
                    pendingAttachments.removeAt(idx)
                    Toast.makeText(this@AiChatActivity, "图片「$name」读取失败", Toast.LENGTH_SHORT).show()
                }
                updateAttachmentPreview()
            }
        }
    }

    /**
     * 读取图片并降采样转存到 filesDir/chat_images/，最长边不超过 [MAX_IMAGE_DIMEN]。
     * @return localPath to data URI（直接编码本地文件字节，避免二次解码）
     */
    private fun importImage(uri: Uri): Pair<String, String> {
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_DIMEN ||
            bounds.outHeight / sample > MAX_IMAGE_DIMEN) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("图片解码失败")
        val format = when {
            mime.contains("png") -> Bitmap.CompressFormat.PNG
            mime.contains("webp") -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val bytes = java.io.ByteArrayOutputStream().also {
            bmp.compress(format, 85, it)
        }.toByteArray()
        bmp.recycle()
        val ext = when (format) {
            Bitmap.CompressFormat.PNG -> "png"
            Bitmap.CompressFormat.WEBP -> "webp"
            else -> "jpg"
        }
        val outMime = when (format) {
            Bitmap.CompressFormat.PNG -> "image/png"
            Bitmap.CompressFormat.WEBP -> "image/webp"
            else -> "image/jpeg"
        }
        val dir = java.io.File(filesDir, "chat_images").apply { mkdirs() }
        val file = java.io.File(dir, "${java.util.UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return file.absolutePath to "data:$outMime;base64,$b64"
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
                val dp = parent.resources.displayMetrics.density
                val root = android.widget.FrameLayout(parent.context)
                root.layoutParams = RecyclerView.LayoutParams(
                    (72 * dp).toInt(), (72 * dp).toInt()
                ).apply { marginEnd = (8 * dp).toInt() }
                return object : RecyclerView.ViewHolder(root) {}
            }
            override fun getItemViewType(position: Int) =
                if (pendingAttachments[position].type == "image") 0 else 1
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val root = holder.itemView as android.widget.FrameLayout
                root.removeAllViews()
                val att = pendingAttachments[position]
                val ctx = root.context
                val dp = ctx.resources.displayMetrics.density

                if (att.type == "image") {
                    // 图片缩略图卡片，处理中半透明
                    val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        radius = 14 * dp
                        cardElevation = 0f
                        strokeWidth = 0
                        alpha = if (att.imageDataUri == null) 0.5f else 1f
                    }
                    card.addView(ImageView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    })
                    decodeThumb(att, 128)?.let { (card.getChildAt(0) as ImageView).setImageBitmap(it) }
                    root.addView(card)
                } else {
                    // 文本文件 chip
                    root.addView(TextView(ctx).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        gravity = android.view.Gravity.CENTER
                        textSize = 11f
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        text = "📄 ${att.name}"
                        setBackgroundResource(R.drawable.bg_chip)
                        setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    })
                }

                // 移除按钮
                val close = ImageView(ctx).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        (22 * dp).toInt(), (22 * dp).toInt(),
                        android.view.Gravity.TOP or android.view.Gravity.END)
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                }
                close.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < pendingAttachments.size) {
                        pendingAttachments.removeAt(pos)
                        notifyDataSetChanged()
                        updateAttachmentPreview()
                    }
                }
                root.addView(close)
            }
        }
    }

    /** 解码附件缩略图（带缓存）：优先本地转存文件，失败返回 null */
    private fun decodeThumb(att: Attachment, target: Int = 256): android.graphics.Bitmap? {
        val local = att.localPath?.let { java.io.File(it) }?.takeIf { it.exists() }
        val key = "${local?.absolutePath ?: att.uri}#$target"
        thumbCache.get(key)?.let { return it }
        val bmp = runCatching {
            if (local != null) decodeSampled({ BitmapFactory.decodeFile(local.absolutePath, it) }, target)
            else decodeSampled({ opts ->
                contentResolver.openInputStream(att.uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }, target)
        }.onFailure {
            android.util.Log.w("AiChat", "缩略图解码失败: $key", it)
        }.getOrNull()
        if (bmp != null) thumbCache.put(key, bmp)
        return bmp
    }

    /** 通用的「边界探测 + 采样解码」流程，decode 闭包返回位图 */
    private fun decodeSampled(
        decode: (BitmapFactory.Options?) -> android.graphics.Bitmap?,
        target: Int
    ): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds) // bounds-only 模式位图必为 null，尺寸写在 options 里
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target &&
            bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 全屏预览图片 */
    private fun showImagePreview(att: Attachment) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeThumb(att, 1024) }
            if (bmp == null) {
                Toast.makeText(this@AiChatActivity, "图片加载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val iv = ImageView(this@AiChatActivity).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
            }
            MaterialAlertDialogBuilder(this@AiChatActivity)
                .setView(android.widget.FrameLayout(this@AiChatActivity).apply { addView(iv) })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        if ((text.isEmpty() && pendingAttachments.isEmpty()) || isGenerating) return

        if (ModelManager.getCurrent(this) == null) {
            Toast.makeText(this, R.string.ai_model_not_configured, Toast.LENGTH_SHORT).show()
            return
        }

        // 图片仍在后台压缩时禁止发送，避免发空图
        if (pendingAttachments.any { it.type == "image" && it.imageDataUri == null }) {
            Toast.makeText(this, "图片处理中，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }

        // 如果没有当前对话，创建新对话
        if (currentConversation == null) {
            currentConversation = ChatHistory.create(
                this,
                ChatHistory.generateTitle(text.ifEmpty { pendingAttachments.joinToString(",") { it.name } }),
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
        val sentText = text
        val sentAttachments = pendingAttachments.toList()
        pendingAttachments.clear()
        updateAttachmentPreview()

        launchRequest(sentText.ifEmpty { displayText }, sentAttachments)
    }

    /**
     * 发起一次流式 AI 请求；发送与失败重试共用。
     * token 逐段到达，实时写入气泡，同时解决弱网下长时间无响应被中断的问题。
     * 发送前检查上下文占用，达到阈值（模型高级设置 maxInputTokens 的 85%）先压缩前文。
     * @param userText 本次用户消息的纯文本，成功后写入历史（重试时从消息列表回推）
     * @param attachments 本次用户消息的附件（写入历史用，重试时从消息列表回推）
     */
    private fun launchRequest(userText: String, attachments: List<Attachment> = emptyList()) {
        isGenerating = true
        streamPos = -1
        binding.fabSend.isEnabled = true
        binding.fabSend.setImageResource(R.drawable.ic_stop)
        binding.fabSend.contentDescription = getString(R.string.ai_stop)
        addLoading()

        genJob = lifecycleScope.launch {
            try {
                var aiMessages = buildAiMessages()
                // 上下文达到阈值 → 先压缩（AI 摘要替换旧消息，旧图片随之释放）
                val cfg = ModelManager.getCurrent(this@AiChatActivity)
                if (cfg != null && ContextCompressor.shouldCompress(aiMessages, cfg)) {
                    updateToolStatus("上下文过长，正在压缩前文…")
                    val result = ContextCompressor.compress(aiMessages, cfg) { msgs ->
                        aiClient.complete(msgs)
                    }
                    updateToolStatus(null)
                    if (result.removedCount > 0) {
                        applyCompression(result)
                        aiMessages = result.messages
                        Toast.makeText(
                            this@AiChatActivity,
                            "上下文已达阈值，已压缩 ${result.removedCount} 条前文（约 ${result.beforeTokens / 1000}K → ${result.afterTokens / 1000}K tokens）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                val reply = aiClient.chatStream(
                    aiMessages,
                    onDelta = { piece ->
                        withContext(Dispatchers.Main) { appendStreamDelta(piece) }
                    },
                    onToolCall = { name, args ->
                        withContext(Dispatchers.Main) { updateToolStatus("正在执行: $name...") }
                        val result = AiToolExecutor.execute(this@AiChatActivity, name, args)
                        withContext(Dispatchers.Main) { updateToolStatus(null) }
                        result
                    }
                )

                if (streamPos >= 0) {
                    if (reply.isNotEmpty()) {
                        messages[streamPos].content = reply
                        adapter.notifyItemChanged(streamPos)
                    }
                } else if (reply.isNotEmpty()) {
                    removeLoading()
                    addMessage(MessageUi("assistant", reply))
                } else {
                    removeLoading()
                    addMessage(MessageUi("assistant", "（模型未返回内容）", isError = true))
                }

                // 保存到历史（附件只存元数据，图片 base64 从本地文件恢复）
                currentConversation?.let { conv ->
                    conv.messages.add(ChatHistory.Message(
                        "user", userText,
                        attachments = attachments.map {
                            ChatHistory.AttachmentMeta(it.type, it.name, it.localPath)
                        }
                    ))
                    conv.messages.add(ChatHistory.Message("assistant", reply))
                    ChatHistory.save(this@AiChatActivity, conv)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 用户手动停止：给已生成的部分内容打上标记
                if (streamPos >= 0 && messages.getOrNull(streamPos)?.role == "assistant") {
                    messages[streamPos].content =
                        messages[streamPos].content.trimEnd() + "\n\n（已停止）"
                    adapter.notifyItemChanged(streamPos)
                } else {
                    removeLoading()
                }
                throw ce
            } catch (e: Exception) {
                android.util.Log.e("AiChat", "对话请求失败", e)
                val reason = e.message?.takeIf { it.isNotBlank() }
                    ?: "${e.javaClass.simpleName}（无错误信息，详见日志标签 AiClient）"
                if (streamPos >= 0 && messages.getOrNull(streamPos)?.role == "assistant") {
                    messages[streamPos].content += "\n\n[请求失败：$reason]"
                    messages[streamPos].isError = true
                    adapter.notifyItemChanged(streamPos)
                } else {
                    removeLoading()
                    addMessage(
                        MessageUi(
                            "assistant",
                            "请求失败：$reason\n\n可点下方「重试」再试一次。",
                            isError = true
                        )
                    )
                }
            } finally {
                isGenerating = false
                genJob = null
                streamPos = -1
                binding.fabSend.setImageResource(R.drawable.ic_send)
                binding.fabSend.contentDescription = getString(R.string.ai_send)
                binding.fabSend.isEnabled = true
            }
        }
    }

    /** 追加一段流式文本到当前助手气泡 */
    private fun appendStreamDelta(piece: String) {
        if (streamPos == -1) {
            removeLoading()
            messages.add(MessageUi("assistant", piece))
            streamPos = messages.size - 1
            adapter.notifyItemInserted(streamPos)
        } else {
            messages[streamPos].content += piece
        }
        val holder = binding.recycler.findViewHolderForAdapterPosition(streamPos)
            as? MessageAdapter.MsgViewHolder
        holder?.tvAiMessage?.text = messages[streamPos].content
        binding.recycler.scrollToPosition(streamPos)
    }

    /** 手动停止生成：取消协程并断开底层连接 */
    private fun stopGeneration() {
        genJob?.cancel()
        aiClient.cancelActiveRequest()
    }

    /** 失败消息重试：移除错误提示后用已有的用户消息重新请求 */
    private fun retryLast(errorPosition: Int) {
        if (isGenerating) return
        if (errorPosition in messages.indices) {
            messages.removeAt(errorPosition)
            adapter.notifyItemRemoved(errorPosition)
        }
        val lastUser = messages.lastOrNull { it.role == "user" && !it.isSummary }
            ?: return
        launchRequest(lastUser.content, lastUser.attachments)
    }

    /**
     * 压缩生效到 UI 与历史：移除最早的被压缩消息，头部插入一条摘要消息。
     * 摘要持久化后，恢复历史对话时上下文仍保持压缩态。
     */
    private fun applyCompression(result: ContextCompressor.Result) {
        // 三处（本轮请求 / UI 展示 / 历史持久化）保持同一文本，保证后续每轮上下文一致
        val summaryText = "[前文摘要] " + (result.summary ?: "（摘要生成失败，最早的消息已直接丢弃）")
        val summaryUi = MessageUi(
            role = "assistant",
            content = summaryText,
            isSummary = true
        )
        val removeCount = result.removedCount.coerceAtMost(messages.size)
        repeat(removeCount) { messages.removeAt(0) }
        messages.add(0, summaryUi)
        adapter.notifyDataSetChanged()

        currentConversation?.let { conv ->
            repeat(removeCount.coerceAtMost(conv.messages.size)) { conv.messages.removeAt(0) }
            conv.messages.add(0, ChatHistory.Message(
                role = "user",
                content = summaryText,
                isSummary = true
            ))
            ChatHistory.save(this, conv)
        }
    }

    private fun buildAiMessages(): List<AiClient.ChatMessage> {
        val result = mutableListOf<AiClient.ChatMessage>()
        result.add(AiClient.ChatMessage("system", AiSystemPrompt.SYSTEM_PROMPT))
        messages.forEach { msg ->
            if (msg.toolCalls == "loading") return@forEach // 跳过「思考中」占位
            // 图片以多模态形式真正发送；未能编码成功的图片给出文字提示
            val hasFailedImage = msg.attachments.any { it.type == "image" && it.imageDataUri == null }
            val fullContent = buildString {
                append(msg.content)
                // 附加文件内容（截断超长文件，防止超出模型上下文导致接口报错）
                msg.attachments.filter { it.type == "text" && it.content != null }.forEach { att ->
                    val raw = att.content!!
                    val shown = if (raw.length > MAX_TEXT_CHARS) {
                        raw.take(MAX_TEXT_CHARS) + "\n…（文件过长，已截断，共 ${raw.length} 字符）"
                    } else raw
                    append("\n\n--- 附件：${att.name} ---\n")
                    append(shown)
                    append("\n--- 附件结束 ---")
                }
                if (hasFailedImage) append("\n\n[有图片读取失败，未能上传]")
            }
            val images = msg.attachments.mapNotNull { it.imageDataUri }
            // 压缩摘要消息在协议上作为 user 发送（UI 展示为 AI 样式）
            result.add(AiClient.ChatMessage(
                if (msg.isSummary) "user" else msg.role,
                fullContent,
                images = images
            ))
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

            vh.btnRetry.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) retryLast(pos)
            }

            when (getItemViewType(position)) {
                TYPE_USER -> {
                    vh.layoutUser.visibility = View.VISIBLE
                    vh.layoutAi.visibility = View.GONE
                    vh.layoutLoading.visibility = View.GONE

                    // 图片缩略图行（点击可预览）
                    val imgs = item.attachments.filter { it.type == "image" }
                    vh.layoutUserAttach.visibility = if (imgs.isEmpty()) View.GONE else View.VISIBLE
                    vh.layoutUserAttach.removeAllViews()
                    val dp = vh.itemView.resources.displayMetrics.density
                    imgs.forEach { att ->
                        val card = com.google.android.material.card.MaterialCardView(this@AiChatActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                (96 * dp).toInt(), (96 * dp).toInt()
                            ).apply {
                                marginStart = (6 * dp).toInt()
                                topMargin = (4 * dp).toInt()
                            }
                            radius = 12 * dp
                            cardElevation = 0f
                            strokeWidth = 0
                        }
                        card.addView(ImageView(this@AiChatActivity).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        })
                        decodeThumb(att)?.let { (card.getChildAt(0) as ImageView).setImageBitmap(it) }
                        card.setOnClickListener { showImagePreview(att) }
                        vh.layoutUserAttach.addView(card)
                    }

                    // 文本文件以文件名列出
                    val texts = item.attachments.filter { it.type == "text" }
                    vh.tvUserMessage.text = if (texts.isEmpty()) item.content
                    else item.content + "\n" + texts.joinToString("\n") { "📎 ${it.name}" }
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
                    vh.btnRetry.visibility = if (item.isError) View.VISIBLE else View.GONE
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
            val layoutUserAttach: LinearLayout = view.findViewById(R.id.layoutUserAttach)
            val layoutAi: LinearLayout = view.findViewById(R.id.layoutAi)
            val layoutLoading: LinearLayout = view.findViewById(R.id.layoutLoading)
            val tvUserMessage: TextView = view.findViewById(R.id.tvUserMessage)
            val tvAiMessage: TextView = view.findViewById(R.id.tvAiMessage)
            val tvToolCall: TextView = view.findViewById(R.id.tvToolCall)
            val btnRetry: com.google.android.material.button.MaterialButton =
                view.findViewById(R.id.btnRetry)
        }
    }
}
