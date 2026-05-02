package com.smarttools.netguard.ui.onboarding

import android.animation.ObjectAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.smarttools.netguard.App
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.R
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.databinding.ActivityOnboardingBinding
import com.smarttools.netguard.model.PerAppMode
import com.smarttools.netguard.model.ThemeMode
import com.smarttools.netguard.service.TriggerWatcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var step = STEP_LANG
    private var pickedMode = OnboardingMode.TRIGGER
    private var profileImportedCount = 0
    private var transitioning = false
    private var pickedLanguage: String = "system"
    /**
     * Map of (TextView/Button → string resource ID) that we manually
     * re-resolve when the locale changes. We keep this so the activity does
     * not have to be recreated on language pick — recreation produces a
     * visible flash, and configChanges=locale lets us stay alive.
     */
    private lateinit var refreshMap: List<Pair<android.widget.TextView, Int>>

    private enum class OnboardingMode { TRIGGER, GLOBAL, PER_APP, SKIP }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showVpnGranted()
        } else {
            Toast.makeText(this, R.string.onb_vpn_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = (application as App).loadSettings().themeMode
        setTheme(when (theme) {
            ThemeMode.DARK -> R.style.Theme_NetGuard
            ThemeMode.LIGHT -> R.style.Theme_NetGuard_Light
            ThemeMode.OLED -> R.style.Theme_NetGuard_OLED
            ThemeMode.OCEAN -> R.style.Theme_NetGuard_Ocean
            ThemeMode.DYNAMIC -> R.style.Theme_NetGuard_Dynamic
        })
        if (theme == ThemeMode.DYNAMIC) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore wizard state across the activity recreation that
        // setApplicationLocales triggers when the user picks a language.
        pickedLanguage = (application as App).loadSettings().language

        val isRestoration = savedInstanceState != null
        savedInstanceState?.let {
            step = it.getInt(KEY_STEP, STEP_LANG)
            pickedMode = runCatching {
                OnboardingMode.valueOf(it.getString(KEY_MODE) ?: OnboardingMode.TRIGGER.name)
            }.getOrDefault(OnboardingMode.TRIGGER)
            profileImportedCount = it.getInt(KEY_IMPORTED, 0)
            pickedLanguage = it.getString(KEY_LANG) ?: pickedLanguage
        }

        buildRefreshMap()
        populateLanguageList()
        setupModeSelection()
        setupProfileStep()
        setupButtons()

        // Initial logo entrance — small zoom + fade.
        binding.ivLogo.alpha = 0f
        binding.ivLogo.scaleX = 0.8f
        binding.ivLogo.scaleY = 0.8f
        binding.ivLogo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()

        renderStep(animate = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Locale changed via setApplicationLocales — manifest configChanges
        // keeps this activity alive instead of recreating it. Refresh every
        // string-bound view, the dynamic language list, and the bottom buttons.
        refreshMap.forEach { (tv, resId) -> tv.text = getString(resId) }
        binding.btnNext.text = getString(
            if (step == STEP_DONE) R.string.onb_finish else R.string.onb_next
        )
        binding.btnBack.text = getString(R.string.onb_back)
        // The language list contains language *names* (already universal) but
        // its title/subtitle/strings come from the refreshMap above. Rebuild
        // the rows anyway so the selection's stroke color tracks the theme
        // refresh on dynamic-color themes.
        populateLanguageList()
    }

    private fun buildRefreshMap() {
        refreshMap = listOf(
            binding.tvLangTitle to R.string.onb_lang_title,
            binding.tvLangSubtitle to R.string.onb_lang_subtitle,
            binding.tvWelcomeTitle to R.string.onb_welcome_title,
            binding.tvWelcomeSubtitle to R.string.onb_welcome_subtitle,
            binding.tvWelcomeBody to R.string.onb_welcome_body,
            binding.tvFeatureTriggerTitle to R.string.onb_feature_trigger_title,
            binding.tvFeatureTriggerBody to R.string.onb_feature_trigger,
            binding.tvFeaturePerappTitle to R.string.onb_feature_perapp_title,
            binding.tvFeaturePerappBody to R.string.onb_feature_perapp,
            binding.tvFeatureProtocolsTitle to R.string.onb_feature_protocols_title,
            binding.tvFeatureProtocolsBody to R.string.onb_feature_protocols,
            binding.tvModeTitle to R.string.onb_mode_title,
            binding.tvModeSubtitle to R.string.onb_mode_subtitle,
            binding.tvModeTriggerTitle to R.string.onb_mode_trigger,
            binding.tvModeTriggerDesc to R.string.onb_mode_trigger_desc,
            binding.tvModeGlobalTitle to R.string.onb_mode_global,
            binding.tvModeGlobalDesc to R.string.onb_mode_global_desc,
            binding.tvModePerappTitle to R.string.onb_mode_perapp,
            binding.tvModePerappDesc to R.string.onb_mode_perapp_desc,
            binding.tvModeSkipTitle to R.string.onb_mode_skip,
            binding.tvModeSkipDesc to R.string.onb_mode_skip_desc,
            binding.tvVpnTitle to R.string.onb_vpn_title,
            binding.tvVpnBody to R.string.onb_vpn_body,
            binding.btnVpnGrant to R.string.onb_vpn_grant,
            binding.tvUsageTitle to R.string.onb_usage_title,
            binding.tvUsageBody to R.string.onb_usage_body,
            binding.btnUsageGrant to R.string.onb_usage_grant,
            binding.tvProfileTitle to R.string.onb_profile_title,
            binding.tvProfileBody to R.string.onb_profile_body,
            binding.btnProfilePaste to R.string.onb_profile_paste,
            binding.btnProfileImport to R.string.onb_profile_import,
            binding.btnProfileSkip to R.string.onb_profile_skip,
            binding.tvDoneTitle to R.string.onb_done_title,
            binding.tvDoneBody to R.string.onb_done_body,
        )
        // TextInputLayout hint also needs refreshing
        binding.tilProfileUri.hint = getString(R.string.onb_profile_hint)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
        outState.putString(KEY_MODE, pickedMode.name)
        outState.putInt(KEY_IMPORTED, profileImportedCount)
        outState.putString(KEY_LANG, pickedLanguage)
    }

    override fun onResume() {
        super.onResume()
        if (step == STEP_USAGE) refreshUsageStatus()
        if (step == STEP_VPN && VpnService.prepare(this) == null) showVpnGranted()
    }

    private fun populateLanguageList() {
        val container = binding.langContainer
        if (container.childCount > 0) container.removeAllViews()
        val outlineColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
        val primaryColor = themeColor(com.google.android.material.R.attr.colorPrimary)
        LANGUAGE_CODES.forEachIndexed { idx, code ->
            // Don't use isCheckable=true — Material animates a checked-icon
            // drawable on toggle and crashes on Android 14 when the icon is
            // null. Selection is shown purely via stroke color + radio button.
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                radius = dp(12).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(if (code == pickedLanguage) primaryColor else outlineColor)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val rb = RadioButton(this).apply {
                isChecked = code == pickedLanguage
                isClickable = false
                isFocusable = false
            }
            val name = TextView(this).apply {
                text = LANGUAGE_NAMES[idx]
                textSize = 15f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(8) }
            }
            row.addView(rb)
            row.addView(name)
            card.addView(row)
            card.setOnClickListener {
                if (pickedLanguage == code) return@setOnClickListener
                for (i in 0 until container.childCount) {
                    val other = container.getChildAt(i) as? MaterialCardView ?: continue
                    other.setStrokeColor(outlineColor)
                    val otherRow = other.getChildAt(0) as? ViewGroup
                    (otherRow?.getChildAt(0) as? RadioButton)?.isChecked = false
                }
                card.setStrokeColor(primaryColor)
                rb.isChecked = true
                pickedLanguage = code
                card.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
                    card.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                // Apply locale live. The activity declares
                // configChanges=locale, so it stays alive — onConfigurationChanged
                // is called and refreshAllText runs. No flicker.
                val locales = if (code == "system") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(code)
                }
                AppCompatDelegate.setApplicationLocales(locales)
            }
            container.addView(card)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun setupModeSelection() {
        val cards = listOf(
            binding.cardModeTrigger to OnboardingMode.TRIGGER,
            binding.cardModeGlobal to OnboardingMode.GLOBAL,
            binding.cardModePerapp to OnboardingMode.PER_APP,
            binding.cardModeSkip to OnboardingMode.SKIP,
        )
        cards.forEach { (card, mode) ->
            card.setOnClickListener {
                pickedMode = mode
                binding.rbModeTrigger.isChecked = mode == OnboardingMode.TRIGGER
                binding.rbModeGlobal.isChecked = mode == OnboardingMode.GLOBAL
                binding.rbModePerapp.isChecked = mode == OnboardingMode.PER_APP
                binding.rbModeSkip.isChecked = mode == OnboardingMode.SKIP
                card.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
                    card.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
            }
        }
        // Reflect saved state on the radio buttons after recreation
        binding.rbModeTrigger.isChecked = pickedMode == OnboardingMode.TRIGGER
        binding.rbModeGlobal.isChecked = pickedMode == OnboardingMode.GLOBAL
        binding.rbModePerapp.isChecked = pickedMode == OnboardingMode.PER_APP
        binding.rbModeSkip.isChecked = pickedMode == OnboardingMode.SKIP
    }

    private fun setupProfileStep() {
        binding.btnProfilePaste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) {
                showProfileStatus(getString(R.string.onb_profile_empty), error = true)
            } else {
                binding.etProfileUri.setText(text)
                binding.etProfileUri.setSelection(text.length)
            }
        }
        binding.btnProfileImport.setOnClickListener { importTypedProfile() }
        binding.btnProfileSkip.setOnClickListener {
            if (transitioning) return@setOnClickListener
            step = STEP_DONE
            renderStep(animate = true, forward = true)
        }
        binding.etProfileUri.doAfterTextChanged {
            if (binding.tvProfileStatus.visibility == View.VISIBLE) {
                binding.tvProfileStatus.visibility = View.GONE
            }
        }
    }

    private fun importTypedProfile() {
        val text = binding.etProfileUri.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            showProfileStatus(getString(R.string.onb_profile_empty), error = true)
            return
        }
        // Subscription URLs are http(s)://… links the user copies from their
        // provider. They aren't valid for ProfileParser (which expects
        // vless://, vmess://, …). Detect them up-front and run the
        // subscription import path instead, otherwise the wizard reports
        // "Couldn't parse" for what is really a valid input.
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            importSubscription(text)
            return
        }
        importVpnProfiles(text)
    }

    private fun importVpnProfiles(text: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ProfileParser.parseMultiline(text) }
            if (result.profiles.isEmpty()) {
                val reason = result.errors.firstOrNull() ?: "unknown"
                showProfileStatus(getString(R.string.onb_profile_error, reason), error = true)
                return@launch
            }
            withContext(Dispatchers.IO) {
                val app = application as App
                val existing = app.profileRepository.getAll().size
                val toInsert = result.profiles.mapIndexed { idx, p -> p.copy(sortOrder = existing + idx) }
                app.profileRepository.insertAll(toInsert)
            }
            profileImportedCount += result.profiles.size
            showProfileStatus(getString(R.string.onb_profile_added, result.profiles.size), error = false)
            binding.etProfileUri.setText("")
        }
    }

    private fun importSubscription(url: String) {
        val app = application as App
        val repo = app.subscriptionRepository
        showProfileStatus(getString(R.string.onb_profile_subscription_fetching), error = false)
        lifecycleScope.launch {
            // Same SSRF / HTTPS-only guard as the regular Add Subscription flow.
            try {
                withContext(Dispatchers.IO) { repo.validateUrl(url) }
            } catch (e: Exception) {
                showProfileStatus(
                    getString(R.string.onb_profile_error, e.message ?: "invalid URL"),
                    error = true,
                )
                return@launch
            }
            // Persist a stub subscription, then trigger an update so the real
            // profile list is fetched and inserted right inside the wizard.
            val sub = withContext(Dispatchers.IO) {
                val s = com.smarttools.netguard.model.Subscription(
                    name = subscriptionNameFromUrl(url),
                    url = url,
                )
                val id = repo.insert(s)
                s.copy(id = id)
            }
            val result = withContext(Dispatchers.IO) { repo.updateSubscription(sub) }
            result.fold(
                onSuccess = { count ->
                    profileImportedCount += count
                    showProfileStatus(
                        getString(R.string.onb_profile_added, count),
                        error = false,
                    )
                    binding.etProfileUri.setText("")
                },
                onFailure = { err ->
                    showProfileStatus(
                        getString(R.string.onb_profile_error, friendlyTlsError(err)),
                        error = true,
                    )
                },
            )
        }
    }

    /**
     * Translate the most common TLS / SSL failures into something a user can
     * act on. The default message ("chain validation failed", "trust anchor
     * for certification path not found") is meaningless to non-developers
     * and the most common root cause is a misconfigured device clock — the
     * cert appears not-yet-valid or already-expired.
     */
    private fun friendlyTlsError(err: Throwable): String {
        val raw = err.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            "chain validation" in lower ||
                "trust anchor" in lower ||
                "certpathvalidator" in lower ||
                "notbefore" in lower ||
                "notafter" in lower ||
                "expired" in lower ||
                "not yet valid" in lower ->
                "TLS error — check your device date/time. ($raw)"
            "certificate pinning failure" in lower ->
                "TLS pin mismatch (cert rotated since this NetGuard release). $raw"
            "unable to find acceptable" in lower ->
                "Server's TLS chain is incomplete (server config issue). $raw"
            else -> raw.ifBlank { "fetch failed" }
        }
    }

    private fun subscriptionNameFromUrl(url: String): String {
        return try {
            java.net.URL(url).host.takeIf { it.isNotBlank() } ?: "Subscription"
        } catch (_: Exception) {
            "Subscription"
        }
    }

    private fun showProfileStatus(text: String, error: Boolean) {
        binding.tvProfileStatus.text = text
        binding.tvProfileStatus.setTextColor(
            getColor(if (error) R.color.status_error else R.color.status_connected)
        )
        binding.tvProfileStatus.alpha = 0f
        binding.tvProfileStatus.visibility = View.VISIBLE
        binding.tvProfileStatus.animate().alpha(1f).setDuration(200).start()
    }

    private fun setupButtons() {
        binding.btnNext.setOnClickListener { advance() }
        binding.btnBack.setOnClickListener { goBack() }
        binding.btnVpnGrant.setOnClickListener {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                showVpnGranted()
            }
        }
        binding.btnUsageGrant.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(fallback)
            }
        }
    }

    private fun showVpnGranted() {
        binding.tvVpnStatus.text = getString(R.string.onb_vpn_granted)
        binding.tvVpnStatus.alpha = 0f
        binding.tvVpnStatus.visibility = View.VISIBLE
        binding.tvVpnStatus.animate().alpha(1f).setDuration(200).start()
        binding.btnVpnGrant.isEnabled = false
    }

    private fun advance() {
        if (transitioning) return
        when (step) {
            STEP_LANG -> step = STEP_WELCOME
            STEP_WELCOME -> step = STEP_MODE
            STEP_MODE -> step = STEP_VPN
            STEP_VPN -> step = if (pickedMode == OnboardingMode.TRIGGER) STEP_USAGE else STEP_PROFILE
            STEP_USAGE -> step = STEP_PROFILE
            STEP_PROFILE -> step = STEP_DONE
            STEP_DONE -> { finishOnboarding(); return }
        }
        renderStep(animate = true, forward = true)
    }

    private fun goBack() {
        if (transitioning) return
        when (step) {
            STEP_LANG -> return
            STEP_WELCOME -> step = STEP_LANG
            STEP_MODE -> step = STEP_WELCOME
            STEP_VPN -> step = STEP_MODE
            STEP_USAGE -> step = STEP_VPN
            STEP_PROFILE -> step = if (pickedMode == OnboardingMode.TRIGGER) STEP_USAGE else STEP_VPN
            STEP_DONE -> step = STEP_PROFILE
        }
        renderStep(animate = true, forward = false)
    }

    private fun stepView(s: Int): View = when (s) {
        STEP_LANG -> binding.stepLang
        STEP_WELCOME -> binding.stepWelcome
        STEP_MODE -> binding.stepMode
        STEP_VPN -> binding.stepVpn
        STEP_USAGE -> binding.stepUsage
        STEP_PROFILE -> binding.stepProfile
        STEP_DONE -> binding.stepDone
        else -> binding.stepLang
    }

    private fun renderStep(animate: Boolean, forward: Boolean = true) {
        val views = listOf(
            binding.stepLang, binding.stepWelcome, binding.stepMode, binding.stepVpn,
            binding.stepUsage, binding.stepProfile, binding.stepDone
        )
        val target = stepView(step)
        val currentlyVisible = views.firstOrNull { it.visibility == View.VISIBLE && it !== target }

        binding.btnBack.visibility = if (step == STEP_LANG) View.INVISIBLE else View.VISIBLE
        binding.btnNext.text = getString(
            if (step == STEP_DONE) R.string.onb_finish else R.string.onb_next
        )

        if (!animate) {
            views.forEach { it.visibility = if (it === target) View.VISIBLE else View.GONE }
            target.alpha = 1f
            target.translationX = 0f
            updateProgress(animate = false)
            if (step == STEP_USAGE) refreshUsageStatus()
            return
        }

        transitioning = true
        val width = binding.stepContainer.width.toFloat().takeIf { it > 0 } ?: 600f
        val slideOffset = if (forward) width * 0.18f else -width * 0.18f

        if (currentlyVisible != null) {
            currentlyVisible.animate()
                .alpha(0f)
                .translationX(-slideOffset)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    currentlyVisible.visibility = View.GONE
                    currentlyVisible.translationX = 0f

                    target.alpha = 0f
                    target.translationX = slideOffset
                    target.visibility = View.VISIBLE
                    target.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            transitioning = false
                            if (step == STEP_USAGE) refreshUsageStatus()
                        }
                        .start()
                }
                .start()
        } else {
            target.visibility = View.VISIBLE
            target.alpha = 1f
            target.translationX = 0f
            transitioning = false
        }
        updateProgress(animate = true)
    }

    private fun updateProgress(animate: Boolean) {
        val totalSteps = if (pickedMode == OnboardingMode.TRIGGER) 7 else 6
        val effective = when (step) {
            STEP_LANG -> 1
            STEP_WELCOME -> 2
            STEP_MODE -> 3
            STEP_VPN -> 4
            STEP_USAGE -> 5
            STEP_PROFILE -> if (pickedMode == OnboardingMode.TRIGGER) 6 else 5
            STEP_DONE -> totalSteps
            else -> 1
        }
        val target = (effective * 100) / totalSteps
        if (animate) {
            ObjectAnimator.ofInt(binding.progressSteps, "progress", binding.progressSteps.progress, target).apply {
                duration = 360
                interpolator = DecelerateInterpolator()
                start()
            }
        } else {
            binding.progressSteps.progress = target
        }
    }

    private fun refreshUsageStatus() {
        val granted = TriggerWatcherService.hasUsageStatsPermission(this)
        if (granted) {
            binding.tvUsageStatus.text = getString(R.string.onb_usage_granted)
            binding.tvUsageStatus.alpha = 0f
            binding.tvUsageStatus.visibility = View.VISIBLE
            binding.tvUsageStatus.animate().alpha(1f).setDuration(200).start()
            binding.btnUsageGrant.isEnabled = false
        } else {
            binding.tvUsageStatus.visibility = View.GONE
            binding.btnUsageGrant.isEnabled = true
        }
    }

    private fun finishOnboarding() {
        val app = application as App
        val current = app.loadSettings()
        val withMode = when (pickedMode) {
            OnboardingMode.TRIGGER -> current.copy(
                triggerEnabled = false,
                perAppMode = PerAppMode.DISABLED
            )
            OnboardingMode.GLOBAL -> current.copy(
                triggerEnabled = false,
                perAppMode = PerAppMode.DISABLED
            )
            OnboardingMode.PER_APP -> current.copy(
                triggerEnabled = false,
                perAppMode = PerAppMode.WHITELIST
            )
            OnboardingMode.SKIP -> current
        }
        // Persist language alongside mode so MainActivity respects it on launch.
        val updated = withMode.copy(language = pickedLanguage)
        app.saveSettings(updated)
        app.getPreferences().edit()
            .putBoolean(PREF_ONBOARDING_DONE, true)
            .putString(PREF_ONBOARDING_MODE, pickedMode.name)
            .apply()

        // Apply locale only now — doing it during the wizard would recreate
        // the activity each pick and flash the screen.
        val locales = if (pickedLanguage == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(pickedLanguage)
        }
        AppCompatDelegate.setApplicationLocales(locales)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (pickedMode == OnboardingMode.TRIGGER) {
                putExtra(EXTRA_OPEN_TRIGGER, true)
            }
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        private const val STEP_LANG = 0
        private const val STEP_WELCOME = 1
        private const val STEP_MODE = 2
        private const val STEP_VPN = 3
        private const val STEP_USAGE = 4
        private const val STEP_PROFILE = 5
        private const val STEP_DONE = 6

        private const val KEY_STEP = "wizard_step"
        private const val KEY_MODE = "wizard_mode"
        private const val KEY_IMPORTED = "wizard_imported"
        private const val KEY_LANG = "wizard_lang"

        const val PREF_ONBOARDING_DONE = "onboarding_done"
        const val PREF_ONBOARDING_MODE = "onboarding_mode"
        const val EXTRA_OPEN_TRIGGER = "open_trigger"

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
}
