package edu.sustech.mobile.tis

import edu.sustech.mobile.calendar.Term
import java.time.LocalDate

/**
 * The next meeting that actually happens.
 *
 * The Today card and the "Next class" widget ask the same question, so the rule
 * lives here once. They used to carry a copy each, which is how a card can
 * promise a 19:00 lecture during 国庆节: TIS serves a *pattern* ("weeks 2, 4, 6 …
 * on Thursday"), and only the academic calendar knows which of its dates are
 * real. [findInWeek] is the pattern-only reading, kept for the terms no
 * bundled calendar covers.
 */
object NextClass {

    /** A meeting and the date it lands on. */
    data class Upcoming(val entry: ClassEntry, val date: LocalDate)

    /**
     * The soonest real meeting.
     *
     * A meeting already finished today does not count, and one a holiday
     * flushed counts on its compensatory day instead — or not at all.
     */
    fun find(entries: List<ClassEntry>, term: Term, today: LocalDate, currentPeriod: Int): Upcoming? {
        var best: Upcoming? = null
        for (entry in meetingsOf(entries)) {
            for (date in term.meetings(entry.effectiveWeeks, entry.weekday)) {
                if (date.isBefore(today)) continue
                if (date == today && entry.periodTo < currentPeriod) continue
                val current = best
                if (current == null || date.isBefore(current.date) ||
                    (date == current.date && entry.periodFrom < current.entry.periodFrom)
                ) {
                    best = Upcoming(entry, date)
                }
            }
        }
        return best
    }

    /**
     * The soonest meeting in [week]'s pattern, ignoring the calendar.
     *
     * Only for a date no bundled calendar covers; it is what the app did before
     * the calendar existed, and it is wrong on a holiday by construction.
     * [date] is the weekday's date in the current week, since the pattern alone
     * does not carry dates.
     */
    fun findInWeek(entries: List<ClassEntry>, week: Int?, today: LocalDate, currentPeriod: Int): Upcoming? {
        val todayIso = today.dayOfWeek.value
        val inWeek = inWeek(entries, week)
        val laterToday = inWeek
            .filter { it.weekday == todayIso && it.periodTo >= currentPeriod }
            .minByOrNull { it.periodFrom }
        if (laterToday != null) return Upcoming(laterToday, today)
        val later = inWeek.filter { it.weekday > todayIso }
            .minWithOrNull(compareBy({ it.weekday }, { it.periodFrom })) ?: return null
        return Upcoming(later, today.plusDays((later.weekday - todayIso).toLong()))
    }

    /** True when [week]'s pattern puts any meeting on today, finished or not. */
    fun hadClassToday(entries: List<ClassEntry>, week: Int?, today: LocalDate): Boolean =
        inWeek(entries, week).any { it.weekday == today.dayOfWeek.value }

    private fun inWeek(entries: List<ClassEntry>, week: Int?): List<ClassEntry> {
        val unique = meetingsOf(entries)
        return if (week == null) unique else unique.filter { it.meets(week) }
    }

    /** TIS repeats a course once per slot; identity is course, day and periods. */
    private fun meetingsOf(entries: List<ClassEntry>): List<ClassEntry> =
        entries.distinctBy { "${it.name}|${it.weekday}|${it.periodFrom}|${it.periodTo}" }
}
