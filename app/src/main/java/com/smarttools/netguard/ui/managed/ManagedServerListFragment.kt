package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.smarttools.netguard.databinding.FragmentManagedServerListBinding
import kotlinx.coroutines.launch

/**
 * Lists the VPS instances this device controls through netguard-agent.
 *
 * Phase-1 surface: empty-state placeholder + RecyclerView + FAB. The
 * cell adapter and Add-Server wizard land in follow-up commits — this
 * just gets the navigation hop landing somewhere usable so we can
 * iterate the rest in isolation.
 */
class ManagedServerListFragment : Fragment() {

    private var _binding: FragmentManagedServerListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManagedServerListViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentManagedServerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.inflateMenu(com.smarttools.netguard.R.menu.menu_managed_server_list)
        // Routes (multi-hop chains) are available to everyone now — the UI
        // is self-explanatory enough.
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                com.smarttools.netguard.R.id.action_show_chains -> {
                    ChainsListSheet().show(parentFragmentManager, ChainsListSheet.TAG)
                    true
                }
                else -> false
            }
        }

        val adapter = ManagedServerAdapter { server ->
            findNavController().navigate(
                com.smarttools.netguard.R.id.action_my_servers_to_detail,
                ManagedServerDetailFragment.args(server.id),
            )
        }
        binding.rvServers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvServers.adapter = adapter

        binding.fabAddServer.setOnClickListener {
            findNavController().navigate(
                com.smarttools.netguard.R.id.action_my_servers_to_add_server
            )
        }

        binding.fabNewChain.setOnClickListener {
            CreateChainSheet().show(parentFragmentManager, CreateChainSheet.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.servers.collect { list ->
                    binding.layoutEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvServers.visibility =
                        if (list.isEmpty()) View.GONE else View.VISIBLE
                    // Chain wizard needs ≥2 servers — anything less and
                    // the button just causes a frustrating "need more
                    // servers" dialog, so we hide it instead.
                    binding.fabNewChain.visibility =
                        if (list.size >= 2) View.VISIBLE else View.GONE
                    adapter.submitList(list)
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
