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

    /** Persisted scroll position for SettingsFragment so it survives sub-screen navigation. */
    var settingsScrollY: Int = 0

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
            val subById = subs.associateBy { it.id }
            // Strip perAppList from export — contains installed package names (privacy)
            val safeSettings = _settings.value.copy(perAppList = emptySet())
            val profilesWithSub = profiles.map { p ->
                val subName = subById[p.subscriptionId]?.name
                if (subName != null) {
                    mapOf("uri" to p.toUri(), "subscription" to subName)
                } else {
                    mapOf("uri" to p.toUri())
                }
            }
            val exportData = mapOf(
                "version" to 2,
                "profiles" to profilesWithSub,
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

                // 1. Import subscriptions FIRST so we know their new ids
                val subNameToId = mutableMapOf<String, Long>()
                val subsArray = root.getAsJsonArray("subscriptions")
                if (subsArray != null && subsArray.size() > 0) {
                    for (i in 0 until subsArray.size()) {
                        val subObj = subsArray[i]?.asJsonObject ?: continue
                        val name = subObj.get("name")?.asString ?: continue
                        val url = subObj.get("url")?.asString ?: continue
                        if (url.isNotBlank()) {
                            try {
                                app.subscriptionRepository.validateUrl(url)
                            } catch (e: Exception) {
                                android.util.Log.w(
                                    "ImportConfig",
                                    "Skipping subscription with invalid URL: ${e.message}"
                                )
                                continue
                            }
                            val safeName = name.take(256).ifBlank { "Subscription" }
                            val id = app.subscriptionRepository.insert(
                                com.smarttools.netguard.model.Subscription(name = safeName, url = url)
                            )
                            subNameToId[name] = id
                        }
                    }
                }

                // 2. Import profiles. Support both v1 (string array) and v2
                //    (objects with {uri, subscription}). v2 lets us re-link
                //    profiles to the subscription they came from so the
                //    Profiles tab stays grouped after restore.
                val profilesArray = root.getAsJsonArray("profiles")
                if (profilesArray != null && profilesArray.size() > 0) {
                    // Group by subscription name (or "" for unattached)
                    val groups = mutableMapOf<String, MutableList<String>>()
                    for (i in 0 until profilesArray.size()) {
                        val el = profilesArray[i] ?: continue
                        val (uri, subName) = if (el.isJsonObject) {
                            val obj = el.asJsonObject
                            val u = obj.get("uri")?.asString ?: continue
                            val s = obj.get("subscription")?.asString ?: ""
                            u to s
                        } else if (el.isJsonPrimitive) {
                            (el.asString ?: continue) to ""
                        } else continue
                        groups.getOrPut(subName) { mutableListOf() }.add(uri)
                    }
                    for ((subName, uris) in groups) {
                        val parsed = com.smarttools.netguard.core.ProfileParser.parseMultiline(
                            uris.joinToString("\n")
                        )
                        val subId = subNameToId[subName] ?: 0L
                        val linked = if (subId != 0L) {
                            parsed.profiles.map { it.copy(subscriptionId = subId) }
                        } else parsed.profiles
                        app.profileRepository.insertAll(linked)
                        count += linked.size
                    }
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
                            base.add(k, v)
                        }
                        val merged = gson.fromJson(base, AppSettings::class.java)
                            .copy(perAppList = _settings.value.perAppList)
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
                val subById = subs.associateBy { it.id }
                val safeSettings = _settings.value.copy(perAppList = emptySet())
                // v2 format: profiles carry their subscription name so import
                // can re-link them after subscriptions are recreated.
                val profilesWithSub = profiles.map { p ->
                    val subName = subById[p.subscriptionId]?.name
                    if (subName != null) {
                        mapOf("uri" to p.toUri(), "subscription" to subName)
                    } else {
                        mapOf("uri" to p.toUri())
                    }
                }
                val exportData = mapOf(
                    "version" to 2,
                    "profiles" to profilesWithSub,
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
