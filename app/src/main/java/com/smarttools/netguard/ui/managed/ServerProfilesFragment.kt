package com.smarttools.netguard.ui.managed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.agent.InboundRow
import com.smarttools.netguard.databinding.FragmentServerProfilesBinding
import com.smarttools.netguard.databinding.ItemServerProfileBinding
import kotlinx.coroutines.launch

class ServerProfilesFragment : Fragment() {

    private var _b: FragmentServerProfilesBinding? = null
    private val b get() = _b!!
    private val vm: ManagedServerDetailViewModel by activityViewModels()
    private lateinit var adapter: ProfilesAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentServerProfilesBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ProfilesAdapter(
            onCopy = ::copyUri,
            onDelete = ::confirmDelete,
        )
        b.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        b.rvProfiles.adapter = adapter

        b.fabAdd.setOnClickListener { showAddDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.profiles.collect { list ->
                    adapter.submitList(list)
                    b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val etLabel = EditText(ctx).apply {
            hint = getString(R.string.srv_profile_label_hint)
            container.addView(this)
        }
        val etPort = EditText(ctx).apply {
            hint = getString(R.string.srv_profile_port_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            container.addView(this)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.srv_add_profile)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ ->
                val label = etLabel.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: 0
                vm.addProfile(label, port)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyUri(row: InboundRow) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vless", row.profileUri))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(row: InboundRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage("Delete inbound on port ${row.port}?")
            .setPositiveButton(android.R.string.ok) { _, _ -> vm.deleteProfile(row.inboundId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    private class ProfilesAdapter(
        val onCopy: (InboundRow) -> Unit,
        val onDelete: (InboundRow) -> Unit,
    ) : ListAdapter<InboundRow, ProfilesAdapter.VH>(DIFF) {
        inner class VH(val b: ItemServerProfileBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(ItemServerProfileBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = getItem(pos)
            with(h.b) {
                tvProtoPort.text = "${r.protocol.uppercase()} : ${r.port}"
                tvUri.text = r.profileUri
                btnCopy.setOnClickListener { onCopy(r) }
                btnDelete.setOnClickListener { onDelete(r) }
            }
        }
        companion object {
            val DIFF = object : DiffUtil.ItemCallback<InboundRow>() {
                override fun areItemsTheSame(a: InboundRow, b: InboundRow) = a.inboundId == b.inboundId
                override fun areContentsTheSame(a: InboundRow, b: InboundRow) = a == b
            }
        }
    }
}
