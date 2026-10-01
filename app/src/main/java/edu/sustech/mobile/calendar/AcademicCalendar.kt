package edu.sustech.mobile.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The academic calendar, read from the bundled `assets/calendar/<year>/` files.
 *
 * The timetable TIS serves is a **pattern**, not a schedule: it says "this lab
 * meets in weeks 2, 4, 6 … on Thursday", and it says so every term, holidays
 * included. Whether a given date is a teaching day, and whether a flushed
 * meeting moved to a makeup day, is a *calendar* fact, and the calendar is not
 * in TIS. Without it the app names classes that will not happen — on
 * 2026-10-01 (国庆节) it announced a 19:00 lecture.
 *
 * The files are verbatim copies of the `sustech-calendar` repo, the same ones
 * `sustech_survival.calendar` parses, so all three clients agree on a date.
 * They are bundled rather than fetched because the app cannot rely on the
 * network for a fact this stable — see `assets/calendar/README.md`.
 *
 * Nothing here throws: a missing or unreadable calendar leaves [termAt]
 * returning null, and callers fall back to showing the raw timetable.
 */
object AcademicCalendar {

    private const val ROOT = "calendar"
    private const val LEVEL = "undergraduate"

    /** A year's parsed contents, keyed by year. */
    private class Year(val holidays: List<Holiday>, val terms: List<Term>) {
        fun term(date: LocalDate): Term? = terms.firstOrNull { it.isInSemester(date) }
    }

    private val years = HashMap<String, Year>()

    /**
     * The term [date] falls in, or null between terms.
     *
     * A fall term runs into the next January, so its year directory is the
     * year the term *started*; that is why the previous directory is tried too.
     */
    fun termAt(context: Context, date: LocalDate): Term? {
        for (year in intArrayOf(date.year, date.year - 1)) {
            load(context, year)?.term(date)?.let { return it }
        }
        return null
    }

    private fun load(context: Context, year: Int): Year? {
        val key = "$year/$LEVEL"
        synchronized(years) { years[key] }?.let { return it }
        val general = readJson(context, "$ROOT/$year/general.json")
        val level = readJson(context, "$ROOT/$year/$LEVEL.json")
        if (general == null || level == null) {
            // Remember the miss, so a date far from any bundled term does not
            // re-read (and re-fail on) the assets on every screen.
            synchronized(years) { years[key] = Year(emptyList(), emptyList()) }
            return null
        }
        val parsed = try {
            val holidays = readHolidays(general)
            Year(
                holidays = holidays,
                terms = listOfNotNull(
                    term(level.optJSONObject("spring_semester"), holidays),
                    term(level.optJSONObject("fall_semester"), holidays),
                ),
            )
        } catch (_: Exception) {
            return null
        }
        synchronized(years) { years[key] = parsed }
        return parsed
    }

