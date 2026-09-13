package edu.sustech.mobile.sso

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.pms.PmsAuth
import edu.sustech.mobile.tis.Semester

/**
 * Auto sign-in for every service, from the one stored school account.
 *
 * Nothing in the UI signs in per service: screens call `ensureX()`, which
 * reuses a live session or silently re-authenticates with the stored
 * credentials. Expiry is therefore invisible — the same behavior the Python
 * client gets from `Authorizer.ensure()`.
 */
object Session {

    /** TIS's CAS entry point, same value the Python `TISAuth` uses. */
    private const val TIS_SERVICE = Hosts.TIS + "/cas"

    /**
     * Signs in to every service with the stored account.
     *
     * Returns true when at least one service accepted it, so a campus-only
     * service being unreachable off campus does not block the app. Credentials
     * being *wrong* is the case where nothing accepts them.
     */
    fun signIn(): Boolean {
        requireCredentials()
        val print = ensurePrint()
        val courses = ensureCourses() != null
        return print || courses
    }

    /** Live print session, or a fresh one from the stored credentials. */
    fun ensurePrint() = runCatching { App.api.check() }.isSuccess || reloginPrint()

    fun reloginPrint(): Boolean {
        if (!Credentials.configured) return false
        return try {
            PmsAuth.login(Credentials.sid, Credentials.password)
            true
        } catch (e: ApiException) {
            false
        }
    }

    /** Live TIS session, or a fresh one from the stored credentials. */
    fun ensureCourses(): Semester? = runCatching { App.tis.currentSemester() }.getOrNull() ?: reloginCourses()

    fun reloginCourses(): Semester? {
        if (!Credentials.configured) return null
        return try {
            if (!CasLogin.login(TIS_SERVICE, Credentials.sid, Credentials.password, xhr = true)) return null
            App.tis.currentSemester()
        } catch (e: ApiException) {
            null
        }
    }

    private fun requireCredentials() {
        if (!Credentials.configured) {
            throw ApiException("No school account saved", signInRequired = true)
        }
    }
}
