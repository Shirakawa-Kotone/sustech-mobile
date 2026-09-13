package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Hosts

/**
 * CAS sign-in for any SUSTech service, in a WebView.
 *
 * The user authenticates on the real sign-in page; the service then sets its
 * own session cookie, and the app lifts only that cookie out of the WebView.
 * No password is ever typed into the app or stored by it — this one activity
 * serves every service, current and future.
 *
 * Extras: [EXTRA_URL] entry point, [EXTRA_COOKIE] the cookie name that proves
 * the landing (e.g. `OSESSIONID` for print, `SESSION` for TIS).
 */
class WebLoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var url: String
    private lateinit var cookieName: String
    private lateinit var host: String
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_web_login)

        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        cookieName = intent.getStringExtra(EXTRA_COOKIE) ?: return finish()
        host = Hosts.host(url)

        web = findViewById(R.id.web)
        status = findViewById(R.id.web_status)
        progress = findViewById(R.id.web_progress)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(R.string.service_login_title, host)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        CookieManager.getInstance().setAcceptCookie(true)
        App.cookies.headerFor(host).takeIf { it.isNotEmpty() }?.let {
            CookieManager.getInstance().setCookie(url, it)
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, landedUrl: String?) {
                progress.visibility = View.GONE
                if (landedUrl != null) onLanded(landedUrl)
            }
        }
        web.loadUrl(url)
    }

    /** Called after every page load; only the service's own host counts. */
    private fun onLanded(landedUrl: String) {
        if (settled) return
        if (!landedUrl.contains(host) || landedUrl.contains(Hosts.CAS)) return

        val header = CookieManager.getInstance().getCookie(landedUrl).orEmpty()
        if (!header.contains(cookieName, ignoreCase = true)) return

        settled = true
        status.setText(R.string.empty_loading)
        App.cookies.replaceFromHeader(header, host)
        setResult(RESULT_OK)
        finish()
    }

    @Deprecated("WebView history is the expected back behavior on this screen")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_COOKIE = "cookie"
    }
}
