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

    fun updateSettings(updater: (AppSettings) -> AppSettings) {
        val newSettings = updater(_settings.value)
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
            // Strip perAppList from export — contains installed package names (privacy)
            val safeSettings = _settings.value.copy(perAppList = emptySet())
            val exportData = mapOf(
                "profiles" to profiles.map { it.toUri() },
                "subscriptions" to subs.map { mapOf("name" to it.name, "url" to it.url) },
                "settings" to safeSettings
            )
            _exportResult.emit(Gson().toJson(exportData))
        }
    }

    fun importConfig(json: String) {
        viewModelScope.launch {
            try {
                if (json.isBlank()) {
                    _importResult.emit(Result.failure(Exception("Empty JSON")))
                    return@launch
                }
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                var count = 0

                // Import profiles
                val profilesArray = root.getAsJsonArray("profiles")
                if (profilesArray != null && profilesArray.size() > 0) {
                    val uris = (0 until profilesArray.size()).mapNotNull {
                        profilesArray[it]?.asString
                    }
                    val result = com.smarttools.netguard.core.ProfileParser.parseMultiline(
                        uris.joinToString("\n")
                    )
                    app.profileRepository.insertAll(result.profiles)
                    count += result.profiles.size
                }

                // Import subscriptions
                val subsArray = root.getAsJsonArray("subscriptions")
                if (subsArray != null && subsArray.size() > 0) {
                    for (i in 0 until subsArray.size()) {
                        val subObj = subsArray[i]?.asJsonObject ?: continue
                        val name = subObj.get("name")?.asString ?: continue
                        val url = subObj.get("url")?.asString ?: continue
                        if (url.isNotBlank()) {
                            app.subscriptionRepository.insert(
                                com.smarttools.netguard.model.Subscription(name = name, url = url)
                            )
                        }
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
                val safeSettings = _settings.value.copy(perAppList = emptySet())
                val exportData = mapOf(
                    "version" to 1,
                    "profiles" to profiles.map { it.toUri() },
                    "subscriptions" to subs.map { mapOf("name" to it.name, "url" to it.url) },
                    "settings" to safeSettings
                )
                val json = Gson().toJson(exportData)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                _exportResult.emit(context.getString(R.string.backup_success))
            } catch (e: Exception) {
                _exportResult.emit("Error: ${e.message}")
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
