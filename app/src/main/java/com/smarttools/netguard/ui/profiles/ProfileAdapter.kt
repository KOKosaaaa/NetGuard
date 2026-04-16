package com.smarttools.netguard.ui.profiles

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.ItemProfileBinding
import com.smarttools.netguard.model.ServerProfile
import java.util.Collections

class ProfileAdapter(
    private val onItemClick: (ServerProfile) -> Unit,
    private val onItemLongClick: (ServerProfile) -> Unit,
    private val onPingClick: (ServerProfile) -> Unit,
    private val onFavoriteClick: (ServerProfile) -> Unit
) : ListAdapter<ServerProfile, ProfileAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ServerProfile>() {
            override fun areItemsTheSame(a: ServerProfile, b: ServerProfile) = a.id == b.id
            override fun areContentsTheSame(a: ServerProfile, b: ServerProfile) = a == b
        }
    }

    private val mutableList = mutableListOf<ServerProfile>()
    private val listLock = Any()

    override fun submitList(list: List<ServerProfile>?) {
        synchronized(listLock) {
            mutableList.clear()
            mutableList.addAll(list ?: emptyList())
            super.submitList(mutableList.toList())
        }
    }

    fun moveItem(from: Int, to: Int) {
        synchronized(listLock) {
            if (from < 0 || to < 0 || from >= mutableList.size || to >= mutableList.size) return
            Collections.swap(mutableList, from, to)
            notifyItemMoved(from, to)
        }
    }

    fun getReorderedList(): List<ServerProfile> = synchronized(listLock) { mutableList.toList() }

    inner class ViewHolder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        b.tvName.text = profile.name.ifEmpty { "${profile.address}:${profile.port}" }
        b.tvAddress.text = "${profile.address}:${profile.port}"
        b.tvProtocol.text = profile.protocol.value.uppercase()

        // Ping
        when {
            profile.lastPingMs < 0 -> {
                b.tvPing.text = "-"
                b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            profile.lastPingMs < 200 -> {
                b.tvPing.text = "${profile.lastPingMs}ms"
                b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.ping_good))
            }
            profile.lastPingMs < 500 -> {
                b.tvPing.text = "${profile.lastPingMs}ms"
                b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.ping_medium))
            }
            else -> {
                b.tvPing.text = "${profile.lastPingMs}ms"
                b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.ping_bad))
            }
        }

        // Selection indicator
        if (profile.isSelected) {
            b.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_selected))
        } else {
            b.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_normal))
        }

        // Favorite star
        b.ivFavorite.setImageResource(
            if (profile.isFavorite) R.drawable.ic_star_filled
            else R.drawable.ic_star_outline
        )
        b.ivFavorite.setOnClickListener { onFavoriteClick(profile) }

        b.root.setOnClickListener { onItemClick(profile) }
        b.root.setOnLongClickListener {
            onItemLongClick(profile)
            true
        }
        b.tvPing.setOnClickListener { onPingClick(profile) }
    }
}
