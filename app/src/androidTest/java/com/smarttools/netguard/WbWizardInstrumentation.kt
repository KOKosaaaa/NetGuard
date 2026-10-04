package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerRepository
import com.smarttools.netguard.ui.managed.*
import kotlinx.coroutines.runBlocking

/** Device smoke test without third-party test dependencies or a real VPS. */
class WbWizardInstrumentation : Instrumentation() {
    private var routes = false
    private var recovery = false
    private var dpi = false
    private var diagnostics = false
    private var guide = false
    private var home = false
    private var glass = false
    private var glassEdges = false
    private var glassTheme: String? = null
    private var glassQuick = false
    private var glassCancel = false
    private var speedTls = false
    private var speedTlsKeepAlive = false
    private var speedLive = false
    private var speedUiPublic = false
    private var notifications = false
    private var fastSelection = false
    private var mapLabelCapture = false
    private var localizationCapture = false
    private var notificationsBlocked = false
    private var wifiIdentity: String? = null
    private var wifiBackground = false
    private var guideLocale = "ru"
    private var diagnosticsLocale = "ru"
    override fun onCreate(arguments: Bundle?) {
        routes = arguments?.getString("routes") == "true"
        recovery = arguments?.getString("wb_recovery") == "true"
        dpi = arguments?.getString("dpi") == "true"
        diagnostics = arguments?.getString("diagnostics") == "true"
        guide = arguments?.getString("guide") == "true"
        home = arguments?.getString("home") == "true"
        glass = arguments?.getString("glass") == "true"
        glassEdges = arguments?.getString("glass_edges") == "true"
        glassTheme = arguments?.getString("glass_theme")
        glassQuick = arguments?.getString("glass_quick") == "true"
        glassCancel = arguments?.getString("glass_cancel") == "true"
        speedTls = arguments?.getString("speed_tls") == "true"
        speedTlsKeepAlive = arguments?.getString("speed_tls_keepalive") == "true"
        speedLive = arguments?.getString("speed_live") == "true"
        speedUiPublic = arguments?.getString("speed_ui_public") == "true"
        notifications = arguments?.getString("notifications") == "true"
        fastSelection = arguments?.getString("fast_selection") == "true"
        mapLabelCapture = arguments?.getString("map_label_capture") == "true"
        localizationCapture = arguments?.getString("localization_capture") == "true"
        notificationsBlocked = arguments?.getString("notifications_blocked") == "true"
        wifiIdentity = arguments?.getString("wifi_identity")
        wifiBackground = arguments?.getString("wifi_background") == "true"
        guideLocale = arguments?.getString("guide_locale") ?: "ru"
        diagnosticsLocale = arguments?.getString("diagnostics_locale") ?: "ru"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        if (wifiBackground) { WifiBackgroundAndroidProbe(this).run(); return }
        if (wifiIdentity != null) { WifiIdentityAndroidProbe(this).run(wifiIdentity == "granted"); return }
        if (localizationCapture) { LocalizationCapture(this).run(); return }
        if (mapLabelCapture) { MapLabelCapture(this).run(); return }
        if (notifications) { NotificationPermissionAndroidProbe(this).run(notificationsBlocked); return }
        if (fastSelection) { FastSelectionAndroidProbe(this).run(); return }
        if (speedLive) { SpeedLiveAndroidProbe(this).run(); return }
        if (speedUiPublic) { SpeedUiPublicAndroidProbe(this).run(); return }
        if (glass || glassEdges || glassCancel) { GlassHomeAndroidProbe(this).run(glassTheme,glassQuick,glassEdges,glassCancel); return }
        if (speedTls) {
            val result = Bundle()
            val code = try {
                result.putString("stream", SpeedTestAndroidTlsProbe(this).run(speedTlsKeepAlive) + "\n")
                Activity.RESULT_OK
            } catch (t: Throwable) {
                result.putString("stream", "Android speed TLS FAIL: ${t.stackTraceToString()}\n")
                Activity.RESULT_CANCELED
            }
            finish(code, result); return
        }
        if (home) { HomeDesignAndroidProbe(this).run(); return }
        if (guide) { OnboardingGuideAndroidProbe(this).run(guideLocale); return }
        if (diagnostics) { RouteDiagnosticsAndroidProbe(this).run(diagnosticsLocale); return }
        if (dpi) { DpiAndroidProbe(this).run(); return }
        if (recovery) { WbRecoveryProbe(this).run(); return }
        if (routes) { runRouteTest(); return }
        var main: MainActivity? = null
        var wb: Activity? = null
        var server: ManagedServer? = null
        var resultCode = Activity.RESULT_CANCELED
        val result = Bundle()
        val repo = ManagedServerRepository.get(targetContext)
        try {
            val id = runBlocking {
                repo.add(ManagedServer(name = "WB wizard test", host = "127.0.0.1", port = 1,
                    bearer = "test-only", spkiPin = "test-only"))
            }
            server = runBlocking { repo.getById(id) }
            main = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitForIdleSync()
            lateinit var vm: ManagedServerDetailViewModel
            lateinit var sheet: CreateProfileSheet
            runOnMainSync { vm = ViewModelProvider(main!!)[ManagedServerDetailViewModel::class.java] }
            check(runBlocking { vm.init(id) })
            runOnMainSync {
                sheet = CreateProfileSheet()
                sheet.showNow(main!!.supportFragmentManager, "wb-test")
                check(sheet.dialog!!.findViewById<View>(R.id.card_vless) != null)
                check(sheet.dialog!!.findViewById<View>(R.id.card_telemost) != null)
                check(sheet.dialog!!.findViewById<View>(R.id.card_wbstream).isShown)
            }
            waitForIdleSync()
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.getExternalFilesDir(null), "wb-create-wizard.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val monitor = addMonitor(CreateWbStreamActivity::class.java.name, null, false)
            runOnMainSync { sheet.dialog!!.findViewById<View>(R.id.card_wbstream).performClick() }
            wb = waitForMonitorWithTimeout(monitor, 60_000)
            check(wb is CreateWbStreamActivity) { "WB card did not open its creation wizard" }
            check(wb!!.intent.getLongExtra(CreateWbStreamActivity.EXTRA_SERVER_ID,0) == id)
            removeMonitor(monitor)
            resultCode = Activity.RESULT_OK
            result.putString("stream", "WB wizard PASS: 3 protocol cards; WB click opens creation activity with the selected server.\n")
        } catch (t: Throwable) {
            result.putString("stream", "WB wizard FAIL: ${t.stackTraceToString()}\n")
        } finally {
            runOnMainSync { wb?.finish(); main?.finish() }
            server?.let { runBlocking { repo.remove(it) } }
        }
        finish(resultCode, result)
    }
    private fun runRouteTest() {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: Activity? = null
        var routeStage = "startup"
        val service = Intent(targetContext, com.smarttools.netguard.service.RouteProbeVpnService::class.java)
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            check(android.net.VpnService.prepare(targetContext) == null) { "VPN test permission required" }
            targetContext.startService(service)
            val startup = com.smarttools.netguard.service.RouteProbeVpnService.ready.poll(30, java.util.concurrent.TimeUnit.SECONDS)
            check(startup == "ready") { startup ?: "VPN test service did not start" }
            val connectivity=targetContext.getSystemService(android.net.ConnectivityManager::class.java)
            val networkDeadline=System.nanoTime()+5_000_000_000L
            while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)!=true &&
                System.nanoTime()<networkDeadline) Thread.sleep(50)
            check(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true) { "Test VPN network not yet active" }
            for (address in listOf("203.0.113.2", "2001:db8::2")) {
                routeStage = "TCP $address"
                java.net.Socket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(java.net.InetSocketAddress(address,443),5000)
                    socket.getOutputStream().write("test".toByteArray())
                    val bytes=ByteArray(4);java.io.DataInputStream(socket.getInputStream()).readFully(bytes)
                    check(String(bytes)=="test")
                }
                java.net.DatagramSocket().use { socket ->
                    routeStage = "UDP $address"
                    socket.soTimeout=5000
                    socket.connect(java.net.InetAddress.getByName(address),443)
                    socket.send(java.net.DatagramPacket("udp".toByteArray(),3))
                    val packet=java.net.DatagramPacket(ByteArray(20),20);socket.receive(packet)
                    check(String(packet.data,0,packet.length)=="udp")
                }
            }
            val owners=(1..4).map { com.smarttools.netguard.service.RouteProbeVpnService.owners.poll(5,java.util.concurrent.TimeUnit.SECONDS) ?: error("missing source tuple") }
            check(owners.all { it.substringAfterLast('|').toIntOrNull()==android.os.Process.myUid() }) { "Incorrect connection owner: $owners; source=${com.smarttools.netguard.service.RouteProbeVpnService.sourceTuples}" }
            check(com.smarttools.netguard.service.RouteProbeVpnService.normalRequests.get()==0)
            check(com.smarttools.netguard.service.RouteProbeVpnService.vpnRequests.get()==4)
            result.putString("stream","PASS: native TUN TCP/UDP IPv4/IPv6 exact owner UID; all four forced to VPN, zero normal routes. $owners\n")
            code=Activity.RESULT_OK
        } catch(t:Throwable) {
            val probe = com.smarttools.netguard.service.RouteProbeVpnService
            result.putString("stream","FAIL at $routeStage: vpn=${probe.vpnRequests.get()}, normal=${probe.normalRequests.get()}, owners=${probe.owners}, source=${probe.sourceTuples}\n${t.stackTraceToString()}\n")
        }
        finally { targetContext.stopService(service); runOnMainSync { activity?.finish() } }
        finish(code,result)
    }
}
