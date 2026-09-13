package edu.sustech.mobile.ui.tis

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.tis.CourseRow
import edu.sustech.mobile.ui.ListFragment

/**
 * Courses — the term's enrolled courses, one row per course.
 *
 * Built from the personal timetable, not the grade API: a term that just
 * started has no grades yet, and the timetable is the only source that knows
 * what you are actually enrolled in today.
 */
class CoursesFragment : ListFragment<CourseRow>(R.layout.fragment_list) {

    override fun rowLayout() = R.layout.item_tis_course

    override fun emptyText() = getString(R.string.tis_courses_empty)

    override fun errorText(error: Throwable): String =
        if (error is ApiException && error.signInRequired) getString(R.string.tis_not_signed_in)
        else super.errorText(error)

    override suspend fun fetch(): List<CourseRow> {
        val semester = App.tis.currentSemester()
        return CourseRow.group(App.tis.semesterSchedule(semester))
    }

    override fun bindRow(view: View, item: CourseRow, position: Int) {
        view.findViewById<TextView>(R.id.course_code).text = item.code.ifEmpty { "—" }
        view.findViewById<TextView>(R.id.course_name).text = item.name
        view.findViewById<TextView>(R.id.course_meta).text = listOf(
            item.teacher,
            item.meetingsText,
            item.rooms,
            getString(R.string.tis_weeks_range, item.weekRangeText),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
