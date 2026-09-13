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
 * credentials, so expiry is invisible — the behavior the Python client gets
 * from `Authorizer.ensure()`.
 *
 * [signIn] classifies the outcome instead of returning a bare boolean, because
 * one of the services is campus-only: printing answers 403 from anywhere else,
 * and that must never be presented as "your account is wrong".
 */
object Session {

    /** How a sign-in attempt ended. */
    enum class Access {
        /** At least one service accepted the account. */
        ACCEPTED,

        /** A service answered and rejected the account — the credentials are wrong. */
        REFUSED,

        /** Nothing answered: off campus, offline, or the service is down. */
        UNREACHABLE,
    }

    /** The verdict plus per-service reasons, so a failure is never a mystery. */
    data class SignInReport(val access: Access, val detail: String)

    /** TIS's CAS entry point, the same value the Python `TISAuth` uses. */
    private const val TIS_SERVICE = Hosts.TIS + "/cas"

    /**
     * Signs in to every service and reports the strongest signal seen.
     *
     * One flat rule drives the whole classification: only an explicit refusal
     * counts as a bad account. Off-campus (printing), timeouts and 5xx replies
     * are unreachable, so a user off campus is never told their password is
     * wrong because printing could not be reached.
     */
    fun signIn(): SignInReport {
        requireCredentials()
        val print = probe("printing") { printAccess() }
        val courses = probe("courses") { coursesAccess() }
        val access = when {
            print.first == Access.ACCEPTED || courses.first == Access.ACCEPTED -> Access.ACCEPTED
            print.first == Access.REFUSED || courses.first == Access.REFUSED -> Access.REFUSED
            else -> Access.UNREACHABLE
        }
        val detail = listOf(print, courses)
            .filter { it.second.isNotEmpty() }
            .joinToString(" · ") { (_, why) -> why }
        return SignInReport(access, detail)
    }

    /** Runs one probe, turning any failure into a reason string. */
    private fun probe(name: String, block: () -> Access): Pair<Access, String> = try {
        when (val result = block()) {
            Access.ACCEPTED -> result to ""
            else -> result to "$name: ${lastReason(name) ?: "no answer"}"
        }
    } catch (e: ApiException) {
        Access.UNREACHABLE to "$name: ${e.message}"
    } catch (e: Exception) {
        Access.UNREACHABLE to "$name: ${e::class.java.simpleName}: ${e.message}"
    }

    /** Why the last probe for [name] failed, kept for the report. */
    private val reasons = HashMap<String, String>()

    private fun lastReason(name: String): String? = reasons[name]

    private fun note(name: String, reason: String) {
        reasons[name] = reason
    }

    /** Live print session, or a fresh one from the stored credentials. */
    fun ensurePrint(): Boolean = printAccess() == Access.ACCEPTED

    /** Live TIS session, or a fresh one from the stored credentials. */
    fun ensureCourses(): Semester? = runCatching { App.tis.currentSemester() }
        .getOrNull()
        ?: reloginCourses()

    fun reloginCourses(): Semester? {
        if (!Credentials.configured) return null
        return try {
            CasLogin.login(TIS_SERVICE, Credentials.sid, Credentials.password, xhr = true)
            App.tis.currentSemester()
        } catch (e: ApiException) {
            null
        }
    }

    fun reloginPrint(): Boolean {
        if (!Credentials.configured) return false
        return try {
            PmsAuth.login(Credentials.sid, Credentials.password)
            true
        } catch (e: ApiException) {
            false
        }
    }

    // -- Per-service classification -------------------------------------------

    private fun printAccess(): Access {
        runCatching { App.api.check() }.onSuccess { return Access.ACCEPTED }
        if (!Credentials.configured) return Access.REFUSED
        return try {
            PmsAuth.login(Credentials.sid, Credentials.password)
            Access.ACCEPTED
        } catch (e: ApiException) {
            note("printing", e.message.orEmpty())
            if (e.refused) Access.REFUSED else Access.UNREACHABLE
        }
    }

    private fun coursesAccess(): Access {
        runCatching { App.tis.currentSemester() }.onSuccess { return Access.ACCEPTED }
        if (!Credentials.configured) return Access.REFUSED
        return try {
            CasLogin.login(TIS_SERVICE, Credentials.sid, Credentials.password, xhr = true)
            App.tis.currentSemester()
            Access.ACCEPTED
        } catch (e: ApiException) {
            note("courses", e.message.orEmpty())
            if (e.refused) Access.REFUSED else Access.UNREACHABLE
        }
    }

    private fun requireCredentials() {
        if (!Credentials.configured) {
            throw ApiException("No school account saved", signInRequired = true)
        }
    }
}
