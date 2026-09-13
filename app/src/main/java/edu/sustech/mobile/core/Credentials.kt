package edu.sustech.mobile.core

import android.content.Context

/**
 * The one credential pair the app keeps: the school account.
 *
 * Entered once (there is no per-service sign-in any more) and reused for every
 * service afterwards — the same contract as `sustech_survival`'s
 * `credentials.txt`: one cred, and services re-authenticate themselves forever
 * after.
 *
 * Stored in the app's private SharedPreferences. `allowBackup=false` keeps it
 * out of cloud backups; nothing else can read it without root. It is never
 * logged, never returned in a UI string, and never leaves the device except as
 * a CAS/RSA login body to the university's own endpoints.
 */
object Credentials {

    private const val PREFS = "sustech_mobile_creds"
    private const val KEY_SID = "sid"
    private const val KEY_PASSWORD = "password"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once a student ID and password have been entered. */
    val configured: Boolean get() = sid.isNotEmpty() && password.isNotEmpty()

    val sid: String get() = prefs.getString(KEY_SID, "").orEmpty()

    /** Read-only here; the only writer is [save]. */
    val password: String get() = prefs.getString(KEY_PASSWORD, "").orEmpty()

    fun save(sid: String, password: String) {
        val cleanSid = sid.trim()
        if (cleanSid.isEmpty() || password.isEmpty()) return
        prefs.edit().putString(KEY_SID, cleanSid).putString(KEY_PASSWORD, password).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
