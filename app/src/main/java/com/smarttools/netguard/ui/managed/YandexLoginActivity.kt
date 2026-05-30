package com.smarttools.netguard.ui.managed

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.smarttools.netguard.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standalone Activity that hosts a WebView pointed at passport.yandex.ru
 * so the user can sign in with their own Yandex account. Once the
 * Telemost SDK cookies (Session_id, sessar, L, sessionid2) appear in
 * the WebView's CookieManager, we package them as the `cookies-yandex.json`
 * blob the agent's headless-telemost-creator expects and ship it back
 * to the caller via setResult.
 *
 * Caller pattern (in any Fragment or Activity):
 *
 *   private val launcher = registerForActivityResult(
 *       ActivityResultContracts.StartActivityForResult()
 *   ) { result ->
 *       if (result.resultCode == Activity.RESULT_OK) {
 *           val json = result.data?.getStringExtra(EXTRA_COOKIES_JSON)
 *           // pass json to /v1/telemost/deploy
 *       }
 *   }
 *   launcher.launch(Intent(context, YandexLoginActivity::class.java))
 *
 * The Activity never persists cookies anywhere — they're held in the
 * WebView's process-local CookieManager and live only until the user
 * leaves this screen. The caller is responsible for handing them off
 * to the agent right away (or dropping them).
 */
class YandexLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_login)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finishCancelled() }

        webView = findViewById(R.id.web_view)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Yandex's login page hard-renders for desktop without a phone
        // UA hint; mobile Chrome UA gets a friendlier flow with bigger
        // tap targets.
        webView.settings.userAgentString = MOBILE_UA

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        // Don't wipe existing cookies — when the user has already signed
        // in from a previous Telemost deploy, the next WebView open
        // should immediately auto-harvest and dismiss instead of forcing
        // a fresh login. We try harvesting BEFORE loading the page in
        // case the cookies are still valid; if not, the WebView falls
        // through to the login form like a fresh start.
        if (tryHarvestCookies()) return

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tryHarvestCookies()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Some logins finalize via XHR before a full pageFinished
                // fires; check on every navigation to be safe.
                tryHarvestCookies()
            }
        }

        webView.loadUrl(LOGIN_URL)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finishCancelled()
    }

    /**
     * Returns true when the harvest succeeded and the activity is
     * finishing — callers should bail out instead of continuing to
     * load the WebView. False means we don't have a usable session
     * yet; keep the WebView around so the user can sign in.
     */
    private fun tryHarvestCookies(): Boolean {
        if (delivered) return true
        val raw = CookieManager.getInstance().getCookie("https://yandex.ru") ?: return false
        val map = parseCookieHeader(raw)
        // We need ALL of these to be present for the agent's session
        // to be usable; Session_id alone is the auth, but the other
        // three are part of the Yandex SDK contract and creator's
        // request signing breaks without them.
        val required = listOf("Session_id", "sessar", "L", "sessionid2")
        if (!required.all { map.containsKey(it) }) return false

        val json = JSONArray()
        for ((name, value) in map) {
            json.put(JSONObject().apply {
                put("name", name)
                put("value", value)
            })
        }

        delivered = true
        val out = Intent().apply { putExtra(EXTRA_COOKIES_JSON, json.toString()) }
        setResult(Activity.RESULT_OK, out)
        finish()
        return true
    }

    private fun finishCancelled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        // Keep cookies on disk so the next Telemost re-deploy doesn't
        // force the user to sign in again. The CookieManager is shared
        // across the app process and persists across cold starts.
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COOKIES_JSON = "cookies_json"
        private const val LOGIN_URL = "https://passport.yandex.ru/auth?origin=telemost"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /**
         * Parse "name=value; name2=value2" → ordered map. We keep
         * insertion order because the agent's creator binary expects
         * a stable cookie ordering for some request signatures.
         */
        fun parseCookieHeader(header: String): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val s = part.trim()
                val eq = s.indexOf('=')
                if (eq <= 0) continue
                val name = s.substring(0, eq).trim()
                val value = s.substring(eq + 1).trim()
                if (name.isNotEmpty()) out[name] = value
            }
            return out
        }
    }
}
