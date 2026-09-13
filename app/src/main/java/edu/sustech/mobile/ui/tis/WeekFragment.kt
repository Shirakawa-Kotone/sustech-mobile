package edu.sustech.mobile.ui.tis

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.tis.ClassEntry
import edu.sustech.mobile.tis.Weekday
import edu.sustech.mobile.ui.ListFragment

/**
 * This week — every meeting of the current teaching week, in order.
 *
 * The week number comes from TIS itself (`querydangqianzc`), so a term that
 * has not started yet reports honestly rather than guessing from the date.
 */
class WeekFragment : ListFragment<ClassEntry>(R.layout.fragment_tis_week) {

    private var headerText = ""

    override fun rowLayout() = R.layout.item_tis_class

    override fun emptyText() = getString(R.string.tis_week_empty)

    override fun errorText(error: Throwable): String =
        if (error is ApiException && error.signInRequired) getString(R.string.tis_not_signed_in)
        else super.errorText(error)

    override suspend fun fetch(): List<ClassEntry> {
        val semester = App.tis.currentSemester()
        val week = App.tis.currentWeek()
        headerText = listOfNotNull(
            week?.let { getString(R.string.today_week, it) },
            semester.labelEn.ifEmpty { semester.label },
        ).joinToString(" · ")
        val entries = entriesForWeek(week ?: 1, semester)
        return entries
    }

    private suspend fun entriesForWeek(
        week: Int,
        semester: edu.sustech.mobile.tis.Semester,
    ): List<ClassEntry> = App.tis.weekSchedule(week, semester)
        .sortedWith(compareBy({ it.weekday }, { it.periodFrom }))

    override fun onLoaded(rows: List<ClassEntry>) {
        view?.findViewById<TextView>(R.id.week_header)?.text = headerText
    }

    override fun bindRow(view: View, item: ClassEntry, position: Int) {
        view.findViewById<TextView>(R.id.class_day).text = Weekday.short(item.weekday)
        view.findViewById<TextView>(R.id.class_time).text = item.timeText
        view.findViewById<TextView>(R.id.class_name).text = item.name
        view.findViewById<TextView>(R.id.class_meta).text = listOf(
            item.teacher.replace(",", ", ").replace(",  ", ", "),
            item.room,
            item.classGroup,
            getString(R.string.tis_weeks_range, item.weekRangeText),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
