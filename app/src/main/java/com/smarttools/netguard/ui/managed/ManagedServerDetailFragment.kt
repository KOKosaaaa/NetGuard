package com.smarttools.netguard.ui.managed

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
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentManagedServerDetailBinding
import kotlinx.coroutines.launch

/**
 * Detail screen for one managed server. Reads the serverId from
 * fragment arguments (set by the list cell click), bootstraps the
 * shared ManagedServerDetailViewModel, then hands the rest off to the
 * four tab fragments via a FragmentStateAdapter.
 *
 * ViewModel is scoped to the **activity** so tab fragments share the
 * same instance — every tab sees the same StateFlows + the same
 * AgentApiClient (per-host CertificatePinner is expensive to rebuild).
 */
class ManagedServerDetailFragment : Fragment() {

    private var _binding: FragmentManagedServerDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManagedServerDetailViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentManagedServerDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val serverId = arguments?.getLong(ARG_SERVER_ID, 0L) ?: 0L
        if (serverId == 0L) {
            findNavController().navigateUp()
            return
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener(::onMenu)

        binding.pager.adapter = TabsAdapter(this)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.srv_tab_overview
                    1 -> R.string.srv_tab_profiles
                    2 -> R.string.srv_tab_rules
                    else -> R.string.srv_tab_logs
                }
            )
        }.attach()

        viewLifecycleOwner.lifecycleScope.launch {
            val ok = viewModel.init(serverId)
            if (!ok) {
                findNavController().navigateUp()
                return@launch
            }
            binding.toolbar.title = viewModel.currentServer().name
            // Prime the first three tabs once so cards aren't empty.
            viewModel.refreshStatus()
            viewModel.refreshProfiles()
            viewModel.refreshOutbounds()
            viewModel.refreshRules()
        }

        // Surface ViewModel toasts at the activity level.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toast.collect { msg ->
                    if (msg != null) {
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        viewModel.consumeToast()
                    }
                }
            }
        }
    }

    private fun onMenu(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_rename -> {
            promptRename()
            true
        }
        // Telemost stream count moved into the profile management screen
        // (Profiles tab → Telemost → Change room count).
        R.id.action_swap_setup -> {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.srv_swap_title)
                .setMessage(R.string.srv_swap_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // Spinner while the agent works, then a clear success/fail
                    // dialog — a silent toast left users unsure it ran.
                    val progress = showProgress(getString(R.string.srv_swap_progress))
                    viewModel.setupSwap { ok, msg ->
                        if (!isAdded) return@setupSwap
                        runCatching { progress.dismiss() }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(if (ok) R.string.srv_swap_done else R.string.srv_swap_failed)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        R.id.action_uninstall_xray -> {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.srv_action_uninstall_xray)
                .setMessage(R.string.srv_uninstall_xray_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val progress = showProgress(getString(R.string.srv_uninstall_xray_progress))
                    viewModel.uninstallXray { ok, msg ->
                        if (!isAdded) return@uninstallXray
                        runCatching { progress.dismiss() }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(if (ok) R.string.srv_swap_done else R.string.srv_swap_failed)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        R.id.action_purge -> {
            // Destructive + outward-facing (changes the remote box): confirm
            // with explicit wording about what gets wiped before firing.
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.srv_action_purge)
                .setMessage(R.string.srv_purge_confirm)
                .setPositiveButton(R.string.srv_purge_confirm_btn) { _, _ ->
                    val progress = showProgress(getString(R.string.srv_purge_progress))
                    viewModel.purgeServer { ok, msg ->
                        if (!isAdded) return@purgeServer
                        runCatching { progress.dismiss() }
                        val dlg = MaterialAlertDialogBuilder(requireContext())
                            .setTitle(if (ok) R.string.srv_swap_done else R.string.srv_swap_failed)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                if (ok) findNavController().navigateUp()
                            }
                        if (ok) dlg.setCancelable(false)
                        dlg.show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        R.id.action_remove_server -> {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.srv_action_remove_server)
                .setMessage("Remove this server from the app? Agent keeps running on the VPS.")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewModel.removeServer { findNavController().navigateUp() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        R.id.action_update_agent -> {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.srv_action_update_agent)
                .setMessage(R.string.srv_update_agent_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val progress = showProgress(getString(R.string.srv_update_agent_progress))
                    viewModel.updateAgent { ok, msg ->
                        if (!isAdded) return@updateAgent
                        runCatching { progress.dismiss() }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(if (ok) R.string.srv_swap_done else R.string.srv_swap_failed)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        else -> false
    }

    /** A non-cancelable spinner+label dialog shown while a server op runs. */
    private fun showProgress(message: String): androidx.appcompat.app.AlertDialog {
        val row = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(64, 48, 64, 48)
            addView(android.widget.ProgressBar(requireContext()))
            addView(android.widget.TextView(requireContext()).apply {
                text = message
                setPadding(40, 0, 0, 0)
            })
        }
        return MaterialAlertDialogBuilder(requireContext())
            .setView(row).setCancelable(false).show()
    }

    /** Modal text-input dialog that calls [viewModel.rename] on OK. */
    private fun promptRename() {
        // Pre-fill with the current name so the user is editing rather
        // than re-typing from scratch.
        val current = viewModel.status.value?.let { "" } // current name isn't on _status; pull from server view
        val edit = com.google.android.material.textfield.TextInputEditText(requireContext())
        edit.hint = getString(R.string.srv_rename_hint)
        // The current name lives on the ViewModel's `server` private —
        // we expose just enough through the toolbar binding below.
        edit.setText(binding.toolbar.title)
        val layout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            addView(edit)
            setPadding(48, 0, 48, 0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.srv_rename_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = edit.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty()) {
                    viewModel.rename(newName) {
                        binding.toolbar.title = newName
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private class TabsAdapter(host: Fragment) : FragmentStateAdapter(host) {
        override fun getItemCount(): Int = 4
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ServerOverviewFragment()
            1 -> ServerProfilesFragment()
            2 -> ServerRulesFragment()
            else -> ServerLogsFragment()
        }
    }

    companion object {
        const val ARG_SERVER_ID = "server_id"
        fun args(serverId: Long): Bundle = Bundle().apply { putLong(ARG_SERVER_ID, serverId) }
    }
}
