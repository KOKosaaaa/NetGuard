package com.smarttools.netguard.ui.managed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.databinding.ItemManagedServerBinding

class ManagedServerAdapter(
    private val onClick: (ManagedServer) -> Unit,
) : ListAdapter<ManagedServer, ManagedServerAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemManagedServerBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemManagedServerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = getItem(position)
        with(holder.binding) {
            tvName.text = s.name
            tvHost.text = "${s.host}:${s.port}"
            tvTelemetry.text = renderTelemetry(s)
            root.setOnClickListener { onClick(s) }
        }
    }

    /**
     * Compact one-line telemetry summary. Hides the row's mental load
     * — three numbers tells the user enough to decide whether to click
     * in and see more.
     */
    private fun renderTelemetry(s: ManagedServer): String {
        if (s.lastSeenAt == 0L) return "never seen"
        val ramPct = if (s.lastMemTotalMb > 0) {
            (s.lastMemUsedMb * 100) / s.lastMemTotalMb
        } else 0
        val ago = ((System.currentTimeMillis() - s.lastSeenAt) / 1000).coerceAtLeast(0)
        val agoStr = when {
            ago < 60 -> "${ago}s"
            ago < 3600 -> "${ago / 60}m"
            else -> "${ago / 3600}h"
        }
        return "load %.2f · ram %d%% · ↑%s".format(s.lastLoadAvg1, ramPct, agoStr)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ManagedServer>() {
            override fun areItemsTheSame(a: ManagedServer, b: ManagedServer) = a.id == b.id
            override fun areContentsTheSame(a: ManagedServer, b: ManagedServer) = a == b
        }
    }
}
