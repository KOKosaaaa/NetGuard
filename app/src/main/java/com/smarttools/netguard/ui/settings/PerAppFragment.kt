package com.smarttools.netguard.ui.settings

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentPerAppBinding
import com.smarttools.netguard.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PerAppFragment : Fragment() {

    private var _binding: FragmentPerAppBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by activityViewModels()

    private var allApps = listOf<AppItem>()
    private val selectedPackages = mutableSetOf<String>()
    private var showSystemApps = false
    private var searchQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPerAppBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The RU bypass shortcut is only meaningful for users who interact
        // with Russian banking / gov apps; on other locales it would be
        // misleading and the strings have no native translation.
        val lang = java.util.Locale.getDefault().language
        binding.btnAddRuBypass.visibility = if (lang == "ru" || lang == "en") {
            View.VISIBLE
        } else {
            View.GONE
        }

        val settings = viewModel.settings.value
        selectedPackages.addAll(settings.perAppList)

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

        binding.btnSaveApps.setOnClickListener {
            viewModel.updateSettings { s ->
                s.copy(perAppList = selectedPackages.toSet())
            }
            findNavController().popBackStack()
        }

        binding.btnAddRuBypass.setOnClickListener {
            // Strategy: a hand-maintained list of every Russian app is
            // unmaintainable. Instead we sweep all installed non-system apps
            // and pick everything whose packageName starts with "ru." (the
            // de-facto convention for RU vendors) PLUS a curated com.*
            // override list for Russian apps that publish under com./io.
            // System apps are skipped — they often carry ru.* test stubs
            // that the user does not actually use.
            val curatedNonRuPrefix = resources.getStringArray(R.array.bypass_packages_ru)
                .filter { !it.startsWith("ru.") }
                .toSet()
            val installedNonSystem = allApps.filter { !it.isSystem }
            var addedCount = 0
            for (app in installedNonSystem) {
                val pkg = app.packageName
                if (pkg in selectedPackages) continue
                val isRuPrefix = pkg.startsWith("ru.") || pkg == "ru" || pkg.startsWith("ru_")
                val isCurated = pkg in curatedNonRuPrefix
                if (isRuPrefix || isCurated) {
                    selectedPackages.add(pkg)
                    addedCount++
                }
            }
            // Refresh checkboxes in the visible list
            allApps = allApps.map { it.copy(isChecked = it.packageName in selectedPackages) }
            (binding.rvApps.adapter as? AppListAdapter)?.submitList(filterApps())
            updateCount()
            Toast.makeText(
                requireContext(),
                getString(R.string.ru_bypass_added, addedCount),
                Toast.LENGTH_SHORT
            ).show()
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
            (searchQuery.isEmpty() || app.label.lowercase().contains(searchQuery) || app.packageName.contains(searchQuery))
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
