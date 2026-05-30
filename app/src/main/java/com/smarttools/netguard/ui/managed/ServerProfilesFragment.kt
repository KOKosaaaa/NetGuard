package com.smarttools.netguard.ui.managed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        b.fabAdd.setOnClickListener { showWizard() }
        b.btnEmptyCreate.setOnClickListener { showWizard() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.profiles.collect { list ->
                    adapter.submitList(list)
                    val empty = list.isEmpty()
                    b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                    // FAB hides on empty so the centered CTA is the only
                    // call-to-action — avoids the "two + buttons" confusion.
                    b.fabAdd.visibility = if (empty) View.GONE else View.VISIBLE
                    b.rvProfiles.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.creating.collect { creating ->
                    b.busyOverlay.visibility = if (creating) View.VISIBLE else View.GONE
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.lastCreatedUri.collect { uri ->
                    if (uri != null) {
                        showProfileReadyDialog(uri)
                        vm.consumeLastCreatedUri()
                    }
                }
            }
        }
        // Profile-creation failures land here as a structured friendly
        // error. We surface them as a dialog (not a toast) so the user
        // actually has time to read it + can hit Retry.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.lastProfileError.collect { failure ->
                    if (failure != null) {
                        showProfileErrorDialog(failure)
                        vm.consumeLastProfileError()
                    }
                }
            }
        }
    }

    private fun showProfileErrorDialog(
        failure: ManagedServerDetailViewModel.ProfileFailure,
    ) {
        val err = failure.error
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(err.title)
            .setMessage(err.body)
        if (err.retryable) {
            builder.setPositiveButton(R.string.retry) { _, _ ->
                vm.retryLastProfile()
            }
            builder.setNegativeButton(R.string.add_server_show_log) { _, _ ->
                showRawDetails(err)
            }
            builder.setNeutralButton(android.R.string.cancel, null)
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
            builder.setNeutralButton(R.string.add_server_show_log) { _, _ ->
                showRawDetails(err)
            }
        }
        builder.show()
    }

    private fun showRawDetails(err: com.smarttools.netguard.agent.FriendlyError) {
        val tv = android.widget.TextView(requireContext()).apply {
            text = err.rawDetails.ifBlank { "(нет деталей)" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_server_show_log)
            .setView(android.widget.ScrollView(requireContext()).apply { addView(tv) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showWizard() {
        CreateProfileSheet().show(parentFragmentManager, CreateProfileSheet.TAG)
    }

    private fun showProfileReadyDialog(uri: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.srv_profile_ready_title)
            .setMessage(getString(R.string.srv_profile_ready_body))
            .setPositiveButton(R.string.srv_profile_ready_copy) { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("vless", uri))
                Toast.makeText(requireContext(),
                    R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.srv_profile_ready_close, null)
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
