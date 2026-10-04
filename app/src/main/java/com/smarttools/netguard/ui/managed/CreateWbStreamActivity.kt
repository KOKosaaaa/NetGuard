package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.agent.WbOwnerSession
import com.smarttools.netguard.model.WbStreamLink
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** One-time WB owner sign-in; the explicit enable action sends only WB login
 * state to the user's authenticated managed server for unattended recovery. */
class CreateWbStreamActivity : AppCompatActivity() {
    private fun l10n(id: Int, vararg args: Any): String = com.smarttools.netguard.util.LocalizedResources.string(this, id, *args)

    private val vm: CreateWbStreamViewModel by viewModels()
    private lateinit var web: WebView
    private lateinit var action: MaterialButton
    private lateinit var status: TextView
    private var room: String? = null
    private var loaded = false
    private var successShown = false
    private var readingSession = false
    private var pageRevision = 0
    private var feedback: String? = null
    private val serverId get() = intent.getLongExtra(EXTRA_SERVER_ID, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        room = canonicalRoom(savedInstanceState?.getString(EXTRA_ROOM) ?: intent.getStringExtra(EXTRA_ROOM))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_1)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { exitWizard() }
            menu.add(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_2)).setOnMenuItemClickListener {
                if (vm.state.value.prepared && !isBusy()) web.loadUrl(room ?: "https://stream.wb.ru/")
                true
            }
            menu.add(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_3)).setOnMenuItemClickListener {
                if (!isBusy()) runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://stream.wb.ru/")))
                }
                true
            }
            menu.add(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_4)).setOnMenuItemClickListener {
                if (vm.state.value.prepared && !isBusy()) pasteRoom()
                true
            }
        }
        root.addView(toolbar)
        status = TextView(this).apply { setPadding(24,12,24,12) }
        root.addView(status)
        action = MaterialButton(this).apply {
            text = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_5); isEnabled = false
            setOnClickListener { if (vm.state.value.background) finish() else if (!vm.state.value.prepared) vm.prepare(serverId, room) else enableRecovery() }
        }
        root.addView(action)
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isBusy() && web.canGoBack()) web.goBack() else exitWizard()
            }
        })
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { pageRevision++ }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { pageRevision++; updateRoom(url) }
            override fun onPageFinished(view: WebView, url: String?) { updateRoom(url) }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !isBusy()) {
                    feedback = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_6)
                    render()
                }
            }
        }
        lifecycleScope.launch {
            vm.state.collect { state ->
                if (state.busy || state.error != null || state.uri != null) feedback = null
                render()
                if (state.prepared && !loaded) {
                    loaded = true
                    if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(room ?: "https://stream.wb.ru/")
                }
                if (state.uri != null && !successShown) {
                    successShown = true
                    MaterialAlertDialogBuilder(this@CreateWbStreamActivity)
                        .setTitle(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_7))
                        .setMessage(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_8))
                        .setPositiveButton(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_9)) { _,_ -> setResult(RESULT_OK); finish() }
                        .setCancelable(false).show()
                }
            }
        }
        vm.prepare(serverId, room)
    }
    private fun updateRoom(url: String?) {
        // WB sends the owner through its login/home pages. Keep the selected room.
        canonicalRoom(url)?.let { room = it }
        render()
    }
    private fun canonicalRoom(url: String?) = url?.let { runCatching { WbStreamLink.parse(it).links.single() }.getOrNull() }
    private fun isBusy() = readingSession || vm.state.value.busy
    private fun render() {
        val state = vm.state.value
        status.text = if (readingSession) l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_10) else feedback ?: state.error ?: state.message
        action.text = when {
            readingSession -> l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_10)
            state.background -> l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_11)
            state.busy && state.prepared -> l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_12)
            !state.prepared && state.error != null -> l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_13)
            else -> l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_5)
        }
        // A missing room/login is actionable: explain it on tap instead of disabling silently.
        action.isEnabled = state.background || (!isBusy() && state.uri == null && (state.prepared || state.error != null))
    }
    private fun showProblem(message: String) {
        feedback = message
        render()
        MaterialAlertDialogBuilder(this).setTitle(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_14))
            .setMessage(message).setPositiveButton(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_15), null).show()
    }
    private fun pasteRoom() {
        val input = EditText(this).apply { hint = "https://stream.wb.ru/room/…" }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_16))
            .setMessage(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_17))
            .setView(input).setPositiveButton(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_18), null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = runCatching { WbStreamLink.parse(input.text.toString()).links.single() }.getOrNull()
                if (link == null) input.error = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_19)
                else { room = link; feedback = null; render(); dialog.dismiss(); web.loadUrl(link) }
            }
        }
        dialog.show()
    }
    private fun exitWizard() {
        if (vm.state.value.background) { finish(); return }
        if (isBusy() || room != null && vm.state.value.uri == null) {
            MaterialAlertDialogBuilder(this).setTitle(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_20))
                .setMessage(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_21))
                .setNegativeButton(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_22),null).setPositiveButton(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_23)) { _,_ -> finish() }.show()
        } else finish()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString(EXTRA_ROOM, room); web.saveState(out); super.onSaveInstanceState(out) }
    override fun onDestroy() { super.onDestroy(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
    private fun enableRecovery() {
        if (isBusy() || vm.state.value.uri != null) return
        val link = room
        if (link == null) { pasteRoom(); return }
        val expectedUrl = web.url
        if (!com.smarttools.netguard.agent.WbOwnerSession.isWbOrigin(expectedUrl)) {
            showProblem(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_24))
            return
        }
        val expectedRevision = pageRevision
        feedback = null
        readingSession = true
        render()
        lifecycleScope.launch {
            try {
                val encoded = withTimeout(8_000) {
                    suspendCancellableCoroutine<String> { continuation ->
                        web.evaluateJavascript("""JSON.stringify({device_id:localStorage.getItem('wb_auth_api_device_id')||'',auth_slice:localStorage.getItem('wb_auth_auth_slice')||''})""") { value ->
                            if (continuation.isActive) continuation.resume(value ?: "null")
                        }
                    }
                }
                if (web.url != expectedUrl || pageRevision != expectedRevision) {
                    showProblem(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_25))
                    return@launch
                }
                val storage = runCatching { org.json.JSONTokener(encoded).nextValue() as String }.getOrNull()
                    ?: throw WbOwnerSession.CaptureException(WbOwnerSession.Reason.STORAGE)
                val cookies = CookieManager.getInstance()
                val session = WbOwnerSession.build(web.url, storage,
                    WbOwnerSession.cookieUrls.map { cookies.getCookie(it).orEmpty() })
                if (!vm.deploy(link, org.json.JSONObject(session))) {
                    showProblem(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_26))
                }
            } catch (_: TimeoutCancellationException) {
                showProblem(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_27))
            } catch (e: CancellationException) {
                throw e
            } catch (e: WbOwnerSession.CaptureException) {
                showProblem(e.reason.message(this@CreateWbStreamActivity))
            } catch (_: Exception) {
                showProblem(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_activity_28))
            } finally {
                readingSession = false
                render()
            }
        }
    }
    companion object { const val EXTRA_SERVER_ID = "server_id"; const val EXTRA_ROOM = "room" }
}
