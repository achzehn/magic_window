package com.github.lsposed.magicwindow.ui

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.AppItem
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.databinding.ItemAppBinding
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AppAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onLongClick: (AppItem) -> Unit,
    private val onToggle: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppItem> = emptyList()

    /** 多选模式下已选中的包名 */
    val selected = linkedSetOf<String>()
    var selectionMode = false
        private set

    // 图标缓存。value 为 null 表示该包取图标失败，同样缓存以免每次 bind 重复重试
    private val iconCache = object : LinkedHashMap<String, Drawable?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable?>?): Boolean =
            size > 256
    }

    // 内置徽标文案缓存
    private val badgeCache = HashMap<String, String?>()

    // 正在后台加载的包名，避免同一个包被反复入队（来回滑动时会堆积大量重复任务）
    private val inFlight = HashSet<String>()

    // MIUI 等定制 ROM 的 getApplicationIcon 会做 AI 图标查找 + 蒙版合成 + 大图解码，
    // 单个图标实测 70~280ms。用 LIFO 队列让当前可见项优先加载，
    // 否则新滑到的项要排在早已滑过的旧项后面，图标迟迟不出来。
    private val iconQueue = object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val iconExecutor =
        ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, iconQueue)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

    fun submit(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSelectionMode(on: Boolean) {
        if (selectionMode == on) return
        selectionMode = on
        if (!on) selected.clear()
        notifyItemRangeChanged(0, items.size, "selection")
    }

    fun toggleSelect(pkg: String) {
        if (!selected.remove(pkg)) selected.add(pkg)
        // 只刷新可见项，避免全量 notifyDataSetChanged
        notifyItemRangeChanged(0, items.size, "selection")
    }

    fun selectAll() {
        selected.addAll(items.map { it.packageName })
        notifyItemRangeChanged(0, items.size, "selection")
    }

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val ctx = b.root.context

        b.tvLabel.text = item.label
        b.tvPkg.text = item.packageName

        // 图标：命中缓存直接用；未命中则异步加载，避免滑动时同步 IPC 阻塞主线程
        val pkg = item.packageName
        val iv = b.icon
        iv.tag = pkg
        if (iconCache.containsKey(pkg)) {
            val cached = iconCache[pkg]
            if (cached != null) iv.setImageDrawable(cached)
            else iv.setImageResource(android.R.drawable.sym_def_app_icon)
        } else {
            iv.setImageResource(android.R.drawable.sym_def_app_icon)
            // inFlight 的增删都在主线程，无需额外同步
            if (inFlight.add(pkg)) {
                val pm = ctx.packageManager
                iconExecutor.execute {
                    val loaded = item.icon(pm)
                    mainHandler.post {
                        inFlight.remove(pkg)
                        iconCache[pkg] = loaded
                        if (loaded != null && iv.tag == pkg) iv.setImageDrawable(loaded)
                    }
                }
            }
        }

        // 内置徽标文案：缓存
        if (!badgeCache.containsKey(item.packageName)) {
            badgeCache[item.packageName] = BuiltinUi.badge(ctx, item.packageName)
        }
        val badge = badgeCache[item.packageName]
        if (badge == null) {
            b.tvBuiltin.visibility = View.GONE
        } else {
            b.tvBuiltin.visibility = View.VISIBLE
            b.tvBuiltin.text = badge
        }

        val rule = ConfigRepository.rule(item.packageName)
        if (rule != null && rule.enabled) {
            b.tvMode.visibility = View.VISIBLE
            b.tvMode.text = ModeUi.label(ctx, rule.mode)
            b.tvMode.setBackgroundResource(R.drawable.bg_mode_chip)
            b.tvMode.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, ModeUi.colorRes(rule.mode))
            )
            b.tvMode.setTextColor(ContextCompat.getColor(ctx, R.color.mode_chip_text))
        } else {
            b.tvMode.visibility = View.GONE
        }

        b.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        b.check.isChecked = selected.contains(item.packageName)

        b.root.setOnClickListener {
            if (selectionMode) onToggle(item) else onClick(item)
        }
        b.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    // 选择模式/批量选中切换时只更新 checkbox，不重绘图标/文案
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = items[position]
        holder.binding.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.binding.check.isChecked = selected.contains(item.packageName)
    }
}
