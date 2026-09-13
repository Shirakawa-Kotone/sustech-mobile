package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.tis.ClassEntry
import edu.sustech.mobile.tis.Weekday
import java.util.Calendar

/**
 * Today: the one screen worth opening between classes.
 *
 * Week number, today's classes, campus weather and air quality, and the next
 * exam. Only the weather is auth-free; everything else needs TIS, and the
 * screen says so instead of showing empty cards.
 */
class TodayFragment : Fragment(R.layout.fragment_today), Refreshable {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.today_sign_in).setOnClickListener {
            startActivity(
                Intent(requireContext(), WebLoginActivity::class.java)
                    .putExtra(WebLoginActivity.EXTRA_URL, Hosts.TIS + "/")
                    .putExtra(WebLoginActivity.EXTRA_COOKIE, TIS_COOKIE),
            )
        }
        load()
    }

    override fun refresh() = load()

    private fun load() {
        val week = view?.findViewById<TextView>(R.id.today_week)
        val weekday = view?.findViewById<TextView>(R.id.today_weekday)
        val classes = view?.findViewById<TextView>(R.id.today_classes_list)
        val hint = view?.findViewById<TextView>(R.id.today_hint)
        val signIn = view?.findViewById<MaterialButton>(R.id.today_sign_in)
        val weather = view?.findViewById<TextView>(R.id.today_weather_value)
        val aqi = view?.findViewById<TextView>(R.id.today_aqi_value)
        val exam = view?.findViewById<TextView>(R.id.today_exam_value)

        val today = Calendar.getInstance()
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
                week?.text = if (currentWeek == null) getString(R.string.today_week_unknown)
                else getString(R.string.today_week, currentWeek)
                weekday?.text =
                    "${Weekday.short(isoWeekday(today))} · ${semester.labelEn.ifEmpty { semester.label }}"

                val todays = entries
                    .filter { it.weekday == isoWeekday(today) && (currentWeek == null || it.meets(currentWeek)) }
                    .sortedBy { it.periodFrom }
                classes?.text = if (todays.isEmpty()) {
                    getString(R.string.today_no_classes)
                } else {
                    todays.joinToString("\n") { describe(it) }
                }
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
                week?.setText(R.string.today_week_unknown)
                classes?.setText(R.string.today_sign_in_hint)
                hint?.visibility = View.VISIBLE
                signIn?.visibility = View.VISIBLE
                exam?.setText(R.string.today_none)
            },
        )
    }

    private fun describe(entry: ClassEntry): String {
        val time = if (entry.timeText.isEmpty()) {
            getString(R.string.tis_periods, entry.periodFrom, entry.periodTo)
        } else {
            entry.timeText
        }
        val teacher = if (entry.teacher.isEmpty()) "" else " · ${entry.teacher}"
        val room = if (entry.room.isEmpty()) "" else " · ${entry.room}"
        return "$time  ${entry.name}$teacher$room"
    }

    /** TIS uses 1 = Monday … 7 = Sunday; Calendar uses 1 = Sunday. */
    private fun isoWeekday(calendar: Calendar): Int = calendar.get(Calendar.DAY_OF_WEEK).let {
        if (it == Calendar.SUNDAY) 7 else it - 1
    }

    private companion object {
        const val TIS_COOKIE = "SESSION"
    }
}
