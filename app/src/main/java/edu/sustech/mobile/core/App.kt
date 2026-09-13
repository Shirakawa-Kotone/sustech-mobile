package edu.sustech.mobile.core

import android.content.Context
import edu.sustech.mobile.pms.PmsApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide singletons: one HTTP client, one cookie store, one PMS API.
 *
 * Everything is exposed as a property so a fragment can write
 * `App.api.stations()` without threading a session object around.
 */
object App {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        AppConfig.init(appContext)
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

    val api: PmsApi by lazy { PmsApi(http) { AppConfig.baseUrl } }

    /** Bare hostname of the configured server — no scheme, no port. */
    fun host(): String = AppConfig.baseUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
}
