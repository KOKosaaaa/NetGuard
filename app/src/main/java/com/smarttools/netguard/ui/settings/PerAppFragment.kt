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
