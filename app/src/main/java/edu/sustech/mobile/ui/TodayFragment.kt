package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.tis.ClassEntry
import edu.sustech.mobile.tis.PeriodTimes
import edu.sustech.mobile.tis.Weekday
import java.util.Calendar

/**
 * Today: the one screen worth opening between classes.
 *
 * Week number, the single next class ("next up"), campus weather and air
 * quality, and the next exam. Only the weather is auth-free; everything else
 * needs TIS, and the screen says so instead of showing empty cards.
 *
 * The class line shows one meeting, not the day's timetable: the card answers
 * "what is next", and the parity of the weeks is spelled out, because a lab that
 * only runs on even weeks otherwise looks like it runs every week.
 */
class TodayFragment : Fragment(R.layout.fragment_today), Refreshable {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.today_sign_in).setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        view.findViewById<SwipeRefreshLayout>(R.id.today_swipe).setOnRefreshListener { load() }
        load()
    }

    override fun refresh() = load()

    private fun load() {
        val swipe = view?.findViewById<SwipeRefreshLayout>(R.id.today_swipe)
        val week = view?.findViewById<TextView>(R.id.today_week)
        val weekday = view?.findViewById<TextView>(R.id.today_weekday)
        val classes = view?.findViewById<TextView>(R.id.today_classes_list)
        val hint = view?.findViewById<TextView>(R.id.today_hint)
        val signIn = view?.findViewById<MaterialButton>(R.id.today_sign_in)
        val weather = view?.findViewById<TextView>(R.id.today_weather_value)
        val aqi = view?.findViewById<TextView>(R.id.today_aqi_value)
        val exam = view?.findViewById<TextView>(R.id.today_exam_value)

        val today = Calendar.getInstance()
        swipe?.isRefreshing = true
        weekday?.text = Weekday.short(isoWeekday(today))
        week?.setText(R.string.today_week_unknown)
        classes?.setText(R.string.empty_loading)
        hint?.visibility = View.GONE
        signIn?.visibility = View.GONE

        // Public data first — it works with no session at all.
        runIo(
            block = { App.weather.weather() to runCatching { App.weather.airQuality() }.getOrNull() },
            onOk = { (w, air) ->
                weather?.text = listOfNotNull(
                    w.tempC?.let { "$it°C" },
                    w.feelsLike?.let { "feels $it°C" },
                    if (w.rainExpected) "rain expected" else "no rain in the next 2 hours",
                ).joinToString(" · ")
                aqi?.text = air?.aqi?.let { "$it (${air.category})" } ?: "—"
            },
            onErr = { weather?.setText(R.string.today_none) },
        )

        runIo(
            block = {
                val semester = App.tis.currentSemester()
                val currentWeek = App.tis.currentWeek()
                val entries = App.tis.semesterSchedule(semester)
                Triple(semester, currentWeek, entries)
            },
            onOk = { (semester, currentWeek, entries) ->
                swipe?.isRefreshing = false
                week?.text = if (currentWeek == null) getString(R.string.today_week_unknown)
                else getString(R.string.today_week, currentWeek)
                weekday?.text =
                    "${Weekday.short(isoWeekday(today))} · ${semester.labelEn.ifEmpty { semester.label }}"
                classes?.text = nextUpText(entries, currentWeek, today)
                hint?.visibility = View.GONE

                runIo(
                    block = { App.tis.exams() },
                    onOk = { exams ->
                        val next = exams.firstOrNull()
                        exam?.text = next?.let { "${it.date} · ${it.course} · ${it.room}" }
                            ?: getString(R.string.today_none)
                    },
                    onErr = { exam?.setText(R.string.today_none) },
                )
            },
            onErr = {
                swipe?.isRefreshing = false
                week?.setText(R.string.today_week_unknown)
                classes?.setText(R.string.today_sign_in_hint)
                hint?.visibility = View.VISIBLE
                signIn?.visibility = View.VISIBLE
                exam?.setText(R.string.today_none)
            },
        )
    }

    /**
     * The next meeting: the one still ahead today if there is one, otherwise the
     * first one later in the week. Meetings are filtered by the week number, so
     * an even-week lab does not appear in an odd week.
     */
    private fun nextUpText(entries: List<ClassEntry>, week: Int?, today: Calendar): String {
        // The same lab is reported once per section row; one slot is one line.
        val unique = entries.distinctBy { "${it.name}|${it.weekday}|${it.periodFrom}|${it.periodTo}" }
        val inWeek = if (week == null) unique else unique.filter { it.meets(week) }
        val todayIso = isoWeekday(today)
        val current = PeriodTimes.currentPeriod(today)

        val laterToday = inWeek
            .filter { it.weekday == todayIso && it.periodTo >= current }
            .minByOrNull { it.periodFrom }
        if (laterToday != null) return describe(laterToday, prefix = "")

        val laterThisWeek = inWeek
            .filter { it.weekday > todayIso }
            .minWithOrNull(compareBy({ it.weekday }, { it.periodFrom }))
        if (laterThisWeek != null) return describe(laterThisWeek, prefix = "${Weekday.short(laterThisWeek.weekday)} ")

        return if (inWeek.any { it.weekday == todayIso }) getString(R.string.today_none_left)
        else getString(R.string.today_no_classes)
    }

    private fun describe(entry: ClassEntry, prefix: String): String {
        val time = entry.timeText.ifEmpty { PeriodTimes.range(entry.periodFrom, entry.periodTo) }
        val head = "$prefix$time  ${entry.name}"
        val detail = listOfNotNull(
            entry.teacher.ifEmpty { null },
            entry.room.ifEmpty { null },
            entry.weekRangeText.ifEmpty { null }?.let { getString(R.string.tis_weeks_range, it) },
        )
        return if (detail.isEmpty()) head else head + "\n" + detail.joinToString(" · ")
    }

    /** TIS uses 1 = Monday … 7 = Sunday; Calendar uses 1 = Sunday. */
    private fun isoWeekday(calendar: Calendar): Int = calendar.get(Calendar.DAY_OF_WEEK).let {
        if (it == Calendar.SUNDAY) 7 else it - 1
    }
}
