package com.smarttools.netguard.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.os.Build
import android.app.LocaleManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/** ViewModels and workers must use the app language, even on Android versions
 * where AppCompat applies its locale only to Activity contexts. */
object LocalizedResources {
    fun context(base: Context): Context {
        val language = base.getSharedPreferences("netguard_prefs", Context.MODE_PRIVATE)
            .getString("language", "system") ?: "system"
        val applicationLocales = if (Build.VERSION.SDK_INT >= 33)
            base.getSystemService(LocaleManager::class.java).applicationLocales
            else LocaleList.forLanguageTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())
        val locales = if (language != "system") LocaleList.forLanguageTags(if (language == "in") "id" else language)
            else if (!applicationLocales.isEmpty) applicationLocales
            else Resources.getSystem().configuration.locales
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(locales)
        return base.createConfigurationContext(configuration)
    }

    fun string(base: Context, @StringRes id: Int, vararg args: Any): String =
        context(base).getString(id, *args)
}
