package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.agent.ChainWithHops
import com.smarttools.netguard.databinding.BottomSheetChainsListBinding
import com.smarttools.netguard.databinding.ItemChainRowBinding
import kotlinx.coroutines.launch

/**
 * Lists the multi-hop chains the user has created. Each row shows the
 * label + a one-line "N hops: A → B → C" summary, with a trash icon
 * that pops a confirmation dialog before delegating to
 * [ManagedServerListViewModel.deleteChain] (which fans out to the
 * per-hop agent.deleteProfile calls + ServerProfile cleanup).
 *
 * Reached from the toolbar overflow on ManagedServerListFragment so the
 * user always has a way to find their chains, not just right after the
 * wizard finished.
 */
class ChainsListSheet : BottomSheetDialogFragment() {

    private var _b: BottomSheetChainsListBinding? = null
    private val b get() = _b!!
    private val vm: ManagedServerListViewModel by activityViewModels()
    private lateinit var adapter: ChainsAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = BottomSheetChainsListBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChainsAdapter(::confirmDelete)
        b.rvChains.layoutManager = LinearLayoutManager(requireContext())
        b.rvChains.adapter = adapter

        b.btnCreateRoute.setOnClickListener {
            CreateChainSheet().show(parentFragmentManager, CreateChainSheet.TAG)
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.chains.collect { list ->
                    adapter.submitList(list.toList())
                    b.tvChainsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    b.rvChains.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
        // Create needs >=2 servers (a chain has >=2 hops). Disable + explain
        // why otherwise, rather than letting the wizard fail later.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.servers.collect { servers ->
                    val enough = servers.size >= 2
                    b.btnCreateRoute.isEnabled = enough
                    b.btnCreateRoute.setText(
                        if (enough) R.string.chains_create_button
                        else R.string.chains_create_disabled
                    )
                }
            }
        }
    }

    private fun confirmDelete(chain: ChainWithHops) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chains_confirm_delete_title)
            .setMessage(getString(R.string.chains_confirm_delete_body, chain.chain.label))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ctx = requireContext().applicationContext
                vm.deleteChain(chain) {
                    Toast.makeText(ctx, R.string.chains_delete_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    private class ChainsAdapter(
        private val onDelete: (ChainWithHops) -> Unit,
    ) : ListAdapter<ChainWithHops, ChainsAdapter.VH>(DIFF) {

        inner class VH(val b: ItemChainRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
            VH(ItemChainRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val cw = getItem(pos)
            with(h.b) {
                tvChainLabel.text = cw.chain.label
                tvChainHops.text = h.b.root.context.getString(
                    R.string.chains_hop_summary,
                    cw.hops.size,
                    cw.hops.joinToString(" → ") { it.serverName },
                )
                btnDeleteChain.setOnClickListener { onDelete(cw) }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<ChainWithHops>() {
                override fun areItemsTheSame(a: ChainWithHops, b: ChainWithHops) =
                    a.chain.id == b.chain.id
                override fun areContentsTheSame(a: ChainWithHops, b: ChainWithHops) =
                    a == b
            }
        }
    }

    companion object { const val TAG = "ChainsListSheet" }
}
