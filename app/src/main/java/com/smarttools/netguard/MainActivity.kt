package com.smarttools.netguard

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
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
            val hasPriorInstall = prefs.all.keys.any {
                it != OnboardingActivity.PREF_ONBOARDING_DONE
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
        setContentView(R.layout.activity_main)

        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setupWithNavController(navController)

        // Simple/Expert: the Logs tab is developer-oriented, so hide it unless
        // Expert mode is on (Settings → Expert mode). Read once at create —
        // toggling Expert recreates MainActivity to re-apply (see SettingsFragment).
        bottomNav.menu.findItem(R.id.nav_logs)?.isVisible =
            (application as App).loadSettings().expertMode

        // fsociety boot-sequence: only on cold start (no savedInstanceState) so
        // it doesn't replay on every rotation / process restore. Lines type in
        // one by one for ~1.8s, then fade out.
        if (theme == ThemeMode.FSOCIETY && savedInstanceState == null) {
            playFsocietyBootSequence()
        }
        // Custom click handler: when a tab is tapped, pop everything off the
        // backstack until we're at the root of THAT tab. Default behavior
        // can leave sub-screens (like nav_trigger under nav_settings) on the
        // backstack so the user comes back to the wrong fragment.
        bottomNav.setOnItemSelectedListener { item ->
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
        val uri = intent?.data?.toString() ?: return
        if (uri.length > 8192) {
            Toast.makeText(this, "URI too long", Toast.LENGTH_SHORT).show()
            return
        }
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://")
        if (!schemes.any { uri.startsWith(it) }) return

        val profile = try {
            ProfileParser.parseSingleUri(uri)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Invalid deep link URI: ${e.message}")
            Toast.makeText(this, "Invalid profile URI", Toast.LENGTH_SHORT).show()
            return
        }

        if (profile == null) {
            Toast.makeText(this, "Unsupported protocol", Toast.LENGTH_SHORT).show()
            return
        }

        val serverInfo = "${profile.protocol.value}://${profile.address}:${profile.port}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_profile_question)
            .setMessage(getString(R.string.import_profile_confirm, serverInfo))
            .setPositiveButton(R.string.import_btn) { _, _ ->
                val profileVm = ViewModelProvider(this)[ProfileListViewModel::class.java]
                profileVm.importFromText(uri)
                Toast.makeText(this, "Profile imported", Toast.LENGTH_SHORT).show()
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
