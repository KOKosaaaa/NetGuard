package com.smarttools.netguard.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentSettingsBinding
import com.smarttools.netguard.model.PerAppMode
import com.smarttools.netguard.model.RoutingMode
import com.smarttools.netguard.model.ThemeMode
import com.smarttools.netguard.util.SecuritySelfTest
import com.smarttools.netguard.viewmodel.SettingsViewModel
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    companion object {
        private val THEME_MODES = arrayOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.OLED, ThemeMode.OCEAN)

        private val LANGUAGE_CODES = arrayOf(
            "system", "en", "ru", "de", "zh-CN", "ja", "hi", "tr",
            "ar", "es", "fr", "pt", "ko", "in", "vi", "th", "it"
        )
        private val LANGUAGE_NAMES = arrayOf(
            "System default", "English", "Русский", "Deutsch",
            "中文 (简体)", "日本語", "हिन्दी", "Türkçe",
            "العربية", "Español", "Français", "Português",
            "한국어", "Bahasa Indonesia", "Tiếng Việt", "ไทย", "Italiano"
        )
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by activityViewModels()

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportToUri(requireContext(), it) }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importFromUri(requireContext(), it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTheme()
        setupSettings()
        setupLanguage()
        setupSecurityTest()
        setupExportImport()
        setupKillSwitch()
        setupPerApp()
        setupBackupRestore()
        setupAbout()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settings.collect { s ->
                        binding.btnTheme.text = when (s.themeMode) {
                            ThemeMode.DARK -> getString(R.string.theme_dark)
                            ThemeMode.LIGHT -> getString(R.string.theme_light)
                            ThemeMode.OLED -> getString(R.string.theme_oled)
                            ThemeMode.OCEAN -> getString(R.string.theme_ocean)
                        }
                        binding.rgRouting.check(
                            when (s.routingMode) {
                                RoutingMode.GLOBAL_PROXY -> R.id.rb_global
                                RoutingMode.RULE_BASED -> R.id.rb_rules
                                RoutingMode.DIRECT -> R.id.rb_direct
                            }
                        )
                        binding.etPrimaryDns.setText(s.primaryDns)
                        binding.etSecondaryDns.setText(s.secondaryDns)
                        binding.cbDoh.isChecked = s.dohEnabled
                        binding.cbBypassLan.isChecked = s.bypassLan
                        binding.cbIpv6.isChecked = s.enableIpv6
                        binding.cbSpeedNotification.isChecked = s.showSpeedInNotification
                        val langIdx = LANGUAGE_CODES.indexOf(s.language).coerceAtLeast(0)
                        binding.btnLanguage.text = LANGUAGE_NAMES[langIdx]
                        binding.rgPerAppMode.check(
                            when (s.perAppMode) {
                                PerAppMode.DISABLED -> R.id.rb_per_app_disabled
                                PerAppMode.WHITELIST -> R.id.rb_per_app_whitelist
                                PerAppMode.BLACKLIST -> R.id.rb_per_app_blacklist
                            }
                        )
                    }
                }
                launch {
                    viewModel.securityResults.collect { results ->
                        if (results.isNotEmpty()) {
                            displaySecurityResults(results)
                        }
                    }
                }
                launch {
                    viewModel.securityTesting.collect { testing ->
                        binding.btnSecurityTest.isEnabled = !testing
                        binding.progressSecurity.visibility = if (testing) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.exportResult.collect { json ->
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("config", json))
                        Toast.makeText(requireContext(), R.string.exported, Toast.LENGTH_SHORT).show()
                        // Auto-clear clipboard after 30 seconds
                        binding.root.postDelayed({
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                    clipboard.clearPrimaryClip()
                                } else {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                                }
                            } catch (_: Exception) {}
                        }, 30_000)
                    }
                }
                launch {
                    viewModel.importResult.collect { result ->
                        result.fold(
                            onSuccess = { count ->
                                Toast.makeText(requireContext(), "Imported $count profiles", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { e ->
                                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun setupTheme() {
        binding.btnTheme.setOnClickListener {
            val names = arrayOf(
                getString(R.string.theme_dark),
                getString(R.string.theme_light),
                getString(R.string.theme_oled),
                getString(R.string.theme_ocean)
            )
            val current = THEME_MODES.indexOf(viewModel.settings.value.themeMode).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.theme)
                .setSingleChoiceItems(names, current) { dialog, which ->
                    val mode = THEME_MODES[which]
                    viewModel.updateSettings { s -> s.copy(themeMode = mode) }
                    dialog.dismiss()
                    requireActivity().recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupSettings() {
        binding.rgRouting.setOnCheckedChangeListener { _, checkedId ->
            viewModel.updateSettings { s ->
                s.copy(
                    routingMode = when (checkedId) {
                        R.id.rb_global -> RoutingMode.GLOBAL_PROXY
                        R.id.rb_rules -> RoutingMode.RULE_BASED
                        R.id.rb_direct -> RoutingMode.DIRECT
                        else -> s.routingMode
                    }
                )
            }
        }

        binding.btnSaveDns.setOnClickListener {
            val primary = binding.etPrimaryDns.text.toString().trim().ifEmpty { "1.1.1.1" }
            val secondary = binding.etSecondaryDns.text.toString().trim().ifEmpty { "8.8.8.8" }

            if (!isValidDns(primary) || !isValidDns(secondary)) {
                Toast.makeText(requireContext(), R.string.invalid_dns, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.updateSettings { s ->
                s.copy(
                    primaryDns = primary,
                    secondaryDns = secondary,
                    dohEnabled = binding.cbDoh.isChecked,
                    bypassLan = binding.cbBypassLan.isChecked,
                    enableIpv6 = binding.cbIpv6.isChecked,
                    showSpeedInNotification = binding.cbSpeedNotification.isChecked,
                )
            }
            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isValidDns(dns: String): Boolean {
        // IPv4 address
        val ipv4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        if (ipv4.matches(dns)) {
            val parts = dns.split(".")
            if (parts.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return false
            // Block private/loopback ranges
            if (dns.startsWith("127.") || dns.startsWith("10.") ||
                dns.startsWith("192.168.") || dns.startsWith("169.254.") ||
                dns.startsWith("0.") || dns == "255.255.255.255"
            ) return false
            if (dns.startsWith("172.")) {
                val second = parts[1].toIntOrNull() ?: return false
                if (second in 16..31) return false
            }
            return true
        }
        if (dns == "localhost") return false
        // Domain name (for DoH like 1dot1dot1dot1.cloudflare-dns.com)
        val domain = Regex("^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$")
        return domain.matches(dns) && dns.length <= 253
    }

    private fun setupLanguage() {
        binding.btnLanguage.setOnClickListener {
            val currentLang = viewModel.settings.value.language
            val checkedItem = LANGUAGE_CODES.indexOf(currentLang).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.language)
                .setSingleChoiceItems(LANGUAGE_NAMES, checkedItem) { dialog, which ->
                    val code = LANGUAGE_CODES[which]
                    viewModel.updateSettings { s -> s.copy(language = code) }
                    val locales = if (code == "system") {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(code)
                    }
                    AppCompatDelegate.setApplicationLocales(locales)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupSecurityTest() {
        binding.btnSecurityTest.setOnClickListener {
            viewModel.runSecurityTest()
        }
    }

    private fun displaySecurityResults(results: List<SecuritySelfTest.TestResult>) {
        binding.llSecurityResults.removeAllViews()
        binding.llSecurityResults.visibility = View.VISIBLE

        for (result in results) {
            val tv = android.widget.TextView(requireContext()).apply {
                val icon = when {
                    result.passed && !result.warning -> "\u2713"
                    result.warning -> "\u26A0"
                    else -> "\u2717"
                }
                val color = when {
                    result.passed && !result.warning -> ContextCompat.getColor(context, R.color.test_pass)
                    result.warning -> ContextCompat.getColor(context, R.color.test_warn)
                    else -> ContextCompat.getColor(context, R.color.test_fail)
                }
                text = "$icon ${result.testName}: ${result.details}"
                setTextColor(color)
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            binding.llSecurityResults.addView(tv)
        }
    }

    private fun setupKillSwitch() {
        binding.btnKillSwitch.setOnClickListener {
            try {
                val intent = android.content.Intent("android.net.vpn.SETTINGS")
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Open Settings > Network > VPN manually", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupPerApp() {
        binding.rgPerAppMode.setOnCheckedChangeListener { _, checkedId ->
            viewModel.updateSettings { s ->
                s.copy(perAppMode = when (checkedId) {
                    R.id.rb_per_app_whitelist -> PerAppMode.WHITELIST
                    R.id.rb_per_app_blacklist -> PerAppMode.BLACKLIST
                    else -> PerAppMode.DISABLED
                })
            }
        }

        binding.btnSelectApps.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_per_app)
        }
    }

    private fun setupExportImport() {
        binding.btnExport.setOnClickListener {
            // Warn user that clipboard is accessible to other apps
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.export_config)
                .setMessage(R.string.export_warning)
                .setPositiveButton(R.string.export_config) { _, _ ->
                    viewModel.exportConfig()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnImport.setOnClickListener {
            val input = EditText(requireContext()).apply {
                hint = "Paste JSON config"
                minLines = 3
                setPadding(48, 32, 48, 16)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_config)
                .setView(input)
                .setPositiveButton(R.string.import_btn) { _, _ ->
                    viewModel.importConfig(input.text.toString())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupAbout() {
        binding.tvCreatedBy.setOnClickListener {
            val url = getString(R.string.github_url)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        }
    }

    private fun setupBackupRestore() {
        binding.btnBackupFile.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.backup_to_file)
                .setMessage(R.string.backup_warning)
                .setPositiveButton(R.string.backup_to_file) { _, _ ->
                    val filename = "netguard_backup_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json"
                    backupLauncher.launch(filename)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnRestoreFile.setOnClickListener {
            restoreLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    override fun onDestroyView() {
        _binding?.root?.handler?.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }
}
