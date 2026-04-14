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
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.model.ThemeMode
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
        val theme = (application as App).loadSettings().themeMode
        setTheme(when (theme) {
            ThemeMode.DARK -> R.style.Theme_NetGuard
            ThemeMode.LIGHT -> R.style.Theme_NetGuard_Light
            ThemeMode.OLED -> R.style.Theme_NetGuard_OLED
            ThemeMode.OCEAN -> R.style.Theme_NetGuard_Ocean
            ThemeMode.DYNAMIC -> R.style.Theme_NetGuard_Dynamic
        })
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setupWithNavController(navController)

        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
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
