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
    private const val KEY_SIGNIN_NOTE = "last_signin_note"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        // Rewrite a hand-edited address once instead of leaving a value on disk
        // that the app would never actually use (e.g. plain http to a campus host).
        val stored = prefs.getString(KEY_BASE_URL, "").orEmpty()
        val normalised = normalise(stored)
        if (stored.isNotEmpty() && stored != normalised) {
            prefs.edit().putString(KEY_BASE_URL, normalised).apply()
        }
    }

    private val prefs
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Address of the print server. A value without a scheme is assumed to be
     * HTTPS, and plain HTTP survives only for a local test server (emulator
     * host alias, localhost, private address) — a campus host over plain HTTP
     * could never work anyway, because the platform blocks cleartext to it.
     */
    var baseUrl: String
        get() = normalise(prefs.getString(KEY_BASE_URL, "") ?: "")
        set(value) = prefs.edit().putString(KEY_BASE_URL, normalise(value)).apply()

    /**
     * Normalises whatever the user typed into a usable base URL: adds the
     * scheme, drops a trailing slash, and upgrades plain HTTP to HTTPS unless
     * the host is a local test server. Never returns something empty.
     */
    fun normalise(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return DEFAULT_BASE_URL
        if (!value.contains("://")) value = "https://$value"
        val scheme = value.substringBefore("://").lowercase()
        val host = value.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (scheme == "http" && !isLocalHost(host)) {
            value = "https://" + value.substringAfter("://")
        }
        return value.trimEnd('/')
    }

    /** True for hosts where plain HTTP is acceptable (mock server, LAN). */
    fun isLocalHost(host: String): Boolean {
        val name = host.trim().lowercase()
        return name == "10.0.2.2" || name == "10.0.3.2" || name == "localhost" || name == "127.0.0.1" ||
            name == "0.0.0.0" || name.endsWith(".local") || name.startsWith("192.168.") ||
            name.startsWith("10.") || Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(name)
    }

    /**
     * Why the last sign-in attempt ended the way it did (empty when it simply
     * worked). Written by the sign-in screen, shown on the Account tab: a
     * failure should never be a mystery the user cannot inspect.
     */
    var lastSignInNote: String
        get() = prefs.getString(KEY_SIGNIN_NOTE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SIGNIN_NOTE, value).apply()

    /** Last account used for the password login flow. Never a password. */
    var lastUsername: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    fun resetBaseUrl() {
        prefs.edit().remove(KEY_BASE_URL).apply()
    }
}
