package com.smarttools.netguard.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.smarttools.netguard.App
import com.smarttools.netguard.R
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.util.SecuritySelfTest
import com.smarttools.netguard.util.ConfigBackupSubscriptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App

    private val _settings = MutableStateFlow(app.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _securityResults = MutableStateFlow<List<SecuritySelfTest.TestResult>>(emptyList())
    val securityResults: StateFlow<List<SecuritySelfTest.TestResult>> = _securityResults.asStateFlow()

    private val _securityTesting = MutableStateFlow(false)
    val securityTesting: StateFlow<Boolean> = _securityTesting.asStateFlow()

    private val _exportResult = MutableSharedFlow<String>()
    val exportResult: SharedFlow<String> = _exportResult.asSharedFlow()

    private val _importResult = MutableSharedFlow<Result<Int>>()
    val importResult: SharedFlow<Result<Int>> = _importResult.asSharedFlow()

    /** Persisted scroll position for SettingsFragment so it survives sub-screen navigation. */
    var settingsScrollY: Int = 0

    fun updateSettings(updater: (AppSettings) -> AppSettings) {
        val newSettings = updater(_settings.value)
        // Merely leaving Settings to read About must not persist all defaults.
        if (newSettings == _settings.value) return
        _settings.value = newSettings
        app.saveSettings(newSettings)
    }

    fun runSecurityTest() {
        viewModelScope.launch {
            _securityTesting.value = true
            _securityResults.value = SecuritySelfTest.runAllTests(getApplication())
            _securityTesting.value = false
        }
    }

    fun exportConfig() {
        viewModelScope.launch {
            val profiles = app.profileRepository.getAll()
            val subs = app.subscriptionRepository.getAll()
            val safeSettings = _settings.value.copy(perAppList = emptySet(), alwaysVpnApps = emptySet())
            val exportData = ConfigBackupSubscriptions.export(
                profiles.map { it.toUri() to it.subscriptionId },
                subs.map { ConfigBackupSubscriptions.Subscription(it.id, it.name, it.url) },
                com.smarttools.netguard.util.LauncherIconBackup.forExport(safeSettings)
            )
            _exportResult.emit(Gson().toJson(exportData))
        }
    }

    fun importConfig(json: String) {
        viewModelScope.launch {
            try {
                if (json.isBlank()) {
                    _importResult.emit(Result.failure(Exception(com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.backup_empty_json))))
                    return@launch
                }
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                var count = 0

                // Parse all reference keys before any insert, so malformed/duplicate v3
                // keys cannot create a partially imported subscription catalog.
                val importedSubscriptions = ConfigBackupSubscriptions.subscriptions(root)
                val subKeyToId = mutableMapOf<String, Long>()
                val subNameToId = mutableMapOf<String, Long>()
                for (sub in importedSubscriptions) {
                    if (sub.url.isBlank()) continue
                    try {
                        app.subscriptionRepository.validateUrl(sub.url)
                    } catch (e: Exception) {
                        android.util.Log.w("ImportConfig", "Skipping subscription with invalid URL: ${e.message}")
                        continue
                    }
                    val id = app.subscriptionRepository.insert(
                        com.smarttools.netguard.model.Subscription(
                            name = sub.name.take(256).ifBlank { "Subscription" }, url = sub.url
                        )
                    )
                    sub.key?.let { subKeyToId[it] = id }
                    subNameToId[sub.name] = id // compatibility with legacy v2 backups
                }
                for ((subId, uris) in ConfigBackupSubscriptions.profileGroups(root, subKeyToId, subNameToId)) {
                    val parsed = com.smarttools.netguard.core.ProfileParser.parseMultiline(uris.joinToString("\n"))
                    val linked = if (subId != 0L) parsed.profiles.map { it.copy(subscriptionId = subId) }
                        else parsed.profiles
                    app.profileRepository.insertAll(linked)
                    count += linked.size
                }

                // 3. Restore settings. exportConfig writes a "settings"
                //    object, but without this block a restore silently dropped
                //    every preference (routing/DNS/theme/trigger/expert...).
                //    perAppList is stripped on export for privacy, so keep the
                //    device's current one instead of wiping it to empty.
                val settingsEl = root.get("settings")
                if (settingsEl != null && settingsEl.isJsonObject) {
                    try {
                        // Gson fills via Unsafe and IGNORES Kotlin default values,
                        // so a partial/foreign JSON would leave non-null fields
                        // null. Merge at the JSON level instead: start from the
                        // CURRENT settings (every field present), overlay only the
                        // keys the backup actually contains, then deserialize the
                        // complete object — no field can come out null.
                        val gson = Gson()
                        val base = gson.toJsonTree(_settings.value).asJsonObject
                        for ((k, v) in settingsEl.asJsonObject.entrySet()) {
                            base.add(k, if (k == "launcherIconTheme")
                                com.smarttools.netguard.util.LauncherIconBackup.restoreValue(v) else v)
                        }
                        val merged = gson.fromJson(base, AppSettings::class.java)
                            .copy(perAppList = _settings.value.perAppList, alwaysVpnApps = _settings.value.alwaysVpnApps)
                        app.saveSettings(merged)
                        _settings.value = merged
                    } catch (e: Exception) {
                        android.util.Log.w("ImportConfig", "Skipping malformed settings block: ${e.message}")
                    }
                }

                _importResult.emit(Result.success(count))
            } catch (e: Exception) {
                _importResult.emit(Result.failure(e))
            }
        }
    }

    fun exportToUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val profiles = app.profileRepository.getAll()
                val subs = app.subscriptionRepository.getAll()
                val safeSettings = _settings.value.copy(perAppList = emptySet(), alwaysVpnApps = emptySet())
                val exportData = ConfigBackupSubscriptions.export(
                    profiles.map { it.toUri() to it.subscriptionId },
                    subs.map { ConfigBackupSubscriptions.Subscription(it.id, it.name, it.url) },
                    com.smarttools.netguard.util.LauncherIconBackup.forExport(safeSettings)
                )
                val json = Gson().toJson(exportData)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                _exportResult.emit(context.getString(R.string.backup_success))
            } catch (e: Exception) {
                _exportResult.emit(com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.error_with_details, e.message.orEmpty()))
            }
        }
    }

    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val maxBytes = 2 * 1024 * 1024
                val json = context.contentResolver.openInputStream(uri)?.use { inp ->
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    var totalRead = 0
                    var bytesRead = inp.read(buffer)
                    while (bytesRead != -1) {
                        totalRead += bytesRead
                        if (totalRead > maxBytes) throw Exception("File too large (max 2MB)")
                        output.write(buffer, 0, bytesRead)
                        bytesRead = inp.read(buffer)
                    }
                    output.toString(Charsets.UTF_8.name())
                } ?: throw Exception("Cannot read file")

                // Delegate to importConfig which handles profiles + subscriptions
                importConfig(json)
            } catch (e: Exception) {
                _importResult.emit(Result.failure(e))
            }
        }
    }
}
