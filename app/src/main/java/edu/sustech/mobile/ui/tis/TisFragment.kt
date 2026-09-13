package edu.sustech.mobile.ui.tis

import android.content.Intent
import android.view.View
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.ui.LoginActivity
import edu.sustech.mobile.ui.ServicePage
import edu.sustech.mobile.ui.TabbedServiceFragment

/**
 * Courses & grades: the TIS reads worth having on a phone — this week's
 * timetable, the term's courses, posted grades, exam schedule.
 *
 * Sign-in is the school page in a WebView; grading and course selection stay
 * on the website.
 */
class TisFragment : TabbedServiceFragment(R.layout.fragment_tabs) {

    override fun pages(): List<ServicePage> = listOf(
        ServicePage(R.string.tab_week) { WeekFragment() },
        ServicePage(R.string.tis_courses) { CoursesFragment() },
        ServicePage(R.string.tis_grades) { GradesFragment() },
        ServicePage(R.string.tis_exams) { ExamsFragment() },
    )

    override fun onServiceViewReady(view: View) = checkSession()

    private fun checkSession() {
        banner(null, null)
        runIo(
            block = { App.tis.currentSemester() },
            onOk = { semester ->
                banner(
                    getString(R.string.tis_semester, semester.labelEn.ifEmpty { semester.label }, semester.year),
                    null,
                )
            },
            onErr = {
                banner(getString(R.string.tis_not_signed_in)) {
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                }
            },
        )
    }
}
