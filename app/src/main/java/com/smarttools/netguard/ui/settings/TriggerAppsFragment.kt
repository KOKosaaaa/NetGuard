package com.smarttools.netguard.ui.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.App
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentTriggerAppsBinding
import com.smarttools.netguard.service.TriggerWatcherService
import com.smarttools.netguard.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TriggerAppsFragment : Fragment() {

    private var _binding: FragmentTriggerAppsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by activityViewModels()

    private var allApps = listOf<AppItem>()
    private val selectedPackages = mutableSetOf<String>()
    private var showSystemApps = false
    private var searchQuery = ""
    /** Suppresses auto-save when we set switch state programmatically from settings. */
    private var initializingUi = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            applyAndExit(true)
        } else {
            Toast.makeText(requireContext(), "VPN permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTriggerAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val settings = viewModel.settings.value
        selectedPackages.addAll(settings.triggerApps)
        initializingUi = true
        binding.swTriggerEnable.isChecked = settings.triggerEnabled
        binding.swTriggerAutoStop.isChecked = settings.triggerAutoStop
        binding.swTriggerStrict.isChecked = settings.triggerStrictMode
        initializingUi = false

        refreshPermissionUi()

        val adapter = AppListAdapter { item ->
            if (item.isChecked) selectedPackages.add(item.packageName)
            else selectedPackages.remove(item.packageName)
            updateCount()
        }

        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter

        binding.etSearch.doAfterTextChanged {
            searchQuery = it?.toString()?.lowercase() ?: ""
            adapter.submitList(filterApps())
        }

        binding.cbSystemApps.setOnCheckedChangeListener { _, checked ->
            showSystemApps = checked
            adapter.submitList(filterApps())
        }

        // Switches auto-save on toggle. The Save button below remains for the
        // app-list selection (where order/check state is buffered until apply),
        // but toggling the master switches no longer requires hunting for it.
        binding.swTriggerEnable.setOnCheckedChangeListener { _, checked ->
            if (initializingUi) return@setOnCheckedChangeListener
            persistSwitches(applyImmediately = true, enabledOverride = checked)
        }
        binding.swTriggerAutoStop.setOnCheckedChangeListener { _, _ ->
            if (initializingUi) return@setOnCheckedChangeListener
            persistSwitches(applyImmediately = false)
        }
        binding.swTriggerStrict.setOnCheckedChangeListener { _, _ ->
            if (initializingUi) return@setOnCheckedChangeListener
            persistSwitches(applyImmediately = false)
        }

        binding.btnOpenVpnSettings.setOnClickListener {
            try {
                startActivity(Intent("android.net.vpn.SETTINGS"))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Open Settings → VPN → NetGuard manually", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {
                // Fallback: open app's settings
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(fallback)
            }
        }

        binding.btnSave.setOnClickListener { save() }

        binding.btnDualappMore.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trigger_dualapp_explain_title)
                .setMessage(R.string.trigger_dualapp_explain_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.btnToggleHeader.setOnClickListener {
            val collapsed = binding.headerSection.visibility == View.GONE
            binding.headerSection.visibility = if (collapsed) View.VISIBLE else View.GONE
            binding.btnToggleHeader.setText(
                if (collapsed) R.string.trigger_collapse else R.string.trigger_expand
            )
        }

        binding.progressLoading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            allApps = withContext(Dispatchers.IO) { loadApps() }
            if (_binding == null) return@launch
            binding.progressLoading.visibility = View.GONE
            adapter.submitList(filterApps())
            updateCount()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    /**
     * Saves the three switch states immediately (without touching the apps
     * list, which the user explicitly applies via the Save button along with
     * the VPN-permission flow). When [applyImmediately] is true the trigger
     * watcher is also reconfigured so that toggling the master switch off
     * really stops the watcher and quarantine TUN.
     */
    private fun persistSwitches(applyImmediately: Boolean, enabledOverride: Boolean? = null) {
        val enabled = enabledOverride ?: binding.swTriggerEnable.isChecked
        val strict = binding.swTriggerStrict.isChecked
        val autoStop = binding.swTriggerAutoStop.isChecked
        viewModel.updateSettings { s ->
            s.copy(
                triggerEnabled = enabled,
                triggerAutoStop = autoStop,
                triggerStrictMode = strict,
            )
        }
        if (applyImmediately) {
            val app = requireContext().applicationContext as App
            // Stop / restart watcher to mirror the new state. We do NOT prompt
            // for VPN permission here — that still happens via the Save flow,
            // which also commits the apps-list selection.
            app.updateTriggerWatcher(enabled && selectedPackages.isNotEmpty())
        }
    }

    private fun refreshPermissionUi() {
        val granted = TriggerWatcherService.hasUsageStatsPermission(requireContext())
        binding.btnGrantPermission.visibility = if (granted) View.GONE else View.VISIBLE
        binding.swTriggerEnable.isEnabled = granted
        if (!granted) {
            binding.swTriggerEnable.isChecked = false
            Toast.makeText(requireContext(), R.string.trigger_no_permission, Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val enabled = binding.swTriggerEnable.isChecked
        val activeApps = selectedPackages.toSet()

        val strictMode = binding.swTriggerStrict.isChecked
        // Conflict check only matters in strict mode (where Trigger handles
        // routing exclusively). Flexible mode is INTENDED to compose with
        // perAppMode, so we leave the lists alone there.
        val current = viewModel.settings.value
        val overlap = if (enabled && strictMode &&
            current.perAppMode == com.smarttools.netguard.model.PerAppMode.BLACKLIST) {
            current.perAppList.intersect(activeApps)
        } else emptySet()
        if (overlap.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                "Removed ${overlap.size} app(s) from per-app blacklist (would bypass trigger)",
                Toast.LENGTH_LONG
            ).show()
        }

        val perAppWasOn = enabled && strictMode &&
            current.perAppMode != com.smarttools.netguard.model.PerAppMode.DISABLED
        if (perAppWasOn) {
            Toast.makeText(requireContext(), R.string.trigger_perapp_conflict, Toast.LENGTH_LONG).show()
        }
        viewModel.updateSettings { s ->
            val newPerAppList = if (overlap.isNotEmpty()) s.perAppList - overlap else s.perAppList
            // Flexible trigger mode coexists with Per-App routing — that's the
            // whole point of it. Only strict mode forces perAppMode=DISABLED.
            val newPerAppMode = if (enabled && strictMode) {
                com.smarttools.netguard.model.PerAppMode.DISABLED
            } else {
                s.perAppMode
            }
            s.copy(
                triggerEnabled = enabled,
                triggerApps = activeApps,
                triggerAutoStop = binding.swTriggerAutoStop.isChecked,
                triggerStrictMode = strictMode,
                perAppList = newPerAppList,
                perAppMode = newPerAppMode
            )
        }

        if (enabled && activeApps.isNotEmpty()) {
            // Request VPN permission first so quarantine TUN can come up.
            val prepareIntent = VpnService.prepare(requireContext())
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
                return
            }
            applyAndExit(true)
        } else {
            applyAndExit(false)
        }
    }

    private fun applyAndExit(enable: Boolean) {
        val app = requireContext().applicationContext as App
        if (enable) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val selected = app.database.profileDao().getSelected()
                // Persist last_profile_id so Android Always-on auto-start can
                // bring the tunnel up with the right server.
                if (selected != null) {
                    app.getPreferences().edit()
                        .putLong("last_profile_id", selected.id)
                        .putString("last_profile_name", selected.name)
                        .apply()
                }
                withContext(Dispatchers.Main) {
                    if (selected == null) {
                        Toast.makeText(requireContext(), R.string.trigger_no_profile, Toast.LENGTH_LONG).show()
                    }
                    app.updateTriggerWatcher(true)
                    if (isAdded) requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        } else {
            app.updateTriggerWatcher(false)
            if (isAdded) requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun loadApps(): List<AppItem> {
        val pm = requireContext().packageManager
        val apps = pm.getInstalledApplications(0)
        return apps.map { info ->
            AppItem(
                packageName = info.packageName,
                label = info.loadLabel(pm).toString(),
                icon = try { info.loadIcon(pm) } catch (_: Exception) { null },
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isChecked = info.packageName in selectedPackages
            )
        }.sortedWith(compareBy({ !it.isChecked }, { it.label.lowercase() }))
    }

    private fun filterApps(): List<AppItem> {
        return allApps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (searchQuery.isEmpty() ||
                    app.label.lowercase().contains(searchQuery) ||
                    app.packageName.contains(searchQuery))
        }
    }

    private fun updateCount() {
        binding.tvSelectedCount.text = getString(R.string.apps_selected, selectedPackages.size)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
