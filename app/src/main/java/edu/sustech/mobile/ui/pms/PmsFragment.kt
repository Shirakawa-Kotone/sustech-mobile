package edu.sustech.mobile.ui.pms

import android.content.Intent
import android.view.View
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.ui.LoginActivity
import edu.sustech.mobile.ui.ServicePage
import edu.sustech.mobile.ui.TabbedServiceFragment

/**
 * Print service: the four pages the PMS website has, as tabs.
 *
 * The pages are the website's own structure — print queue (with upload),
 * stations, scans, usage report — not an invention of the app.
 */
class PmsFragment : TabbedServiceFragment(R.layout.fragment_tabs) {

    override fun pages(): List<ServicePage> = listOf(
        ServicePage(R.string.tab_print) { JobsFragment() },
        ServicePage(R.string.tab_stations) { StationsFragment() },
        ServicePage(R.string.tab_scans) { ScanFragment() },
        ServicePage(R.string.tab_usage) { UsageFragment() },
    )

    override fun onServiceViewReady(view: View) = checkSession()

    private fun checkSession() {
        banner(null, null)
        runIo(
            block = { App.api.check() },
            onOk = { banner(null, null) },
            onErr = { error ->
                // Say what actually failed. "Session expired" is only true when
                // the server refused the session; a campus-only 403, a plain-HTTP
                // address or a dead network each get their own wording.
                val api = error as? ApiException
                when {
                    api != null && api.offCampus -> banner(getString(R.string.banner_campus_only), null)
                    api != null && !api.refused -> banner(error.friendly(requireContext()), null)
                    else -> banner(error.friendly(requireContext())) {
                        startActivity(Intent(requireContext(), LoginActivity::class.java))
                    }
                }
            },
        )
    }
}
