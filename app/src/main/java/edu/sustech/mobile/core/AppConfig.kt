package edu.sustech.mobile.core

import android.content.Context
import edu.sustech.mobile.BuildConfig

/**
 * Single source of truth for the server the app talks to and the session
 * cookies it holds.
 *
 * The base URL is overridable so the app can be exercised off campus against
 * the local mock server (tools/mock_pms.py) without recompiling.
 */
object AppConfig {

    /**
     * Server the app starts with. Defaults to the real PMS; a debug build can
     * point elsewhere with `-PpmsBaseUrl=...` (see app/build.gradle.kts).
     */
    val DEFAULT_BASE_URL: String = BuildConfig.DEFAULT_SERVER_URL

    private const val PREFS = "sustech_mobile"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "last_username"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL
        set(value) {
            val clean = value.trim().trimEnd('/')
            prefs.edit().putString(KEY_BASE_URL, if (clean.isEmpty()) DEFAULT_BASE_URL else clean).apply()
        }

    /** Last account used for the password login flow. Never a password. */
    var lastUsername: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    fun resetBaseUrl() {
        prefs.edit().remove(KEY_BASE_URL).apply()
    }
}
