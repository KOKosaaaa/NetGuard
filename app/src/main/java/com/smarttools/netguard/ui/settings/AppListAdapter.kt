package com.smarttools.netguard.ui.settings

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smarttools.netguard.databinding.ItemAppBinding

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    var isChecked: Boolean
)

class AppListAdapter(
    private val onToggle: (AppItem) -> Unit
) : ListAdapter<AppItem, AppListAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) = a.isChecked == b.isChecked
        }
    }

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.ivAppIcon.setImageDrawable(item.icon)
        holder.binding.tvAppName.text = item.label
        holder.binding.tvPackageName.text = item.packageName
        // Prevent checkbox from handling its own click — root handles everything
        holder.binding.cbSelected.isClickable = false
        holder.binding.cbSelected.isChecked = item.isChecked
        holder.binding.root.setOnClickListener {
            item.isChecked = !item.isChecked
            holder.binding.cbSelected.isChecked = item.isChecked
            onToggle(item)
        }
    }
}
