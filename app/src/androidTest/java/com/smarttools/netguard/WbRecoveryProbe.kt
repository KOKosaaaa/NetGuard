package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerRepository
import com.smarttools.netguard.ui.managed.CreateWbStreamActivity
import com.smarttools.netguard.ui.managed.CreateWbStreamViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/** Real Activity/WebView/button and pinned HTTPS requests, using only test login data.
 * Reflection replaces only a hung JS callback; production has no test hooks. */
internal class WbRecoveryProbe(private val test: Instrumentation) {
    private lateinit var activity: CreateWbStreamActivity
    private lateinit var vm: CreateWbStreamViewModel
    private fun ui(block: () -> Unit) = test.runOnMainSync(block)
    private fun field(name: String) = CreateWbStreamActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun button() = field("action").get(activity) as MaterialButton
    private fun status() = (field("status").get(activity) as TextView).text.toString()
    private fun web() = field("web").get(activity) as WebView
    private fun await(description: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            ui { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        error("Timed out: $description")
    }
    private fun dismiss() {
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        test.waitForIdleSync()
    }
    private fun page(url: String, script: String = "localStorage.clear()") {
        ui {
            web().stopLoading()
            web().settings.blockNetworkLoads = true
            web().loadDataWithBaseURL(url, "<html><head><script>$script;window.fixtureReady=true;</script></head><body>Local test page</body></html>", "text/html", "UTF-8", url)
        }
        val latch = CountDownLatch(1)
        await("WebView URL") { web().url == url && web().progress == 100 }
        ui { web().evaluateJavascript("window.fixtureReady") { if (it == "true") latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS)) { "Fixture JS did not load" }
    }

    fun run() {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val repo = ManagedServerRepository.get(test.targetContext)
        val server = LocalAgent()
        var id = 0L
        try {
            id = runBlocking { repo.add(ManagedServer(name = "WB recovery test", host = "127.0.0.1", port = server.socket.localPort,
                bearer = "fixture", spkiPin = server.pin)) }
            activity = test.startActivitySync(Intent(test.targetContext, CreateWbStreamActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(CreateWbStreamActivity.EXTRA_SERVER_ID, id)
                .putExtra(CreateWbStreamActivity.EXTRA_ROOM, ROOM)) as CreateWbStreamActivity
            ui { vm = ViewModelProvider(activity)[CreateWbStreamViewModel::class.java] }
            await("server preparation") { vm.state.value.prepared && field("loaded").getBoolean(activity) }

            page("https://auth-stream.wb.ru/login")
            ui {
                check(field("room").get(activity) == ROOM) { "Login redirect lost room" }
                check(button().isEnabled) { "Login redirect silently disabled button" }
                button().performClick()
                check(status().contains("заверши вход"))
            }
            dismiss()

            // Remove the original intent to prove the saved selection survives recreation.
            val monitor = test.addMonitor(CreateWbStreamActivity::class.java.name, null, false)
            ui { activity.intent.removeExtra(CreateWbStreamActivity.EXTRA_ROOM); activity.recreate() }
            activity = test.waitForMonitorWithTimeout(monitor, 15_000) as? CreateWbStreamActivity ?: error("Recreation failed")
            test.removeMonitor(monitor)
            ui { vm = ViewModelProvider(activity)[CreateWbStreamViewModel::class.java] }
            await("recreated UI") { field("loaded").getBoolean(activity) }
            ui { check(field("room").get(activity) == ROOM) { "Recreation lost room" } }

            page("https://stream.wb.ru/")
            ui { check(button().isEnabled); button().performClick() }
            await("missing storage feedback") { status().contains("[WB_STORAGE]") && button().isEnabled }
            dismiss()

            ui { field("room").set(activity, null); button().performClick() }
            // The missing-room branch must open an actual dialog.
            await("missing-room dialog") {
                test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Открыть комнату")?.isNotEmpty() == true
            }
            dismiss()
            ui { field("room").set(activity, ROOM) }

            lateinit var realWeb: WebView
            lateinit var heldWeb: HeldWebView
            ui {
                realWeb = web()
                heldWeb = HeldWebView()
                field("web").set(activity, heldWeb)
                button().performClick()
                check(status() == "Проверяю вход WB…" && !button().isEnabled)
                // A history callback must not re-enable the button while JS is pending.
                realWeb.webViewClient.doUpdateVisitedHistory(realWeb, "https://stream.wb.ru/", false)
                check(!button().isEnabled)
            }
            await("JS watchdog", 12_000) { status().contains("не ответила за 8 секунд") && button().isEnabled }
            ui { heldWeb.callback!!.onReceiveValue("null"); check(status().contains("не ответила за 8 секунд")) }
            dismiss()
            ui {
                button().performClick()
                heldWeb.currentUrl = "https://auth-stream.wb.ru/"
                heldWeb.callback!!.onReceiveValue("null")
            }
            await("navigation feedback") { status().contains("изменилась во время проверки") && button().isEnabled }
            dismiss()
            ui { field("web").set(activity, realWeb); heldWeb.destroy() }

            // Use the real Chromium localStorage/cookie APIs and real pinned HTTPS client.
            val cookieReady = CountDownLatch(1)
            ui { CookieManager.getInstance().setCookie("https://auth-stream.wb.ru/v2/auth/slide-v3", "wbx-refresh=fixture-refresh; Path=/v2/auth; Secure; HttpOnly") { check(it); cookieReady.countDown() } }
            check(cookieReady.await(5, TimeUnit.SECONDS))
            ui {
                val cookies = CookieManager.getInstance()
                check(!cookies.getCookie("https://auth-stream.wb.ru/").orEmpty().contains("wbx-refresh=")) { "Fixture must reproduce a path-scoped cookie invisible at root" }
                check(cookies.getCookie("https://auth-stream.wb.ru/v2/auth/slide-v3").orEmpty().contains("wbx-refresh=fixture-refresh"))
            }
            page("https://stream.wb.ru/", "localStorage.setItem('wb_auth_api_device_id','fixture-device');localStorage.setItem('wb_auth_auth_slice',JSON.stringify({accessToken:'fixture-access'}))")
            ui { button().performClick() }
            check(server.deployReceived.await(15, TimeUnit.SECONDS)) { "Button did not submit deployment" }
            await("deployment progress") { vm.state.value.busy && status().contains("Передаю") && !button().isEnabled }
            val body = JSONObject(requireNotNull(server.body))
            check(body.getString("room") == ROOM && body.getBoolean("update"))
            check(body.getJSONObject("owner_session").getString("device_id") == "fixture-device")
            check(body.getJSONObject("owner_session").getJSONObject("cookies").getString("wbx-refresh") == "fixture-refresh")
            server.complete.countDown()
            await("background handoff", 30_000) { vm.state.value.background && button().isEnabled && button().text == "Продолжить в фоне" }
            ui { button().performClick() }
            await("wizard closed") { activity.isDestroyed }
            activity = test.startActivitySync(Intent(test.targetContext, CreateWbStreamActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(CreateWbStreamActivity.EXTRA_SERVER_ID, id)
                .putExtra(CreateWbStreamActivity.EXTRA_ROOM, ROOM)) as CreateWbStreamActivity
            ui { vm = ViewModelProvider(activity)[CreateWbStreamViewModel::class.java] }
            await("resume same background task") { vm.state.value.background }
            check(server.deployCount == 1) { "Reopening submitted another deployment" }
            server.allowTask.countDown()
            check(server.roomChecked.await(20,TimeUnit.SECONDS)) { "Worker did not inspect live room" }
            check(runBlocking { (test.targetContext.applicationContext as App).profileRepository.getAll().none { it.address == ROOM } }) { "Unconnected room was reported ready" }
            server.publisherReady = true
            await("success", 45_000) { vm.state.value.uri != null && status().contains("включено") }
            check(server.deployCount == 1)
            result.putString("stream", "PASS: login/HttpOnly cookie capture; JS timeout/navigation; background handoff; close and reopen resumes same persisted task without POST; profile absent until live owner and publisher ready; automatic completion, exactly one request.\n")
            code = Activity.RESULT_OK
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
        } finally {
            server.complete.countDown()
            server.allowTask.countDown()
            server.socket.close()
            if (::activity.isInitialized) ui { activity.finish() }
            runBlocking {
                repo.getById(id)?.let { repo.remove(it) }
                val profiles = (test.targetContext.applicationContext as App).profileRepository
                profiles.getAll().filter { it.address == ROOM }.forEach { profiles.delete(it) }
            }
        }
        test.finish(code, result)
    }

    private inner class HeldWebView : WebView(test.targetContext) {
        var currentUrl = "https://stream.wb.ru/"
        var callback: ValueCallback<String>? = null
        override fun getUrl(): String = currentUrl
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) { callback = resultCallback }
    }

    private inner class LocalAgent {
        val socket: SSLServerSocket
        val pin: String
        val deployReceived = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val allowTask = CountDownLatch(1)
        val roomChecked = CountDownLatch(1)
        @Volatile var publisherReady = false
        @Volatile var body: String? = null
        @Volatile var deployCount = 0
        init {
            val store = KeyStore.getInstance("PKCS12").apply {
                test.context.assets.open("wb-recovery-test.p12").use { load(it, "test-only".toCharArray()) }
            }
            pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").publicKey.encoded).joinToString("") { "%02x".format(it) }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only".toCharArray()) }
            socket = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
                .createServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            thread(isDaemon = true, name = "WB fixture agent") {
                while (!socket.isClosed) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    connection.use {
                        runCatching {
                            val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
                            val path = input.readLine().split(' ')[1]
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            val data = CharArray(length)
                            var read = 0
                            while (read < length) { val count = input.read(data, read, length - read); check(count > 0); read += count }
                            val response = when (path) {
                                // This fixture exercises room recovery, not server-binary upgrade.
                                "/v1/wbstream/health" -> """{"supported":true,"transport_revision":14}"""
                                "/v1/wbstream/deploy" -> {
                                    body = String(data); deployCount++; deployReceived.countDown()
                                    check(complete.await(30, TimeUnit.SECONDS))
                                    """{"task_id":"fixture-task","status":"pending"}"""
                                }
                                "/v1/tasks/fixture-task" -> {
                                    check(allowTask.await(30,TimeUnit.SECONDS))
                                    """{"task_id":"fixture-task","type":"wbstream","status":"done","started_at":"2026-09-27T00:00:00Z","result":{"publisher_ready":false,"recovery_enabled":true,"rooms":["$ROOM"]}}"""
                                }
                                "/v1/wbstream/rooms" -> {
                                    val ready = publisherReady
                                    roomChecked.countDown()
                                    """{"installed":1,"instances":[{"room":"$ROOM","active":$ready,"owner_state":"${if(ready) "hosting" else "reconnecting"}"}]}"""
                                }
                                else -> error("Unexpected fixture path: $path")
                            }
                            val bytes = response.toByteArray()
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                        }
                    }
                }
            }
        }
    }
    companion object { const val ROOM = "https://stream.wb.ru/room/netguard-local-regression-test" }
}
