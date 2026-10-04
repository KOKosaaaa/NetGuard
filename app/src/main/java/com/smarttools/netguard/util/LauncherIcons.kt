package com.smarttools.netguard.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.smarttools.netguard.R
import com.smarttools.netguard.model.ThemeMode

/** Only launcher aliases change; MainActivity and the VPN service stay enabled. */
object LauncherIcons {
    private val names = mapOf(
        ThemeMode.DARK to "Default",
        ThemeMode.LIGHT to "Light",
        ThemeMode.OLED to "Oled",
        ThemeMode.OCEAN to "Ocean",
        ThemeMode.FSOCIETY to "Fsociety",
        ThemeMode.DYNAMIC to "Dynamic"
    )

    fun iconFor(theme: ThemeMode): Int = when (theme) {
        ThemeMode.DARK -> R.mipmap.ic_launcher
        ThemeMode.LIGHT -> R.mipmap.ic_launcher_light
        ThemeMode.OLED -> R.mipmap.ic_launcher_oled
        ThemeMode.OCEAN -> R.mipmap.ic_launcher_ocean
        ThemeMode.FSOCIETY -> R.mipmap.ic_launcher_fsociety
        ThemeMode.DYNAMIC -> R.mipmap.ic_launcher_dynamic
    }

    @Synchronized
    fun sync(context: Context, theme: ThemeMode) {
        val pm = context.packageManager
        val target = names.getValue(theme)
        val changes = names.values.mapNotNull { name ->
            val component = ComponentName(context.packageName, "${context.packageName}.launcher.$name")
            val desired = if (name == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val explicit = pm.getComponentEnabledSetting(component)
            // Default is the only alias initially enabled in the manifest.
            val enabled = explicit == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (explicit == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && name == "Default")
            if (enabled == (name == target)) null else component to desired
        }
        if (changes.isEmpty()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.setComponentEnabledSettings(changes.map { (component, state) ->
                    PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                })
            } else {
                // Older Android has no atomic batch: publish the new icon first.
                changes.sortedBy { (_, state) -> state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                    .forEach { (component, state) ->
                        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                    }
            }
        } catch (e: RuntimeException) {
            // An OEM launcher refresh must never crash or stop an active VPN.
            Log.w("LauncherIcons", "Could not update launcher icon", e)
        }
    }
}
