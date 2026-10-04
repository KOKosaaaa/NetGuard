package com.smarttools.netguard

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.model.*
import com.smarttools.netguard.service.TunnelVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.File
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in public HTTPS UI integration. Injected session + Java passthrough SOCKS, NOT native VPN/WB. */
internal class SpeedUiPublicAndroidProbe(private val test: Instrumentation) {
    private fun ui(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
    private fun await(ms: Long, label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!condition()) { check(System.nanoTime() < end) { label }; Thread.sleep(40) }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(name: String) = TunnelVpnService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(null) as MutableStateFlow<T>
    private class PassThrough(port: Int) : AutoCloseable {
        val connected = AtomicInteger()
        private val peers = ConcurrentHashMap.newKeySet<Socket>()
        private val pool = Executors.newCachedThreadPool()
        private val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        @Volatile private var closing = false
        val errors = ConcurrentLinkedQueue<String>()
        init { pool.execute { while (!closing) {
            val local = try { server.accept() } catch (_: java.io.IOException) { break }
            peers.add(local)
            pool.execute {
                var remote: Socket? = null
                var phase="greeting"
                try {
                    local.soTimeout = 15_000
                    val input = DataInputStream(local.getInputStream()); val out = local.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                    check(0.toByte() in methods); out.write(byteArrayOf(5, 0)); out.flush()
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                    input.readUnsignedByte(); check(input.readUnsignedByte() == 3) { "Expected remote hostname routing" }
                    val bytes = ByteArray(input.readUnsignedByte()); input.readFully(bytes)
                    val host = String(bytes, Charsets.US_ASCII); val targetPort = input.readUnsignedShort()
                    check(targetPort == 443 && host in setOf("speed.cloudflare.com", "fra.speedtest.clouvider.net", "www.librespeed.fi"))
                    val peer = Socket(); remote = peer; peers.add(peer)
                    phase="upstream-connect"
                    peer.connect(InetSocketAddress(host, targetPort), 10_000)
                    peer.soTimeout = 15_000
                    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); out.flush(); connected.incrementAndGet(); phase="relay"
                    val uplink = pool.submit { try { input.copyTo(peer.getOutputStream()) } finally { runCatching { peer.shutdownOutput() } } }
                    try { peer.getInputStream().copyTo(out) } finally { local.close(); peer.close(); uplink.cancel(true) }
                } catch (e: Throwable) {
                    // Expected EOF/reset on keep-alive disposal is not a relay failure.
                    if (!closing && (phase!="relay" || e !is java.io.IOException)) errors.add(phase+":"+e.javaClass.simpleName + ":" + e.message)
                } finally { runCatching { local.close() }; peers.remove(local); remote?.let { runCatching { it.close() }; peers.remove(it) } }
            }
        } } }
        override fun close() { closing = true; server.close(); peers.forEach { runCatching { it.close() } }; pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS)) { "Passthrough tasks leaked" } }
    }
    fun run() {
        val app = test.targetContext.applicationContext as App
        var a: MainActivity? = null; var id = -1L; var success = false; var admitted = false
        var saved: Map<String, *>? = null; var proxy: PassThrough? = null
        val report = StringBuilder("SCOPE actual Home/VM/production engine/public TLS via Java SOCKS; injected session, NOT native VPN/WB or phone evidence\n")
        val connection = flow<ConnectionState>("_connectionState"); val active = flow<Long>("_activeProfileId")
        val oldConnection = connection.value; val oldActive = active.value
        try {
            test.waitForIdleSync()
            await(8000,"Application bootstrap") { runCatching { app.database; true }.getOrDefault(false) }
            check(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(oldConnection is ConnectionState.Disconnected && CredentialManager.getPort() == null) { "Idle owned emulator only" }
            admitted = true; saved = app.getPreferences().all.toMap()
            app.getPreferences().edit().putBoolean("onboarding_done",true).apply()
            app.saveSettings(app.loadSettings().copy(showSpeedTest = true, showConnectionMap = false))
            id = runBlocking { app.database.profileDao().insert(ServerProfile(name="QA public measurement UI", protocol=Protocol.WBSTREAM, address="qa-speed-ui-no-native")) }
            proxy = PassThrough(CredentialManager.generate().third)
            active.value = id; connection.value = ConnectionState.Connected()
            a = test.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = a!!; val vm = activity.mainViewModel
            test.waitForIdleSync()
            await(8000,"Speed button not ready") { var yes=false;ui {yes=activity.findViewById<View>(R.id.btn_speed_test)?.isShown==true};yes }
            ui { check(activity.findViewById<View>(R.id.btn_speed_test).performClick()) }
            await(75_000,"UI speed job did not finish") { !vm.speedTesting.value && (vm.speedResult.value!=null || vm.speedError.value!=null) }
            val result = checkNotNull(vm.speedResult.value) { "Actual UI failed: ${vm.speedError.value}" }
            check(result.downloadMbps>0 && result.uploadMbps>0 && !result.timedOut) { "Incomplete actual UI measurement: $result error=${vm.speedError.value}" }
            check(proxy.connected.get()>0 && proxy.errors.isEmpty()) { "SOCKS path not proven or fixture failed: ${proxy.errors}" }
            await(8000,"Positive result not rendered in actual sheet") {
                var yes=false;ui {
                    val home=(activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!
                    val sheet=home.javaClass.getDeclaredField("speedSheet").apply{isAccessible=true}.get(home) as Dialog
                    val text=sheet.findViewById<TextView>(R.id.tv_speed_result)
                    yes=sheet.isShowing && text.isShown && text.text.isNotBlank()
                };yes
            }
            report.append("MEASUREMENT complete: $result; SOCKS connections=${proxy.connected.get()}\n")
            await(8000,"Positive result window not visible to accessibility; actual=${test.uiAutomation.rootInActiveWindow?.packageName}") {
                test.uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName
            }
            test.uiAutomation.takeScreenshot()?.let { bitmap -> try { File(app.getExternalFilesDir(null),"speed-ui-public-result.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() } }
            report.append("PASS actual click -> production ViewModel -> verified public HTTPS over SOCKS -> displayed completed DL/UL: $result; SOCKS connections=${proxy.connected.get()}\n")
            success=true
        } catch(t:Throwable) {
            report.append("FAIL ${t.stackTraceToString()}\nSOCKS connected=${proxy?.connected?.get()} errors=${proxy?.errors}\n")
            com.smarttools.netguard.service.LogBuffer.snapshot().filter { it.message.startsWith("[speed-test]") }.takeLast(24).forEach { report.append(it.message).append('\n') }
            if(proxy!=null && a?.mainViewModel?.speedError?.value==com.smarttools.netguard.viewmodel.MainViewModel.SpeedError.FAILED) {
                val snapshot=CredentialManager.speedProxy(true)
                if(snapshot!=null)runCatching { runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.smarttools.netguard.util.SpeedTester.run(snapshot) {}
                } } }.onSuccess { report.append("MAIN diagnostic returned $it\n") }
                    .onFailure { report.append("MAIN diagnostic exception ${it.stackTraceToString()}\n") }
            }
        }
        finally {
            if(admitted) {
                ui { a?.mainViewModel?.cancelSpeedTest();connection.value=ConnectionState.Disconnected;a?.finish() }
                runCatching { proxy?.close() }.onFailure { success=false;report.append("FAIL cleanup: ${it.message}\n") }
                CredentialManager.clear();connection.value=oldConnection;active.value=oldActive
                if(id>0)runBlocking { app.database.profileDao().deleteById(id) }
                saved?.let { values -> val edit=app.getPreferences().edit().clear();values.forEach { (k,v)->when(v) {
                    is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v)
                    is Set<*>->{@Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>)}
                } };check(edit.commit()) }
            }
        }
        test.finish(if(success)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("stream",report.toString())})
    }
}
