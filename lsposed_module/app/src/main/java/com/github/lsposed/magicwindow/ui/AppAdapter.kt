package com.github.lsposed.magicwindow.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.lsposed.magicwindow.R
import com.github.lsposed.magicwindow.data.AppItem
import com.github.lsposed.magicwindow.data.ConfigRepository
import com.github.lsposed.magicwindow.databinding.ItemAppBinding

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

    fun submit(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSelectionMode(on: Boolean) {
        if (selectionMode == on) return
        selectionMode = on
        if (!on) selected.clear()
        notifyDataSetChanged()
    }

    fun toggleSelect(pkg: String) {
        if (!selected.remove(pkg)) selected.add(pkg)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selected.addAll(items.map { it.packageName })
        notifyDataSetChanged()
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
        b.icon.setImageDrawable(item.icon(ctx.packageManager))

        val badge = BuiltinUi.badge(ctx, item.packageName)
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
}
