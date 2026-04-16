package com.smarttools.netguard.ui.profiles

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentProfileListBinding
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.util.QRGenerator
import com.smarttools.netguard.util.ServiceTester
import com.smarttools.netguard.viewmodel.MainViewModel
import com.smarttools.netguard.viewmodel.ProfileListViewModel
import kotlinx.coroutines.launch

class ProfileListFragment : Fragment() {

    private var _binding: FragmentProfileListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileListViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: ProfileAdapter
    private val groupDecoration = SubscriptionGroupDecoration()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProfileAdapter(
            onItemClick = { profile ->
                // Use MainViewModel — handles reconnect if VPN is active
                mainViewModel.selectProfile(profile.id)
            },
            onItemLongClick = { profile ->
                showProfileActions(profile)
            },
            onPingClick = { profile ->
                viewModel.pingProfile(profile)
            },
            onFavoriteClick = { profile ->
                viewModel.toggleFavorite(profile.id)
            }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProfiles.adapter = adapter
        binding.rvProfiles.addItemDecoration(groupDecoration)

        // Swipe to delete
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (from >= adapter.currentList.size || to >= adapter.currentList.size) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.updateSortOrder(adapter.getReorderedList())
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= adapter.currentList.size) return
                val profile = adapter.currentList[pos]
                viewModel.deleteProfile(profile)
                Snackbar.make(binding.root, R.string.profile_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) {
                        viewModel.importFromText(profile.toUri())
                    }
                    .show()
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvProfiles)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_ping_all -> { viewModel.pingAll(); true }
                R.id.action_test_services -> { viewModel.testServices(); true }
                R.id.action_sort_ping -> { viewModel.sortByPing(); true }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.profiles.collect { profiles ->
                        groupDecoration.setData(profiles, viewModel.subscriptionNames.value)
                        adapter.submitList(profiles)
                        binding.tvEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.subscriptionNames.collect { names ->
                        groupDecoration.setData(viewModel.profiles.value, names)
                        binding.rvProfiles.invalidateItemDecorations()
                    }
                }
                launch {
                    viewModel.importResult.collect { result ->
                        val msg = if (result.errors.isEmpty()) {
                            "Imported ${result.added} profile(s)"
                        } else {
                            "Imported ${result.added}, errors: ${result.errors.size}"
                        }
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.pinging.collect { pinging ->
                        binding.progressPing.visibility = if (pinging) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.serviceTestResults.collect { results ->
                        showServiceTestResults(results)
                    }
                }
            }
        }
    }

    private fun showProfileActions(profile: ServerProfile) {
        val items = arrayOf(
            getString(R.string.share_qr),
            getString(R.string.share_uri),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(profile.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showQRDialog(profile)
                    1 -> {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("profile", profile.toUri()))
                        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
                        binding.root.postDelayed({
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                    clipboard.clearPrimaryClip()
                                } else {
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                                }
                            } catch (_: Exception) {}
                        }, 30_000)
                    }
                    2 -> {
                        viewModel.deleteProfile(profile)
                        Snackbar.make(binding.root, R.string.profile_deleted, Snackbar.LENGTH_LONG)
                            .setAction(R.string.undo) { viewModel.importFromText(profile.toUri()) }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showServiceTestResults(results: List<ServiceTester.TestResult>) {
        val accessible = results.count { it.accessible }
        val total = results.size
        val sb = StringBuilder()
        for (r in results) {
            val icon = if (r.accessible) "\u2705" else "\u274C"
            val time = if (r.accessible && r.responseTimeMs > 0) "  ${r.responseTimeMs}ms" else ""
            sb.appendLine("$icon  ${r.service.icon} ${r.service.name}$time")
        }
        sb.appendLine()
        sb.append(getString(R.string.service_score, accessible, total))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.service_test_results)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showQRDialog(profile: ServerProfile) {
        val uri = profile.toUri()
        val qrBitmap = QRGenerator.generate(uri, 600)
        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(qrBitmap)
            setPadding(48, 48, 48, 16)
            adjustViewBounds = true
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(profile.name)
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.share_uri) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("profile", uri))
                Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroyView() {
        _binding?.root?.handler?.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }
}
