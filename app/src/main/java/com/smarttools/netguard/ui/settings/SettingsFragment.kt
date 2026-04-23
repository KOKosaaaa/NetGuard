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
import com.smarttools.netguard.App
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentSettingsBinding
import com.smarttools.netguard.model.PerAppMode
import com.smarttools.netguard.service.WifiAutoConnectManager
import com.smarttools.netguard.model.RoutingMode
import com.smarttools.netguard.model.ThemeMode
import com.smarttools.netguard.model.TrafficStatsMode
import com.smarttools.netguard.util.SecuritySelfTest
import com.smarttools.netguard.viewmodel.SettingsViewModel
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    companion object {
        private val THEME_MODES = arrayOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.OLED, ThemeMode.OCEAN, ThemeMode.DYNAMIC)

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

        private val TLS_PACKETS_VALID = setOf("tlshello", "http")
        private val RANGE_REGEX = Regex("^\\d+-\\d+$")
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by activityViewModels()
    private var updatingFromFlow = false

    // Remembers what each text field was populated with, so onPause can tell
    // "user edited this" apart from "settings changed under us (e.g. restore
    // from backup) and the field never got refreshed" — only the former
    // should be written back. Prevents stale onPause from clobbering settings
    // that were updated externally while the fragment was foreground.
    private val fieldBaselines = mutableMapOf<Int, String>()

    private fun EditText.setTextAndBaseline(value: String) {
        setText(value)
        fieldBaselines[id] = value
    }

    private fun EditText.hasUserEdit(): Boolean {
        val baseline = fieldBaselines[id] ?: return false
        return text.toString() != baseline
    }

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
        setupFeatureToggles()
        setupTrafficStatsMode()
        setupAutoConnectWifi()
        setupTlsFragment()
        setupBypassList()
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
                        updatingFromFlow = true
                        binding.btnTheme.text = when (s.themeMode) {
                            ThemeMode.DARK -> getString(R.string.theme_dark)
                            ThemeMode.LIGHT -> getString(R.string.theme_light)
                            ThemeMode.OLED -> getString(R.string.theme_oled)
                            ThemeMode.OCEAN -> getString(R.string.theme_ocean)
                            ThemeMode.DYNAMIC -> getString(R.string.theme_dynamic)
                        }
                        binding.rgRouting.check(
                            when (s.routingMode) {
                                RoutingMode.GLOBAL_PROXY -> R.id.rb_global
                                RoutingMode.RULE_BASED -> R.id.rb_rules
                                RoutingMode.DIRECT -> R.id.rb_direct
                            }
                        )
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
                        updatingFromFlow = false
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
            val names = mutableListOf(
                getString(R.string.theme_dark),
                getString(R.string.theme_light),
                getString(R.string.theme_oled),
                getString(R.string.theme_ocean)
            )
            val modes = THEME_MODES.toMutableList()
            // Dynamic colors only on Android 12+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                names.add(getString(R.string.theme_dynamic))
            } else {
                modes.remove(ThemeMode.DYNAMIC)
            }
            val current = modes.indexOf(viewModel.settings.value.themeMode).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.theme)
                .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                    val mode = modes[which]
                    viewModel.updateSettings { s -> s.copy(themeMode = mode) }
                    dialog.dismiss()
                    requireActivity().recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupSettings() {
        // Initialize DNS fields
        val s = viewModel.settings.value
        binding.etPrimaryDns.setTextAndBaseline(s.primaryDns)
        binding.etSecondaryDns.setTextAndBaseline(s.secondaryDns)

        binding.rgRouting.setOnCheckedChangeListener { _, checkedId ->
            if (!updatingFromFlow) {
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
        }

        // Checkboxes save immediately
        binding.cbDoh.setOnCheckedChangeListener { _, checked ->
            if (!updatingFromFlow) viewModel.updateSettings { it.copy(dohEnabled = checked) }
        }
        binding.cbBypassLan.setOnCheckedChangeListener { _, checked ->
            if (!updatingFromFlow) viewModel.updateSettings { it.copy(bypassLan = checked) }
        }
        binding.cbIpv6.setOnCheckedChangeListener { _, checked ->
            if (!updatingFromFlow) viewModel.updateSettings { it.copy(enableIpv6 = checked) }
        }
        binding.cbSpeedNotification.setOnCheckedChangeListener { _, checked ->
            if (!updatingFromFlow) viewModel.updateSettings { it.copy(showSpeedInNotification = checked) }
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

    private fun setupFeatureToggles() {
        val s = viewModel.settings.value
        binding.cbConnectionMap.isChecked = s.showConnectionMap
        binding.cbSpeedTest.isChecked = s.showSpeedTest

        binding.cbConnectionMap.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSettings { it.copy(showConnectionMap = checked) }
        }
        binding.cbSpeedTest.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSettings { it.copy(showSpeedTest = checked) }
        }
    }

    private fun setupTrafficStatsMode() {
        val s = viewModel.settings.value
        updateTrafficStatsButtonText(s.trafficStatsMode)

        binding.btnTrafficStatsMode.setOnClickListener {
            val modes = TrafficStatsMode.entries.toTypedArray()
            val names = arrayOf(
                getString(R.string.traffic_stats_chart),
                getString(R.string.traffic_stats_simple),
                getString(R.string.traffic_stats_hidden)
            )
            val current = modes.indexOf(viewModel.settings.value.trafficStatsMode).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.traffic_stats)
                .setSingleChoiceItems(names, current) { dialog, which ->
                    val mode = modes[which]
                    viewModel.updateSettings { it.copy(trafficStatsMode = mode) }
                    updateTrafficStatsButtonText(mode)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateTrafficStatsButtonText(mode: TrafficStatsMode) {
        binding.btnTrafficStatsMode.text = when (mode) {
            TrafficStatsMode.CHART -> "${getString(R.string.traffic_stats)}: ${getString(R.string.traffic_stats_chart)}"
            TrafficStatsMode.SIMPLE -> "${getString(R.string.traffic_stats)}: ${getString(R.string.traffic_stats_simple)}"
            TrafficStatsMode.HIDDEN -> "${getString(R.string.traffic_stats)}: ${getString(R.string.traffic_stats_hidden)}"
        }
    }

    private fun setupAutoConnectWifi() {
        val s = viewModel.settings.value
        binding.cbAutoConnectWifi.isChecked = s.autoConnectWifi

        binding.cbAutoConnectWifi.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val missing = missingWifiPermissions()
                if (missing.isNotEmpty()) {
                    wifiPermissionLauncher.launch(missing)
                    return@setOnCheckedChangeListener
                }
            }
            viewModel.updateSettings { it.copy(autoConnectWifi = checked) }
            (requireActivity().application as App).updateWifiAutoConnect(checked)
        }

        binding.btnTrustedWifi.setOnClickListener {
            val missing = missingWifiPermissions()
            if (missing.isNotEmpty()) {
                // The Trusted-WiFi dialog is useless without the permissions
                // required to read the current SSID/BSSID, so request them
                // first and re-open the dialog when the user decides.
                trustedWifiPermissionLauncher.launch(missing)
            } else {
                showTrustedWifiDialog()
            }
        }
    }

    /**
     * Permissions we need before we can read the current SSID/BSSID:
     *  * ACCESS_FINE_LOCATION — required on all versions.
     *  * NEARBY_WIFI_DEVICES — required additionally on Android 13+
     *    (SDK 33+); without it, `WifiInfo.ssid` comes back as
     *    `<unknown ssid>` even though ACCESS_FINE_LOCATION is granted.
     */
    private fun missingWifiPermissions(): Array<String> {
        val ctx = requireContext()
        val missing = mutableListOf<String>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            missing += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            missing += android.Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return missing.toTypedArray()
    }

    private val wifiPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.updateSettings { it.copy(autoConnectWifi = true) }
            (requireActivity().application as App).updateWifiAutoConnect(true)
        } else {
            binding.cbAutoConnectWifi.isChecked = false
            Toast.makeText(requireContext(), R.string.location_needed_for_wifi, Toast.LENGTH_LONG).show()
        }
    }

    private val trustedWifiPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            showTrustedWifiDialog()
        } else {
            Toast.makeText(requireContext(), R.string.location_needed_for_wifi, Toast.LENGTH_LONG).show()
        }
    }

    private fun showTrustedWifiDialog() {
        val app = requireActivity().application as App
        val trusted = viewModel.settings.value.trustedWifiList.toMutableList()
        // Read SSID/BSSID even when the auto-connect feature is disabled:
        // the stored manager is only spun up on `autoConnectWifi = true`, but
        // a user who is about to build a trusted list should be able to see
        // and add the currently-connected WiFi before flipping the switch.
        val probe = app.wifiAutoConnectManager ?: WifiAutoConnectManager(app)
        val currentSsid = probe.getCurrentSsid()
        val currentBssid = probe.getCurrentBssid()

        // Display names for existing entries
        val displayItems = trusted.map { WifiAutoConnectManager.displayName(it) }.toMutableList()

        // Check if current WiFi is already trusted (match by SSID+BSSID)
        val currentEntry = if (currentSsid != null && currentBssid != null) {
            WifiAutoConnectManager.encode(currentSsid, currentBssid)
        } else null
        val hasCurrentWifi = currentEntry != null && currentEntry !in trusted

        if (hasCurrentWifi && currentSsid != null) {
            displayItems.add(0, "+ ${getString(R.string.add_current_wifi)} ($currentSsid)")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trusted_wifi)
            .setItems(displayItems.toTypedArray()) { _, which ->
                if (hasCurrentWifi && which == 0) {
                    // Add current WiFi with SSID+BSSID
                    val updated = trusted + currentEntry!!
                    viewModel.updateSettings { it.copy(trustedWifiList = updated.toSet()) }
                    Toast.makeText(requireContext(), "$currentSsid ${getString(R.string.added)}", Toast.LENGTH_SHORT).show()
                } else {
                    // Remove tapped item
                    val idx = if (hasCurrentWifi) which - 1 else which
                    val removed = trusted[idx]
                    trusted.removeAt(idx)
                    viewModel.updateSettings { it.copy(trustedWifiList = trusted.toSet()) }
                    val removedName = WifiAutoConnectManager.ssidOf(removed)
                    Toast.makeText(requireContext(), "$removedName ${getString(R.string.removed)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupTlsFragment() {
        val s = viewModel.settings.value
        binding.cbTlsFragment.isChecked = s.tlsFragmentEnabled
        binding.llTlsFragmentSettings.visibility = if (s.tlsFragmentEnabled) View.VISIBLE else View.GONE
        binding.etTlsPackets.setTextAndBaseline(s.tlsFragmentPackets)
        binding.etTlsLength.setTextAndBaseline(s.tlsFragmentLength)
        binding.etTlsInterval.setTextAndBaseline(s.tlsFragmentInterval)

        binding.cbTlsFragment.setOnCheckedChangeListener { _, checked ->
            binding.llTlsFragmentSettings.visibility = if (checked) View.VISIBLE else View.GONE
            viewModel.updateSettings { it.copy(tlsFragmentEnabled = checked) }
        }
    }

    private fun setupBypassList() {
        val s = viewModel.settings.value
        binding.etBypassDomains.setTextAndBaseline(s.bypassDomains)
        binding.etBypassIps.setTextAndBaseline(s.bypassIps)

        binding.etBypassDomains.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, s: Int, b: Int, a: Int) {}
            override fun onTextChanged(c: CharSequence?, s: Int, b: Int, a: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                binding.tilBypassDomains.error =
                    findBroadBypassPattern(e?.toString().orEmpty(), isIpList = false)
                        ?.let { getString(R.string.bypass_too_broad_warning, it) }
            }
        })
        binding.etBypassIps.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, s: Int, b: Int, a: Int) {}
            override fun onTextChanged(c: CharSequence?, s: Int, b: Int, a: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                binding.tilBypassIps.error =
                    findBroadBypassPattern(e?.toString().orEmpty(), isIpList = true)
                        ?.let { getString(R.string.bypass_too_broad_warning, it) }
            }
        })
    }

    /**
     * Returns the first line that would cause the vast majority of traffic to
     * bypass the VPN, or null if all lines look normal. Conservative — we only
     * flag patterns that are almost certainly a mistake, not arbitrary broad
     * rules the user might actually want.
     */
    private fun findBroadBypassPattern(text: String, isIpList: Boolean): String? {
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (isIpList) {
                // Catch /0 and /1 CIDRs (half the internet or more)
                if (line == "0.0.0.0/0" || line == "::/0") return line
                val slash = line.lastIndexOf('/')
                if (slash > 0) {
                    val prefix = line.substring(slash + 1).toIntOrNull()
                    if (prefix != null && prefix <= 1) return line
                }
                if (line.equals("geoip:all", ignoreCase = true)) return line
            } else {
                if (line == "*" || line == "." || line == "..") return line
                // Regex that matches everything
                if (line.startsWith("regexp:", ignoreCase = true)) {
                    val pat = line.substringAfter(":").trim()
                    if (pat == ".*" || pat == ".+" || pat == "." || pat == "^.*$" || pat == ".*\$") return line
                }
                // Xray substring match with no dot and ≤3 chars — e.g. "ru", "com" — hits millions of domains
                val stripped = line.substringAfter("domain:").substringAfter("full:")
                if (!stripped.contains('.') && !stripped.startsWith("geosite:") && stripped.length <= 3) return line
            }
        }
        return null
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
            if (!updatingFromFlow) {
                viewModel.updateSettings { s ->
                    s.copy(perAppMode = when (checkedId) {
                        R.id.rb_per_app_whitelist -> PerAppMode.WHITELIST
                        R.id.rb_per_app_blacklist -> PerAppMode.BLACKLIST
                        else -> PerAppMode.DISABLED
                    })
                }
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

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            saveTextFields()
        }
    }

    private fun saveTextFields() {
        // Re-read current settings snapshot — external updates (e.g. restore from
        // backup) may have changed settings since the fragment opened. For each
        // text field, only apply the EditText value if the user actually edited
        // it (dirty vs baseline); otherwise fall through to the latest settings.
        val current = viewModel.settings.value
        val rejected = mutableListOf<String>()

        val primary = if (binding.etPrimaryDns.hasUserEdit()) {
            val raw = binding.etPrimaryDns.text.toString().trim().ifEmpty { "1.1.1.1" }
            if (isValidDns(raw)) raw else { rejected.add("DNS1"); current.primaryDns }
        } else current.primaryDns

        val secondary = if (binding.etSecondaryDns.hasUserEdit()) {
            val raw = binding.etSecondaryDns.text.toString().trim().ifEmpty { "8.8.8.8" }
            if (isValidDns(raw)) raw else { rejected.add("DNS2"); current.secondaryDns }
        } else current.secondaryDns

        val packets = if (binding.etTlsPackets.hasUserEdit()) {
            val raw = binding.etTlsPackets.text.toString().trim().ifEmpty { "tlshello" }
            if (raw in TLS_PACKETS_VALID) raw else { rejected.add("TLS packets"); current.tlsFragmentPackets }
        } else current.tlsFragmentPackets

        val length = if (binding.etTlsLength.hasUserEdit()) {
            val raw = binding.etTlsLength.text.toString().trim().ifEmpty { "100-200" }
            if (RANGE_REGEX.matches(raw)) raw else { rejected.add("TLS length"); current.tlsFragmentLength }
        } else current.tlsFragmentLength

        val interval = if (binding.etTlsInterval.hasUserEdit()) {
            val raw = binding.etTlsInterval.text.toString().trim().ifEmpty { "10-20" }
            if (RANGE_REGEX.matches(raw)) raw else { rejected.add("TLS interval"); current.tlsFragmentInterval }
        } else current.tlsFragmentInterval

        val domains = if (binding.etBypassDomains.hasUserEdit())
            binding.etBypassDomains.text.toString() else current.bypassDomains
        val ips = if (binding.etBypassIps.hasUserEdit())
            binding.etBypassIps.text.toString() else current.bypassIps

        val broadBypass = findBroadBypassPattern(domains, isIpList = false)
            ?: findBroadBypassPattern(ips, isIpList = true)

        viewModel.updateSettings {
            it.copy(
                primaryDns = primary,
                secondaryDns = secondary,
                tlsFragmentPackets = packets,
                tlsFragmentLength = length,
                tlsFragmentInterval = interval,
                bypassDomains = domains,
                bypassIps = ips
            )
        }

        if (broadBypass != null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.bypass_too_broad_toast),
                Toast.LENGTH_LONG
            ).show()
        }

        if (rejected.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.invalid_values_rejected, rejected.joinToString(", ")),
                Toast.LENGTH_LONG
            ).show()
        }
    }


    override fun onDestroyView() {
        _binding?.root?.handler?.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }
}
