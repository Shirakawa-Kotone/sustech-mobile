package edu.sustech.mobile.widget

import android.content.Context
import edu.sustech.mobile.calendar.AcademicCalendar
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.tis.NextClass
import edu.sustech.mobile.tis.PeriodTimes
import edu.sustech.mobile.tis.Weekday
import edu.sustech.mobile.transit.BusApi
import edu.sustech.mobile.transit.LocationProbe
import java.time.LocalDate
import java.util.Calendar

/**
 * What a widget shows, whatever it is configured for.
 *
 * The home screen can only render [RemoteViews], so every widget is reduced to
 * a title, a short list of lines, and the time the data was fetched — the
 * fetching and the thinking happen here, off the widget's thread, and the
 * provider only paints. [at] is what lets the card say how stale it is.
 */
data class WidgetSnapshot(
    val title: String,
    val lines: List<String>,
    val at: Long = System.currentTimeMillis(),
)

/** The widget contents the config screen offers. */
enum class WidgetKind(val key: String) {
    BUS("bus"),
    DEADLINES("bb"),
    CLASSES("classes"),
    WEATHER("weather");

    companion object {
        fun of(key: String?): WidgetKind = entries.firstOrNull { it.key == key } ?: BUS
    }

    /**
     * How long a card of this kind may go without a network refresh.
     *
     * A bus moves between stops in minutes; a deadline does not move at all.
     * Polling all four at the bus's pace burned the campus API and the TIS
     * session for no gain, so the slow kinds are refreshed on a slow clock and
     * the fast one on a fast clock.
     */
    val cadenceMillis: Long
        get() = when (this) {
            BUS -> 60_000L
            DEADLINES, CLASSES, WEATHER -> 15 * 60_000L
        }
}

/**
 * Builds the snapshot for one widget.
 *
 * These functions **throw** on failure instead of inventing an error card: the
 * provider owns the fallback (last stored snapshot + its age), because only it
 * knows what the widget showed a minute ago.
 */
object WidgetData {

    fun snapshot(context: Context, kind: WidgetKind, stopId: String): WidgetSnapshot = when (kind) {
        WidgetKind.BUS -> bus(context, stopId)
        WidgetKind.DEADLINES -> deadlines()
        WidgetKind.CLASSES -> classes(context)
        WidgetKind.WEATHER -> weather()
    }

    fun titleOf(kind: WidgetKind): String = when (kind) {
        WidgetKind.BUS -> "Bus"
        WidgetKind.DEADLINES -> "BB deadlines"
        WidgetKind.CLASSES -> "Next class"
        WidgetKind.WEATHER -> "Weather"
    }

    /** One short line explaining a failure, for the card's footer. */
    fun shortReason(error: Throwable): String = when {
        error is ApiException && error.signInRequired -> "sign in inside the app"
        error is ApiException && error.offCampus -> "needs campus network"
        else -> "update failed"
    }

    /**
     * Bus ETAs for a stop — the "wait or run" card.
     *
     * With nothing configured, the stop is the one nearest the phone: the card
     * exists for the moment you are walking and want to know whether to run,
     * and at that moment the nearest stop is the only one that matters. Each
     * line names the first stop after this one, because a direction word
     * alone does not tell you whether that bus goes to the dorm or to the
     * engineering college.
     */
    private fun bus(context: Context, stopId: String): WidgetSnapshot {
        val stops = App.bus.stops()
        val here = LocationProbe.lastKnown(context)
        val nearest = here?.let {
            App.bus.nearestStops(it.latitude, it.longitude, 1).firstOrNull()
        }
        val configured = stops.firstOrNull { it.id == stopId }
        val stop: BusApi.Stop
        val metres: Int?
        when {
            configured != null -> { stop = configured; metres = null }
            nearest != null -> { stop = nearest.first; metres = nearest.second }
            else -> {
                stop = stops.firstOrNull()
                    ?: return WidgetSnapshot("Bus", listOf("no stop data"))
                metres = null
            }
        }

        val arrivals = App.bus.arrivals(stop.id)
        val lines = when {
            arrivals.isEmpty() -> listOf("no buses due right now")
            // Every entry carries no live estimate (last bus gone, holiday):
            // say why, instead of a row of dashes the reader must decode.
            arrivals.all { it.minutes == null } -> arrivals.take(2).map { arrival ->
                "${arrival.routeName} ${BusApi.unavailableText(arrival.reason)}"
            }
            else -> arrivals.take(3).map { arrival ->
                val route = arrival.routeName.ifEmpty { "Bus" }
                val tail = arrival.upcoming.firstOrNull()
                    ?.let { " → $it" }.orEmpty()
                val eta = arrival.minutes?.let { "$it min" }
                    ?: arrival.plannedAt.ifEmpty { "—" }
                "$route · $eta$tail"
            }
        }
        val away = metres?.let {
            " · " + if (it < 1000) "$it m" else String.format("%.1f km", it / 1000.0)
        }
        return WidgetSnapshot(stop.display + (away ?: ""), lines)
    }

    /**
     * The next few Blackboard deadlines.
     *
     * `allowRelogin = false`: a widget refresh must never drive a CAS sign-in.
     * Every 60 seconds of self-healing produced throttled CAS answers, which
     * read as "no connection" on the card; if the session is gone, the widget
     * says so and the app does the signing in.
     */
    private fun deadlines(): WidgetSnapshot {
        val all = App.bb.deadlines(allowRelogin = false)
        val lines = if (all.isEmpty()) listOf("no dated assignments")
        else all.take(3).map { "${it.due}  ${it.title.take(26)}" }
        return WidgetSnapshot("BB deadlines", lines)
    }

    /**
     * The next meeting on the real calendar — the same rule the app's Today
     * card uses (see [NextClass]), so the widget and the app never disagree.
     */
    private fun classes(context: Context): WidgetSnapshot {
        val semester = App.tis.currentSemester()
        val week = App.tis.currentWeek()
        val entries = App.tis.semesterSchedule(semester)
        val today = LocalDate.now()
        val currentPeriod = PeriodTimes.currentPeriod(Calendar.getInstance())
        val term = AcademicCalendar.termAt(context, today)
        val next = if (term != null) {
            NextClass.find(entries, term, today, currentPeriod)
        } else {
            NextClass.findInWeek(entries, week, today, currentPeriod)
        }

        if (next == null) return WidgetSnapshot("Next class", listOf("no upcoming classes"))

        val prefix = if (next.date == today) "" else "${Weekday.short(next.date.dayOfWeek.value)} "
        val time = next.entry.timeText.ifEmpty {
            PeriodTimes.range(next.entry.periodFrom, next.entry.periodTo)
        }
        val lines = buildList {
            add("$prefix$time  ${next.entry.name}")
            if (next.entry.room.isNotEmpty()) add(next.entry.room)
        }
        return WidgetSnapshot("Next class", lines)
    }

    /** Campus weather + AQI, the two numbers worth a glance before leaving. */
    private fun weather(): WidgetSnapshot {
        val current = App.weather.weather()
        val air = runCatching { App.weather.airQuality() }.getOrNull()
        val temp = current.tempC?.let { "$it°C" } ?: "—"
        val feels = current.feelsLike?.let { "feels $it°C" } ?: ""
        val rain = if (current.rainExpected) "rain in 2 h" else "no rain in 2 h"
        val lines = buildList {
            add(listOf(temp, feels).filter { it.isNotEmpty() }.joinToString(" · "))
            add(rain)
            air?.aqi?.let { add("AQI $it ${air.category}") }
        }
        return WidgetSnapshot("Weather", lines)
    }
}
