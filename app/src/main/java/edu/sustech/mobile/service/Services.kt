package edu.sustech.mobile.service

import edu.sustech.mobile.R
import edu.sustech.mobile.ui.pms.PmsFragment
import edu.sustech.mobile.ui.tis.TisFragment
import java.util.Locale

/**
 * The service catalog.
 *
 * Order is the display order in the Services tab: implemented services first,
 * then the roadmap. The roadmap mirrors the submodules that already exist in
 * the Python (`sustech_survival`) and TypeScript (`sustech-cli`) clients — it
 * is a shared backlog, not a marketing list.
 */
object Services {

    val printing = ServiceModule(
        id = "pms",
        title = R.string.service_pms,
        summary = R.string.service_pms_summary,
        icon = R.drawable.ic_printer,
        navId = R.id.nav_pms,
        available = true,
    ) { PmsFragment() }

    val courses = ServiceModule(
        id = "tis",
        title = R.string.service_tis,
        summary = R.string.service_tis_summary,
        icon = R.drawable.ic_school,
        navId = R.id.nav_tis,
        available = true,
    ) { TisFragment() }

    val blackboard = ServiceModule(
        id = "blackboard",
        title = R.string.service_blackboard,
        summary = R.string.service_blackboard_summary,
        icon = R.drawable.ic_doc,
        navId = R.id.nav_pms,
    )

    val library = ServiceModule(
        id = "library",
        title = R.string.service_library,
        summary = R.string.service_library_summary,
        icon = R.drawable.ic_history,
        navId = R.id.nav_pms,
    )

    val booking = ServiceModule(
        id = "booking",
        title = R.string.service_booking,
        summary = R.string.service_booking_summary,
        icon = R.drawable.ic_grid,
        navId = R.id.nav_pms,
    )

    val transit = ServiceModule(
        id = "transit",
        title = R.string.service_transit,
        summary = R.string.service_transit_summary,
        icon = R.drawable.ic_refresh,
        navId = R.id.nav_pms,
    )

    val nces = ServiceModule(
        id = "nces",
        title = R.string.service_nces,
        summary = R.string.service_nces_summary,
        icon = R.drawable.ic_person,
        navId = R.id.nav_pms,
    )

    val papers = ServiceModule(
        id = "papers",
        title = R.string.service_papers,
        summary = R.string.service_papers_summary,
        icon = R.drawable.ic_doc,
        navId = R.id.nav_pms,
    )

    val faculty = ServiceModule(
        id = "faculty",
        title = R.string.service_faculty,
        summary = R.string.service_faculty_summary,
        icon = R.drawable.ic_person,
        navId = R.id.nav_pms,
    )

    val exchange = ServiceModule(
        id = "ws",
        title = R.string.service_ws,
        summary = R.string.service_ws_summary,
        icon = R.drawable.ic_school,
        navId = R.id.nav_pms,
    )

    val languageHelp = ServiceModule(
        id = "cle",
        title = R.string.service_cle,
        summary = R.string.service_cle_summary,
        icon = R.drawable.ic_scan,
        navId = R.id.nav_pms,
    )

    val wifi = ServiceModule(
        id = "wifi",
        title = R.string.service_wifi,
        summary = R.string.service_wifi_summary,
        icon = R.drawable.ic_refresh,
        navId = R.id.nav_pms,
    )

    val all: List<ServiceModule> = listOf(
        printing, courses,
        blackboard, library, booking, transit, nces, papers, faculty, exchange,
        languageHelp, wifi,
    )

    val available: List<ServiceModule> = all.filter { it.available }

    fun byId(id: String?): ServiceModule? =
        all.firstOrNull { it.id.equals(id.orEmpty(), ignoreCase = true) }

    fun byTitle(text: String): ServiceModule? {
        val needle = text.lowercase(Locale.US)
        return all.firstOrNull { needle.contains(it.id.lowercase(Locale.US)) }
    }
}
