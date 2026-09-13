package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo

/**
 * CAS sign-in inside a WebView: the user authenticates on the real SUSTech
 * page, the PMS back end links the CAS identity to the print account, and the
 * app only lifts the resulting `OSESSIONID` cookie out of the WebView.
 */
class CasLoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_cas_login)

        web = findViewById(R.id.cas_web)
        status = findViewById(R.id.cas_status)
        progress = findViewById(R.id.cas_progress)

        val entry = AppConfig.baseUrl + SERVICE_PATH

        CookieManager.getInstance().setAcceptCookie(true)
        App.cookies.headerFor(App.host()).takeIf { it.isNotEmpty() }?.let {
            CookieManager.getInstance().setCookie(entry, it)
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (url != null) onLanded(url)
            }
        }
        web.loadUrl(entry)
    }

    /** Called after every page load; only the PMS host counts as "landed". */
    private fun onLanded(url: String) {
        if (settled || !url.contains(App.host()) || url.contains("cas.sustech.edu.cn")) return
        val header = CookieManager.getInstance().getCookie(url).orEmpty()
        if (!header.contains("OSESSIONID", ignoreCase = true)) return

        settled = true
        status.setText(R.string.empty_loading)
        App.cookies.replaceFromHeader(header, App.host())
        runIo(
            block = { App.api.check() },
            onOk = { account ->
                setResult(RESULT_OK)
                finish()
            },
            onErr = { error ->
                settled = false
                status.text = getString(R.string.cas_failed, error.friendly(this))
            },
        )
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private companion object {
        /** The print page the PMS front end serves, same URL the Python client uses. */
        const val SERVICE_PATH = "/client/new/cprintPc/"
    }
}