    private fun readJson(context: Context, path: String): JSONObject? = try {
        JSONObject(context.assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) })
    } catch (_: Exception) {
        null
    }

    private fun readHolidays(general: JSONObject): List<Holiday> =
        general.optJSONArray("holidays").objects().mapNotNull { row ->
            val start = row.optString("start")
            val end = row.optString("end")
            if (start.isEmpty() || end.isEmpty()) null
            else Holiday(row.optString("name"), LocalDate.parse(start), LocalDate.parse(end))
        }

    private fun term(payload: JSONObject?, holidays: List<Holiday>): Term? {
        if (payload == null) return null
        val start = payload.optString("teaching_start")
        val signIn = payload.optString("sign_in")
        val finalEnd = payload.optJSONObject("final")?.optString("end").orEmpty()
        if (start.isEmpty() || signIn.isEmpty() || finalEnd.isEmpty()) return null
        return Term(
            teachingStart = LocalDate.parse(start),
            signIn = LocalDate.parse(signIn),
            finalEnd = LocalDate.parse(finalEnd),
            totalTeachingWeeks = payload.optInt("total_teaching_weeks", 0),
            midtermWeeks = payload.optJSONObject("midterm")?.optJSONArray("equivalent_weeks").ints(),
            finalWeeks = payload.optJSONObject("final")?.optJSONArray("equivalent_weeks").ints(),
            compensatories = payload.optJSONArray("compensatories").objects().mapNotNull { row ->
                val date = row.optString("date")
                val workday = row.optString("workday_type")
                if (date.isEmpty() || workday.isEmpty()) null
                else runCatching {
                    Compensatory(LocalDate.parse(date), row.optString("week_type"), DayOfWeek.valueOf(workday.uppercase()))
                }.getOrNull()
            },
            extraBreaks = payload.optJSONArray("extra_breaks").objects()
                .mapNotNull { runCatching { LocalDate.parse(it.toString()) }.getOrNull() }
                .toSet(),
            holidays = holidays,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = when {
        this == null -> emptyList()
        else -> (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.ints(): List<Int> = when {
        this == null -> emptyList()
        else -> (0 until length()).mapNotNull { optInt(it).takeIf { value -> value > 0 } }
    }
}

/** A statutory holiday. [name] is the calendar's own, shown verbatim as data. */
data class Holiday(val name: String, val start: LocalDate, val end: LocalDate) {
    fun covers(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** A day designated for classes that a holiday flushed. */
data class Compensatory(val date: LocalDate, val weekType: String, val workday: DayOfWeek)

/**
 * One teaching term.
 *
 * Mirrors `sustech_survival.calendar.Semester` so the two clients cannot
 * disagree about a date: week numbers are anchored on the Monday on or before
 * [teachingStart] (week 1 is always a Monday, even when teaching starts
 * mid-week), and a meeting flushed by a holiday moves to its compensatory day
 * or is dropped.
 */
data class Term(
    val teachingStart: LocalDate,
    val signIn: LocalDate,
    val finalEnd: LocalDate,
    val totalTeachingWeeks: Int,
    val midtermWeeks: List<Int>,
    val finalWeeks: List<Int>,
    val compensatories: List<Compensatory>,
    val extraBreaks: Set<LocalDate>,
    val holidays: List<Holiday> = emptyList(),
) {

    private val teachingMonday: LocalDate =
        teachingStart.minusDays((teachingStart.dayOfWeek.value - 1).toLong())

    fun isInSemester(date: LocalDate): Boolean = !date.isBefore(signIn) && !date.isAfter(finalEnd)

    fun holiday(date: LocalDate): Holiday? = holidays.firstOrNull { it.covers(date) }

    fun compensatory(date: LocalDate): Compensatory? = compensatories.firstOrNull { it.date == date }

    fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek.value >= 6

    fun isExtraBreak(date: LocalDate): Boolean = date in extraBreaks

    fun isFinalWeek(date: LocalDate): Boolean = weekOf(date) in finalWeeks

    /** 1-indexed teaching week, or 0 outside the term. */
    fun weekOf(date: LocalDate): Int {
        if (!isInSemester(date)) return 0
        return (ChronoUnit.DAYS.between(teachingMonday, date) / 7).toInt() + 1
    }

    /** The date of (week, weekday) — weekday is ISO, 1 = Monday … 7 = Sunday. */
    fun dateOf(week: Int, weekday: Int): LocalDate? {
        if (weekday !in 1..7) return null
        val lastWeek = totalTeachingWeeks + (finalWeeks.maxOrNull() ?: 0)
        if (week !in 1..lastWeek) return null
        return teachingMonday.plusDays(7L * (week - 1) + (weekday - 1))
    }

    /** True when regular classes meet: no holiday, break, final week or weekend. */
    fun isTeachingDay(date: LocalDate): Boolean {
        if (!isInSemester(date)) return false
        if (holiday(date) != null || compensatory(date) != null) return false
        if (isFinalWeek(date) || isExtraBreak(date)) return false
        return !isWeekend(date)
    }

    /** Classes meet today — on a regular teaching day, or on a makeup day. */
    fun hasClass(date: LocalDate): Boolean = isTeachingDay(date) || compensatory(date) != null

    /**
     * The dates a meeting pattern actually happens on.
     *
     * Weekend and makeup dates are never sources (a makeup day is a
     * *destination*), and a date flushed by a holiday, break or final week is
     * replaced by its compensatory day — or dropped when the calendar has none.
     */
    fun meetings(weeks: List<Int>, weekday: Int): List<LocalDate> {
        if (weeks.isEmpty()) return emptyList()
        val out = sortedSetOf<LocalDate>()
        for (week in weeks) {
            val date = dateOf(week, weekday) ?: continue
            if (!isInSemester(date) || isWeekend(date) || compensatory(date) != null) continue
            if (holiday(date) != null || isFinalWeek(date) || isExtraBreak(date)) {
                compensatoryFor(date, if (week % 2 == 1) "odd" else "even", weekday)?.let { out.add(it.date) }
            } else {
                out.add(date)
            }
        }
        return out.toList()
    }

    /** Which compensatory day replaces a flushed meeting, if the calendar has one. */
    private fun compensatoryFor(flushed: LocalDate, parity: String, weekday: Int): Compensatory? {
        val workday = DayOfWeek.of(weekday)
        val candidates = compensatories.filter {
            it.weekType == parity && it.workday == workday && holidayFlushedBy(it) == flushed
        }
        if (candidates.isEmpty()) return null
        return candidates.filter { !it.date.isBefore(flushed) }.minByOrNull { it.date }
            ?: candidates.maxByOrNull { it.date }
    }

    /** The holiday date that flushed the same (parity, weekday) a makeup day replaces. */
    private fun holidayFlushedBy(comp: Compensatory): LocalDate? {
        val candidates = ArrayList<LocalDate>()
        for (holiday in holidays) {
            var date = holiday.start
            while (!date.isAfter(holiday.end)) {
                val week = weekOf(date)
                val parity = if (week % 2 == 1) "odd" else "even"
                if (date.dayOfWeek == comp.workday && week != 0 && parity == comp.weekType) candidates.add(date)
                date = date.plusDays(1)
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.filter { !it.isAfter(comp.date) }.maxOrNull()
            ?: candidates.filter { it.isAfter(comp.date) }.minOrNull()
    }
}
