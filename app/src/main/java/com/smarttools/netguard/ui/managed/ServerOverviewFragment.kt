package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smarttools.netguard.R
import com.smarttools.netguard.agent.ServiceState
import com.smarttools.netguard.agent.StatusResponse
import com.smarttools.netguard.databinding.FragmentServerOverviewBinding
import com.smarttools.netguard.databinding.ItemServiceRowBinding
import kotlinx.coroutines.launch

class ServerOverviewFragment : Fragment() {

    private var _b: FragmentServerOverviewBinding? = null
    private val b get() = _b!!
    private val vm: ManagedServerDetailViewModel by activityViewModels()
    private lateinit var adapter: ServicesAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentServerOverviewBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ServicesAdapter { svcName -> vm.restartService(svcName) }
        b.rvServices.layoutManager = LinearLayoutManager(requireContext())
        b.rvServices.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.status.collect { s ->
                    if (s != null) bindStatus(s)
                }
            }
        }
    }

    private fun bindStatus(s: StatusResponse) {
        val ramPct = if (s.memTotalMb > 0) (s.memUsedMb * 100) / s.memTotalMb else 0
        val diskPct = if (s.diskTotalGb > 0) (s.diskUsedGb * 100 / s.diskTotalGb).toInt() else 0
        val load = s.loadAvg.joinToString("/") { "%.2f".format(it) }
        b.tvStatusSummary.text = buildString {
            appendLine(getString(R.string.srv_load_avg) + ": " + load)
            appendLine(getString(R.string.srv_memory) + ": " +
                "${s.memUsedMb}/${s.memTotalMb} MB ($ramPct%)")
            appendLine(getString(R.string.srv_disk) + ": " +
                "${s.diskUsedGb}/${s.diskTotalGb} GB ($diskPct%)")
            append(getString(R.string.srv_uptime) + ": " + formatUptime(s.hostUptimeS))
        }
        adapter.submitList(s.services)
    }

    private fun formatUptime(s: Long): String = when {
        s > 86400 -> "${s / 86400}d ${(s % 86400) / 3600}h"
        s > 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s > 60 -> "${s / 60}m"
        else -> "${s}s"
    }

    override fun onDestroyView() {
        _b = null; super.onDestroyView()
    }

    private class ServicesAdapter(
        private val onRestart: (String) -> Unit,
    ) : ListAdapter<ServiceState, ServicesAdapter.VH>(DIFF) {
        inner class VH(val b: ItemServiceRowBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(ItemServiceRowBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = getItem(pos)
            with(h.b) {
                tvSvcName.text = s.name
                tvSvcMeta.text = if (s.active) {
                    val pid = s.pid?.let { "pid $it" } ?: ""
                    "active · $pid"
                } else "inactive"
                btnRestart.isEnabled = s.active
                btnRestart.setOnClickListener { onRestart(s.name) }
            }
        }
        companion object {
            val DIFF = object : DiffUtil.ItemCallback<ServiceState>() {
                override fun areItemsTheSame(a: ServiceState, b: ServiceState) = a.name == b.name
                override fun areContentsTheSame(a: ServiceState, b: ServiceState) = a == b
            }
        }
    }
}
