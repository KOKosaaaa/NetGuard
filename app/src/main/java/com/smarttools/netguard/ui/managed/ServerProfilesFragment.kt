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
    private fun l10n(id: Int, vararg args: Any): String = com.smarttools.netguard.util.LocalizedResources.string(requireContext(), id, *args)


    private var _b: FragmentServerProfilesBinding? = null
    private val b get() = _b!!
    private val vm: ManagedServerDetailViewModel by activityViewModels()
    private lateinit var adapter: ProfilesAdapter
    private var hasProfiles = false
    private var hasTelemost = false
    private var hasWbStream = false

    private fun updateEmpty() {
        val empty = !hasProfiles && !hasTelemost && !hasWbStream
        b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        // FAB hides on empty so the centered CTA is the only call-to-action.
        b.fabAdd.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentServerProfilesBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ProfilesAdapter(
            onCopy = ::copyUri,
            onManage = ::showVlessManage,
        )
        vm.refreshTelemost()
        b.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        b.rvProfiles.adapter = adapter

        b.fabAdd.setOnClickListener { showWizard() }
        b.btnEmptyCreate.setOnClickListener { showWizard() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.profiles.collect { list ->
                    adapter.submitList(list)
                    hasProfiles = list.isNotEmpty()
                    b.rvProfiles.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                    updateEmpty()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.wbStream.collect { rooms ->
                    hasWbStream = rooms != null && rooms.installed > 0
                    b.wbstreamCard.visibility = if (hasWbStream) View.VISIBLE else View.GONE
                    if (hasWbStream) {
                        b.tvWbstreamSummary.text = getString(com.smarttools.netguard.R.string.wb_rooms_connected, rooms!!.activeCount, rooms.installed) +
                            if (rooms.instances.any { it.ownerState == "wb_blocked" }) l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_1)
                            else if (rooms.instances.any { it.ownerState == "needs_login" }) l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_2)
                            else if (rooms.instances.all { it.ownerState == "hosting" }) l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_3)
                            else l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_4)
                        b.wbstreamCard.setOnClickListener {
                            val links = rooms.instances.joinToString("\n") { it.room }
                            val uri = com.smarttools.netguard.model.WbStreamLink.encode(links, "WB Stream")
                            MaterialAlertDialogBuilder(requireContext()).setTitle("WB Stream")
                                .setMessage(getString(com.smarttools.netguard.R.string.wb_rooms_recovery_help, rooms.installed) + rooms.instances.joinToString("\n") { "${it.index}: " + when(it.ownerState) {
                                    "hosting" -> l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_5)
                                    "needs_login" -> l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_6)
                                    "wb_blocked" -> l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_7)
                                    "starting", "reconnecting", "checking" -> l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_8)
                                    "stopped" -> l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_9)
                                    else -> l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_10)
                                } })
                                .setView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                                    text = l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_11)
                                    setOnClickListener {
                                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("WB Stream", uri))
                                        Toast.makeText(requireContext(), l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_12), Toast.LENGTH_SHORT).show()
                                    }
                                })
                                .setNeutralButton(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_13)) { _, _ ->
                                    val srv = vm.serverOrNull ?: return@setNeutralButton
                                    fun openRoom(link: String) {
                                        startActivity(android.content.Intent(requireContext(), CreateWbStreamActivity::class.java)
                                            .putExtra(CreateWbStreamActivity.EXTRA_SERVER_ID, srv.id)
                                            .putExtra(CreateWbStreamActivity.EXTRA_ROOM, link))
                                    }
                                    if (rooms.instances.size == 1) openRoom(rooms.instances.first().room)
                                    else MaterialAlertDialogBuilder(requireContext()).setTitle(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_14))
                                        .setItems(rooms.instances.map { it.room.substringAfterLast('/') }.toTypedArray()) { _, index -> openRoom(rooms.instances[index].room) }.show()
                                }
                                .setPositiveButton(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_15)) { _, _ ->
                                    val progress = MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_16))
                                        .setMessage(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_17))
                                        .setCancelable(false).show()
                                    vm.updateWbStream { ok, message ->
                                        runCatching { progress.dismiss() }
                                        if (isAdded) MaterialAlertDialogBuilder(requireContext())
                                            .setTitle(if (ok) l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_18) else l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_19))
                                            .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                                    }
                                }
                                .setNegativeButton(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_20)) { _, _ ->
                                    confirmPermanentDelete {
                                        val progress = MaterialAlertDialogBuilder(requireContext())
                                            .setTitle(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_21)).setMessage(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_22))
                                            .setCancelable(false).show()
                                        vm.deleteWbStream { ok, message ->
                                            runCatching { progress.dismiss() }
                                            if (isAdded) MaterialAlertDialogBuilder(requireContext())
                                                .setTitle(if (ok) l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_18) else l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_23))
                                                .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                                        }
                                    }
                                }.show()
                        }
                    }
                    updateEmpty()
                }
            }
        }
        // Telemost shown as one tappable "profile" card above the VLESS list.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.telemost.collect { rooms ->
                    val deployed = rooms != null && rooms.installed > 0
                    hasTelemost = deployed
                    b.telemostCard.visibility = if (deployed) View.VISIBLE else View.GONE
                    if (deployed) {
                        b.tvTelemostSummary.text = resources.getQuantityString(
                            R.plurals.telemost_active_rooms, rooms!!.activeCount, rooms.activeCount)
                        b.telemostCard.setOnClickListener { showTelemostManage(rooms) }
                    }
                    updateEmpty()
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
        // Show the current deploy stage in the overlay (fresh-server install)
        // so the user sees what's happening, not a static "creating…".
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.createStep.collect { step ->
                    b.tvBusyMsg.text = step ?: getString(R.string.srv_creating_profile)
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
            text = err.rawDetails.ifBlank { l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_24) }
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

    // --- per-profile management (tap a profile row / the Telemost card) ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun manageBox() = android.widget.LinearLayout(requireContext()).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(8))
    }

    private fun fullWidth(topDp: Int) = android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topDp) }

    private fun actionBtn(text: String, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(requireContext()).apply {
            this.text = text
            layoutParams = fullWidth(8)
            setOnClickListener { onClick() }
        }

    private fun redDeleteBtn(onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = getString(R.string.srv_delete_profile)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = fullWidth(20)
            setOnClickListener { onClick() }
        }

    private fun showVlessManage(row: InboundRow) {
        val active = vm.status.value?.services?.firstOrNull { it.name == "xray" }?.active == true
        val box = manageBox()
        var dialog: androidx.appcompat.app.AlertDialog? = null
        box.addView(android.widget.TextView(requireContext()).apply {
            text = if (active) getString(R.string.srv_status_active)
            else getString(R.string.srv_status_inactive)
        })
        box.addView(actionBtn(getString(R.string.srv_restart)) {
            dialog?.dismiss()
            showRestartProgress { cb -> vm.restartService("xray", cb) }
        })
        box.addView(redDeleteBtn {
            dialog?.dismiss()
            confirmPermanentDelete {
                showApplying(getString(R.string.srv_deleting_profile)) { cb ->
                    vm.deleteProfile(row.inboundId, cb)
                }
            }
        })
        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("${row.protocol.uppercase()} : ${row.port}")
            .setView(box)
            .setNegativeButton(R.string.srv_profile_ready_close, null)
            .show()
    }

    private fun showTelemostManage(rooms: com.smarttools.netguard.agent.TelemostRooms) {
        val box = manageBox()
        var dialog: androidx.appcompat.app.AlertDialog? = null
        box.addView(android.widget.TextView(requireContext()).apply {
            text = resources.getQuantityString(
                R.plurals.telemost_active_rooms, rooms.activeCount, rooms.activeCount)
        })
        // "Restart" re-applies the CURRENT room count (the number running now),
        // not the provisioned capacity — otherwise restarting after a scale-
        // down would bring the old rooms back. Falls back to capacity only if
        // everything is down (transient crash).
        val currentCount = if (rooms.activeCount > 0) rooms.activeCount else rooms.installed
        box.addView(actionBtn(getString(R.string.srv_restart)) {
            dialog?.dismiss()
            showRestartProgress { cb -> vm.scaleTelemost(currentCount, cb) }
        })
        box.addView(actionBtn(getString(R.string.srv_telemost_change_count)) {
            dialog?.dismiss(); showRoomCountDialog(rooms.installed)
        })
        box.addView(redDeleteBtn {
            dialog?.dismiss()
            confirmPermanentDelete {
                vm.uninstallTelemost { ok, msg ->
                    if (!isAdded) return@uninstallTelemost
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(if (ok) R.string.srv_swap_done else R.string.srv_swap_failed)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        })
        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Telemost")
            .setView(box)
            .setNegativeButton(R.string.srv_profile_ready_close, null)
            .show()
    }

    private fun showRoomCountDialog(current: Int) {
        val slider = com.google.android.material.slider.Slider(requireContext()).apply {
            valueFrom = 1f; valueTo = 12f; stepSize = 1f
            value = current.coerceIn(1, 12).toFloat()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.srv_telemost_change_count)
            .setMessage(R.string.telemost_streams_explain)
            .setView(slider)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = slider.value.toInt()
                showApplying { cb -> vm.scaleTelemost(n, cb) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Spinner dialog while an operation runs, then a clear result — used for
    // "change room count" so it doesn't feel like nothing happened.
    private fun showApplying(
        message: String = l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_25),
        run: ((ok: Boolean, msg: String) -> Unit) -> Unit,
    ) {
        val row = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(android.widget.ProgressBar(requireContext()))
            addView(android.widget.TextView(requireContext()).apply {
                setPadding(dp(20), 0, 0, 0); text = message
            })
        }
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setView(row).setCancelable(false).show()
        run { ok, msg ->
            if (!isAdded) return@run
            runCatching { dlg.dismiss() }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (ok) R.string.srv_swap_done else R.string.srv_swap_failed)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // Staged progress while a restart runs: cycles through plausible stages
    // (stop → check → system → start) on a timer, then shows the real result
    // from [run]'s callback. The agent restart is one operation, so the
    // intermediate stages are indicative, not literal — but the user sees it
    // working and gets a clear Done/Error instead of a silent toast.
    private fun showRestartProgress(run: ((ok: Boolean, msg: String) -> Unit) -> Unit) {
        val stages = listOf(l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_26), l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_27), l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_28), l10n(com.smarttools.netguard.R.string.loc_server_profiles_fragment_29))
        val tv = android.widget.TextView(requireContext()).apply {
            setPadding(dp(20), 0, 0, 0); text = stages[0]
        }
        val rowView = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(android.widget.ProgressBar(requireContext()))
            addView(tv)
        }
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setView(rowView).setCancelable(false).show()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var i = 0
        val ticker = object : Runnable {
            override fun run() {
                if (i < stages.size - 1) { i++; tv.text = stages[i]; handler.postDelayed(this, 700) }
            }
        }
        handler.postDelayed(ticker, 700)
        run { ok, msg ->
            handler.removeCallbacks(ticker)
            if (!isAdded) return@run // navigated away mid-op — nothing to show
            runCatching { dlg.dismiss() }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (ok) R.string.srv_swap_done else R.string.srv_swap_failed)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmPermanentDelete(onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.srv_delete_profile)
            .setMessage(R.string.srv_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshWbStream()
        // Re-pull so a profile created elsewhere (or just now) shows up
        // without leaving and re-entering the tab.
        vm.refreshProfiles()
        vm.refreshTelemost()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    private class ProfilesAdapter(
        val onCopy: (InboundRow) -> Unit,
        val onManage: (InboundRow) -> Unit,
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
                // Delete lives in the manage screen now; tap the row to open it.
                btnDelete.visibility = View.GONE
                root.setOnClickListener { onManage(r) }
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
