package com.smarttools.netguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.smarttools.netguard.App

/**
 * Listens for `ACTION_PACKAGE_ADDED` broadcasts and, if the user has enabled
 * the *Auto-bypass new RU apps* setting, appends every freshly-installed
 * non-system app whose `packageName` starts with `ru.` to `perAppList`.
 *
 * Without this, a one-shot button press only protects apps that exist at
 * the moment the user taps it; any RU app installed afterwards starts
 * leaking through the tunnel until the user remembers to re-tap.
 *
 * Registered dynamically in [App.onCreate] (rather than statically in the
 * manifest) — Android 8+ disallows static `PACKAGE_ADDED` receivers for
 * regular apps, so we only get the broadcast while our process is alive.
 * Acceptable: the auto-bypass is a hardening, not a guarantee, and a
 * device that is not running NetGuard at install time is also not routing
 * the new app's traffic anywhere yet.
 */
class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        // Replace events fire as PACKAGE_REMOVED + PACKAGE_ADDED with this extra.
        // We do not want to add the package again on every update.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val app = context.applicationContext as? App ?: return
        val settings = app.loadSettings()
        if (!settings.autoBypassRuPackages) return

        val pkg = intent.data?.schemeSpecificPart ?: return
        if (!pkg.startsWith("ru.")) return

        // Skip system apps — system OEM packages occasionally use ru.* labels
        // for region-specific stubs that the user does not interact with.
        try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return
        } catch (_: Exception) {
            return
        }

        if (pkg in settings.perAppList) return
        val updated = settings.perAppList + pkg
        app.saveSettings(settings.copy(perAppList = updated))
        Log.i("PackageInstallReceiver", "Auto-bypass appended: $pkg (perAppList now ${updated.size})")
    }
}
