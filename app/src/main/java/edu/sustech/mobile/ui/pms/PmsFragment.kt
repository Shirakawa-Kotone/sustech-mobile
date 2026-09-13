package edu.sustech.mobile.ui.pms

import android.content.Intent
import android.view.View
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
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
            onErr = {
                banner(getString(R.string.session_expired)) {
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                }
            },
        )
    }
}
