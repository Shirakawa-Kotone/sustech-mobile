package edu.sustech.mobile.sso

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
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
     * The print site itself. Visiting it through CAS is what the website does,
     * and the print back end links the CAS identity to the print account (it
     * creates one on first visit) — which is how the Python client's refresh
     * works too. The site's own RSA password login is kept as a fallback.
     */
    private const val PRINT_SERVICE = Hosts.PMS + "/client/new/cprintPc/"

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

    /** True while a re-login is running, so the path cannot re-enter itself. */
    private var reloginInFlight = false

    fun reloginCourses(): Semester? {
        if (!Credentials.configured || reloginInFlight) return null
        reloginInFlight = true
        return try {
            CasLogin.login(TIS_SERVICE, Credentials.sid, Credentials.password, xhr = true)
            App.tis.currentSemester()
        } catch (e: ApiException) {
            null
        } finally {
            reloginInFlight = false
        }
    }

    fun reloginPrint(): Boolean {
        if (reloginInFlight) return false
        reloginInFlight = true
        return try {
            printAccess() == Access.ACCEPTED
        } finally {
            reloginInFlight = false
        }
    }

    // -- Per-service classification -------------------------------------------

    private fun printAccess(): Access {
        if (printSessionAlive()) return Access.ACCEPTED
        if (!Credentials.configured) return Access.REFUSED

        var refused = false
        // 1) CAS — the path the browser takes, and the one the real server
        //    accepts. It also works for accounts with no print password yet.
        //    Skipped for a local test server, which has no CAS in front of it.
        if (!AppConfig.isLocalHost(Hosts.host(AppConfig.baseUrl))) {
            try {
                CasLogin.login(PRINT_SERVICE, Credentials.sid, Credentials.password, xhr = false)
                if (printSessionAlive()) return Access.ACCEPTED
                note("printing", "CAS sign-in did not produce a print session")
            } catch (e: ApiException) {
                if (e.refused) refused = true
                note("printing", e.message.orEmpty())
            }
        }
        // 2) The print system's own RSA password login.
        try {
            PmsAuth.login(Credentials.sid, Credentials.password)
            if (printSessionAlive()) return Access.ACCEPTED
            note("printing", "print login did not produce a session")
        } catch (e: ApiException) {
            if (e.refused) refused = true
            note("printing", e.message.orEmpty())
        }
        return if (refused) Access.REFUSED else Access.UNREACHABLE
    }

    /**
     * Session probe for the sign-in logic. Uses the un-wrapped check on purpose:
     * the wrapped one re-enters [reloginPrint] and would recurse.
     */
    private fun printSessionAlive(): Boolean = try {
        App.api.checkSession()
        true
    } catch (e: ApiException) {
        false
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
