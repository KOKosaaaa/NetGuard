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

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            mainViewModel.connect()
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as App
        // Redirect to onboarding on first launch — done before setContentView
        // so we never flash the main UI behind the wizard.
        val prefs = app.getPreferences()
        if (!prefs.getBoolean(OnboardingActivity.PREF_ONBOARDING_DONE, false)) {
            super.onCreate(savedInstanceState)
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        val theme = app.loadSettings().themeMode
        setTheme(when (theme) {
            ThemeMode.DARK -> R.style.Theme_NetGuard
            ThemeMode.LIGHT -> R.style.Theme_NetGuard_Light
            ThemeMode.OLED -> R.style.Theme_NetGuard_OLED
            ThemeMode.OCEAN -> R.style.Theme_NetGuard_Ocean
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
        }
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
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            mainViewModel.connect()
        }
    }
}
