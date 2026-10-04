package com.smarttools.netguard

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.view.View
import com.smarttools.netguard.widget.LiquidBackdrop
import com.smarttools.netguard.widget.LiquidGlass
import com.smarttools.netguard.model.ConnectionState
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.model.ThemeMode
import com.smarttools.netguard.ui.onboarding.OnboardingActivity
import com.smarttools.netguard.viewmodel.MainViewModel
import com.smarttools.netguard.viewmodel.ProfileListViewModel

class MainActivity : AppCompatActivity() {

    lateinit var mainViewModel: MainViewModel
        private set

    /**
     * Action to run after the system VPN permission dialog returns OK.
     * Defaults to a plain connect, but [requestVpnPermissionAndAutoSelect]
     * swaps in autoSelectAndConnect so the same launcher serves both flows.
     */
    private var pendingVpnAction: () -> Unit = { mainViewModel.connect() }
    private lateinit var glassBackdrop: LiquidBackdrop
    private var glassConnected = false
    fun decorateGlass(view: View) {
        if (::glassBackdrop.isInitialized) LiquidGlass.decorate(view,glassBackdrop,glassConnected)
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingVpnAction
        pendingVpnAction = { mainViewModel.connect() }
        if (result.resultCode == RESULT_OK) {
            action()
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as App
        // Redirect to onboarding on first launch — done before setContentView
        // so we never flash the main UI behind the wizard. We treat an
        // *upgrade* from a pre-onboarding NetGuard (any version <1.1.8) as a
        // user who has already configured the app: if SharedPreferences
        // contains any saved key besides `onboarding_done` itself, the user
        // was here before — skip the wizard and mark it done so we don't
        // re-check this on every launch.
        val prefs = app.getPreferences()
        val onboardingDone = prefs.getBoolean(OnboardingActivity.PREF_ONBOARDING_DONE, false)
        if (!onboardingDone) {
            // App's default migrations run before this Activity, even on a
            // clean install. Their four default values are not user settings.
            val bootstrapDefaults = mapOf<String, Any>(
                "striping_migration_v2" to true,
                "telemost_striping" to false,
                "adaptive_routing_default_v1" to true,
                "routing_mode" to com.smarttools.netguard.model.RoutingMode.AUTO.name
            )
            val hasPriorInstall = prefs.all.any { (key, value) ->
                key != OnboardingActivity.PREF_ONBOARDING_DONE &&
                    !(bootstrapDefaults.containsKey(key) && bootstrapDefaults[key] == value)
            }
            if (hasPriorInstall) {
                prefs.edit()
                    .putBoolean(OnboardingActivity.PREF_ONBOARDING_DONE, true)
                    .apply()
            } else {
                super.onCreate(savedInstanceState)
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
        }

        val theme = app.loadSettings().themeMode
        setTheme(when (theme) {
            ThemeMode.DARK -> R.style.Theme_NetGuard
            ThemeMode.LIGHT -> R.style.Theme_NetGuard_Light
            ThemeMode.OLED -> R.style.Theme_NetGuard_OLED
            ThemeMode.OCEAN -> R.style.Theme_NetGuard_Ocean
            ThemeMode.FSOCIETY -> R.style.Theme_NetGuard_Fsociety
            ThemeMode.DYNAMIC -> R.style.Theme_NetGuard_Dynamic
        })
        // Apply DynamicColors AFTER setTheme() — otherwise setTheme() overwrites the overlay
        if (theme == ThemeMode.DYNAMIC) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_main)
        // Refresh channel wording after an app-language change without resetting user channel preferences.
        com.smarttools.netguard.service.NotificationHelper.createChannel(this)
        com.smarttools.netguard.service.WifiAutoConnectManager.refreshChannel(this)
        com.smarttools.netguard.service.TriggerWatcherService.refreshChannel(this)
        // Dynamic palettes can be light even when the previous theme was dark.
        // Match system icons to the resolved backgrounds after the overlay is applied.
        val bars = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        val background = android.util.TypedValue().also {
            this.theme.resolveAttribute(android.R.attr.colorBackground, it, true)
        }.data
        val surface = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, "MainActivity")
        bars.isAppearanceLightStatusBars = androidx.core.graphics.ColorUtils.calculateLuminance(background) > 0.5
        bars.isAppearanceLightNavigationBars = androidx.core.graphics.ColorUtils.calculateLuminance(surface) > 0.5

        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        glassBackdrop=findViewById(R.id.liquid_backdrop)
        applyGlassInsets()
        supportFragmentManager.registerFragmentLifecycleCallbacks(object:FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm:FragmentManager,f:Fragment,v:View,state:Bundle?) {
                decorateGlass(v)
                v.post { if(f.view===v)decorateGlass(v) }
            }
        },true)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.connectionState.collect { state ->
                    glassConnected=state is ConnectionState.Connected
                    glassBackdrop.setConnected(glassConnected)
                    // Palette must use the final state immediately, even while the light fades.
                    glassBackdrop.postDelayed({decorateGlass(findViewById(R.id.nav_host_fragment));decorateGlass(findViewById(R.id.bottom_nav))},470)
                }
            }
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setupWithNavController(navController)

        // Simple/Expert: the Logs tab is developer-oriented, so hide it unless
        // Expert mode is on (Settings → Expert mode). SettingsFragment calls
        // applyExpertVisibility() on toggle — no recreate() (that scrambled the
        // bottom-nav highlight when the menu item count changed).
        applyExpertVisibility()

        // fsociety boot-sequence: only on cold start (no savedInstanceState) so
        // it doesn't replay on every rotation / process restore. Lines type in
        // one by one for ~1.8s, then fade out.
        decorateGlass(bottomNav)
        // Custom click handler: when a tab is tapped, pop everything off the
        // backstack until we're at the root of THAT tab. Default behavior
        // can leave sub-screens (like nav_trigger under nav_settings) on the
        // backstack so the user comes back to the wrong fragment.
        bottomNav.setOnItemSelectedListener { item ->
            if (navController.currentDestination?.id == item.itemId) return@setOnItemSelectedListener true
            // Always go back to the ROOT fragment of the tab — clear any
            // sub-screen the user opened earlier (e.g. Settings → Trigger).
            // saveState/restoreState are intentionally false so each tab
            // tap returns to the top of that section.
            val options = androidx.navigation.NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(false)
                .setPopUpTo(
                    navController.graph.startDestinationId,
                    /* inclusive = */ false,
                    /* saveState = */ false
                )
                .build()
            try {
                navController.navigate(item.itemId, null, options)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        // Reselecting an open root keeps its view and scroll offset.
        bottomNav.setOnItemReselectedListener { item ->
            if (navController.currentDestination?.id != item.itemId) {
                if (!navController.popBackStack(item.itemId, false)) navController.navigate(item.itemId)
            }
        }
        // Keep highlight in sync — if user navigates by code (e.g. into
        // a sub-screen), reflect the parent tab on the bottom bar.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val topLevelId = when (destination.id) {
                R.id.nav_per_app, R.id.nav_trigger -> R.id.nav_settings
                R.id.nav_profile_edit, R.id.nav_qr_scan -> R.id.nav_profiles
                else -> destination.id
            }
            val item = bottomNav.menu.findItem(topLevelId)
            if (item != null && !item.isChecked) item.isChecked = true
        }

        handleDeepLink(intent)
        if (intent?.getBooleanExtra("auto_connect", false) == true) {
            requestVpnPermissionAndConnect()
        }
        if (intent?.getBooleanExtra(OnboardingActivity.EXTRA_OPEN_TRIGGER, false) == true) {
            try {
                navController.navigate(R.id.nav_settings)
                navController.navigate(R.id.action_settings_to_trigger)
            } catch (_: Exception) { /* graph mismatch — ignore */ }
            // Consume the extra so a later recreation (theme/locale change)
            // doesn't re-fire the same navigation and bounce the user back to
            // the trigger settings screen out of nowhere.
            intent?.removeExtra(OnboardingActivity.EXTRA_OPEN_TRIGGER)
        }
    }

    private fun applyGlassInsets() {
        val shell = findViewById<View>(R.id.main_shell)
        val host = findViewById<View>(R.id.nav_host_fragment)
        val nav = findViewById<View>(R.id.bottom_nav)
        val density = resources.displayMetrics.density
        // Material's default listener would add the navigation inset a second time.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(nav) { _, insets -> insets }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            fun margins(view: View, left: Int, top: Int, right: Int, bottom: Int) {
                val params = view.layoutParams as android.view.ViewGroup.MarginLayoutParams
                if (params.leftMargin != left || params.topMargin != top || params.rightMargin != right || params.bottomMargin != bottom) {
                    params.setMargins(left, top, right, bottom)
                    view.layoutParams = params
                }
            }
            // Keep the optical background full-window; inset only interactive content.
            margins(host, safe.left, safe.top, safe.right, 0)
            margins(nav, safe.left + (16 * density).toInt(), 0,
                safe.right + (16 * density).toInt(), maxOf(safe.bottom, keyboard) + (12 * density).toInt())
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(shell)
    }

    /**
     * Show/hide the developer-oriented Logs tab based on Expert mode.
     * Called at create and live from SettingsFragment when the toggle
     * flips — done in-place (no recreate) so the bottom-nav selection
     * highlight isn't disturbed.
     */
    fun applyExpertVisibility() {
        val expert = (application as App).loadSettings().expertMode
        findViewById<BottomNavigationView>(R.id.bottom_nav)
            ?.menu?.findItem(R.id.nav_logs)?.isVisible = expert
    }

    private fun playFsocietyBootSequence() {
        val overlay = findViewById<android.widget.TextView>(R.id.boot_overlay) ?: return
        overlay.visibility = android.view.View.VISIBLE
        overlay.alpha = 1f
        val lines = listOf(
            "[    0.000] booting fsociety v1.2.2",
            "[    0.142] init tunnel pool...     OK",
            "[    0.298] decrypting profiles...  OK",
            "[    0.521] kill switch armed...    OK",
            "[    0.842] hello, friend."
        )
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val sb = StringBuilder()
        // Type lines in over ~1.4s total (5 lines × 280ms cadence).
        for ((i, line) in lines.withIndex()) {
            handler.postDelayed({
                sb.appendLine(line)
                overlay.text = sb.toString()
            }, i * 280L)
        }
        // Hold the final frame briefly, then fade out.
        handler.postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction { overlay.visibility = android.view.View.GONE }
                .start()
        }, 1800L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        if (intent.getBooleanExtra("auto_connect", false)) {
            requestVpnPermissionAndConnect()
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val input = intent?.data?.toString() ?: return
        val uri = runCatching { com.smarttools.netguard.core.SubscriptionLink.unwrap(input) }.getOrNull() ?: return
        if (uri.length > 8192) {
            Toast.makeText(this, getString(com.smarttools.netguard.R.string.import_uri_too_long), Toast.LENGTH_SHORT).show()
            return
        }
        if (uri.startsWith("https://", true) && (input.startsWith("happ://", true) || input.startsWith("v2rayng://", true))) {
            val subscriptionHost = runCatching { java.net.URI(uri).host }.getOrNull() ?: return
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_profile_question)
                .setMessage(subscriptionHost)
                .setPositiveButton(R.string.import_btn) { _, _ ->
                    ViewModelProvider(this)[com.smarttools.netguard.viewmodel.SubscriptionViewModel::class.java].addSubscription("", uri)
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "telemost://", "wbstream://")
        if (!schemes.any { uri.startsWith(it) } && !com.smarttools.netguard.model.WbStreamLink.looksLike(uri)) return

        val profile = try {
            ProfileParser.parseSingleUri(uri)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Invalid deep link URI: ${e.message}")
            Toast.makeText(this, getString(com.smarttools.netguard.R.string.import_invalid_uri), Toast.LENGTH_SHORT).show()
            return
        }

        if (profile == null) {
            Toast.makeText(this, getString(com.smarttools.netguard.R.string.import_protocol_unsupported), Toast.LENGTH_SHORT).show()
            return
        }

        val serverInfo = if (profile.protocol.usesRelay) profile.displayProtocol else "${profile.protocol.value}://${profile.address}:${profile.port}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_profile_question)
            .setMessage(getString(R.string.import_profile_confirm, serverInfo))
            .setPositiveButton(R.string.import_btn) { _, _ ->
                val profileVm = ViewModelProvider(this)[ProfileListViewModel::class.java]
                profileVm.importFromText(uri)
                Toast.makeText(this, getString(com.smarttools.netguard.R.string.import_profile_done), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun requestVpnPermissionAndConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingVpnAction = { mainViewModel.connect() }
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            mainViewModel.connect()
        }
    }

    /**
     * Same as [requestVpnPermissionAndConnect] but, on the OK callback, runs
     * the auto-select probe and connects to whichever server wins. Used by
     * the Home Connect button when no profile is selected yet — saves the
     * user a manual "pick a server" step.
     */
    fun requestVpnPermissionAndAutoSelect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingVpnAction = { mainViewModel.autoSelectAndConnect() }
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            mainViewModel.autoSelectAndConnect()
        }
    }
}
