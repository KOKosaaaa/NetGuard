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
        R.id.action_restart_xray -> { viewModel.restartService("xray"); true }
        R.id.action_uninstall_xray -> {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.srv_action_uninstall_xray)
                .setMessage("Wipe xray + config on this server?")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewModel.uninstallXray { /* stay on screen */ }
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
            Toast.makeText(requireContext(),
                "Update handler: TODO follow-up", Toast.LENGTH_SHORT).show()
            true
        }
        else -> false
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
