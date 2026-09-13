package edu.sustech.mobile.core

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import edu.sustech.mobile.R
import edu.sustech.mobile.tis.TisApi
import edu.sustech.mobile.pms.PmsApi
import edu.sustech.mobile.weather.WeatherClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide singletons: one HTTP client, one cookie jar, one API per
 * service.
 *
 * The cookie jar is the whole session story — it is host-scoped, so a PMS
 * session and a TIS session coexist and survive an app restart, exactly as
 * separate browser tabs would.
 */
object App {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        // The UI has one light palette; force it so a device in dark mode cannot
        // hand the views dark tints on top of white surfaces.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        AppConfig.init(appContext)
        Credentials.init(appContext)
    }

    val cookies: CookieStore by lazy { CookieStore(appContext) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookies)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client that does not follow redirects: the CAS handshake hands the
     * ticket back in a `Location` header, and step 4 needs that value.
     */
    val httpNoRedirect: OkHttpClient by lazy { http.newBuilder().followRedirects(false).build() }

    val api: PmsApi by lazy { PmsApi(http) { AppConfig.baseUrl } }

    val tis: TisApi by lazy { TisApi(http) }

    val weather: WeatherClient by lazy { WeatherClient(http) }

    /** Bare hostname of the configured print server. */
    fun host(): String = Hosts.host(AppConfig.baseUrl)

    fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    fun toast(resId: Int) {
        Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show()
    }
}
