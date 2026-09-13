package edu.sustech.mobile.tis

import org.json.JSONArray
import org.json.JSONObject

// -- Wire constants -----------------------------------------------------------

/**
 * SUSTech standard period times (same table the Python exam module carries).
 * Periods, not clock hours, are what TIS sends — everything else is derived.
 */
object PeriodTimes {
    private val table = mapOf(
        1 to "08:00-08:50", 2 to "08:55-09:45", 3 to "10:00-10:50", 4 to "10:55-11:45",
        5 to "13:00-13:50", 6 to "13:55-14:45", 7 to "15:00-15:50", 8 to "15:55-16:45",
        9 to "17:00-17:50", 10 to "17:55-18:45", 11 to "19:00-19:50", 12 to "19:55-20:45",
        13 to "21:00-21:50",
    )

    fun of(period: Int): String = table[period] ?: ""

    /** "13:00-14:45" for a period range, "" when the range is unknown. */
    fun range(from: Int, to: Int): String {
        val start = of(from).substringBefore('-')
        val end = of(to).substringAfter('-', "")
        return if (start.isEmpty() || end.isEmpty()) "" else "$start-$end"
    }
}

/** SUSTech undergraduate 4.0 conversion (official 换算表), ported from the Python client. */
object Gpa {
    private val letter = mapOf(
        "A+" to 4.00, "A" to 3.94, "A-" to 3.85,
        "B+" to 3.73, "B" to 3.55, "B-" to 3.32,
        "C+" to 3.09, "C" to 2.78, "C-" to 2.42,
        "D+" to 2.08, "D" to 1.63, "D-" to 1.15,
        "F" to 0.00,
    )

    private val numeric = buildMap<Int, Double> {
        for (score in 90..100) put(score, 4.0)
        for (score in 85..89) put(score, 3.7)
        for (score in 80..84) put(score, 3.3)
        for (score in 77..79) put(score, 3.0)
        for (score in 73..76) put(score, 2.7)
        for (score in 70..72) put(score, 2.3)
        for (score in 67..69) put(score, 2.0)
        for (score in 63..66) put(score, 1.7)
        for (score in 60..62) put(score, 1.0)
    }

    fun points(grade: String, score: String): Double? = letter[grade.trim()]
        ?: score.trim().toDoubleOrNull()?.let { numeric[it.toInt()] }

    /** Weighted GPA plus the credit total it was computed over. */
    fun of(records: List<GradeRecord>): Pair<Double, Double> {
        var points = 0.0
        var credits = 0.0
        for (record in records) {
            if (record.credits <= 0) continue
            val gpa = points(record.grade, record.score) ?: continue
            points += gpa * record.credits
            credits += record.credits
        }
        if (credits == 0.0) return 0.0 to 0.0
        return Math.round(points / credits * 1000) / 1000.0 to credits
    }
}

// -- Records ------------------------------------------------------------------

data class Semester(val year: String, val term: String, val label: String, val labelEn: String) {
    companion object {
        fun from(raw: JSONObject) = Semester(
            year = raw.optString("XN", ""),
            term = raw.optString("XQ", ""),
            label = raw.optString("XNXQ", ""),
            labelEn = raw.optString("XNXQ_EN", ""),
        )
    }
}

/**
 * One row of the personal timetable (`xszykb`) — a course meeting.
 *
 * Field notes that cost real debugging time upstream:
 *  - `KEY` is `xq<weekday>_jc<block>`: the weekday is usable, the `jc` number is
 *    the *block index on that day*, NOT a period — periods come from KSJC/JSJC.
 *  - `JSJC` is the inclusive END period, not a count.
 *  - `ZC` is a 36-char bitmap; index 0 is week 1.
 */
data class ClassEntry(
    val taskId: String,
    val courseCode: String,
    val name: String,
    val teacher: String,
    val classGroup: String,
    val room: String,
    val weekday: Int,
    val periodFrom: Int,
    val periodTo: Int,
    val weeks: List<Int>,
) {
    fun meets(week: Int): Boolean = weeks.contains(week)

    /** First–last week, with a marker when the bitmap has gaps (odd/even weeks). */
    private var weeksLabel: String = ""

    val weekRangeText: String
        get() {
            if (weeks.isEmpty()) return weeksLabel
            val first = weeks.first()
            val last = weeks.last()
            val contiguous = weeks.size == last - first + 1
            return if (contiguous) "$first-$last" else "$first-$last (alternating)"
        }

    val timeText: String get() = PeriodTimes.range(periodFrom, periodTo)

    companion object {
        fun from(raw: JSONObject): ClassEntry? {
            val rawSchedule = raw.optString("SKSJ", "")
            if (rawSchedule.isEmpty()) return null
            val scheduleEn = raw.optString("SKSJ_EN", "")

            val lines = rawSchedule.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val linesEn = scheduleEn.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null

            val brackets = lines.drop(1).flatMap { extractBrackets(it) }
            val bracketsEn = linesEn.drop(1).flatMap { extractBrackets(it) }

            val teacher = bracketsEn.getOrNull(0) ?: brackets.getOrNull(0) ?: ""
            val classGroup = bracketsEn.getOrNull(1) ?: brackets.getOrNull(1) ?: ""
            // Brackets are ordered: teacher, class group, then weeks / room /
            // periods. Searching from the front would pick the teacher as the
            // room — skip the first two.
            val room = bracketsEn.drop(2).firstOrNull { !it.contains("周") && !it.contains("节") }
                ?: brackets.drop(2).firstOrNull { !it.contains("周") && !it.contains("节") }
                ?: ""

            val key = raw.optString("KEY", "")
            val weekday = key.substringAfter("xq", "").substringBefore("_").toIntOrNull() ?: 0
            val from = raw.optInt("KSJC", 0)
            val to = raw.optInt("JSJC", from)

            val entry = ClassEntry(
                taskId = raw.optString("RWH", ""),
                courseCode = codeFromTaskId(raw.optString("RWH", "")),
                name = lines.first(),
                teacher = teacher,
                classGroup = classGroup,
                room = room,
                weekday = weekday,
                periodFrom = from,
                periodTo = to,
                weeks = weeksFromBitmap(raw.optString("ZC", "")),
            )
            entry.weeksLabel = weeksTextFromLabel(bracketsEn) .ifEmpty { weeksTextFromLabel(brackets) }
            return entry
        }

        /**
         * Weeks are not always on the wire: `queryxszykbzong` (whole term)
         * carries the ZC bitmap, `queryxszykbzhou` (one week) does not and only
         * labels the row "1-16周". Fall back to that label so the row still
         * says which weeks the course runs.
         */
        fun weeksTextFromLabel(brackets: List<String>): String {
            val label = brackets.firstOrNull { it.contains("周") } ?: return ""
            val digits = Regex("[0-9]+(-[0-9]+)?").find(label)?.value ?: return ""
            val alternating = label.contains("单") || label.contains("双")
            return if (alternating) "$digits (alternating)" else digits
        }

        /** "[1-16周]" / "[一教225]" / "[1-2节]" -> "1-16周", "一教225", "1-2节". */
        private fun extractBrackets(line: String): List<String> =
            Regex("\\[([^\\]]+)\\]").findAll(line).map { it.groupValues[1] }.toList()

        /** `2026-2027-1-MSE301-001` -> `MSE301`. */
        fun codeFromTaskId(taskId: String): String =
            Regex("^\\d{4}-\\d{4}-\\d-(.+?)-\\d+$").find(taskId)?.groupValues?.get(1) ?: ""

        fun weeksFromBitmap(bitmap: String): List<Int> {
            val out = ArrayList<Int>()
            for ((index, char) in bitmap.withIndex()) {
                if (char == '1') out.add(index + 1)
            }
            return out
        }
    }
}

/** A course as the Courses tab shows it: one row per course, not per meeting. */
/** TIS joins co-teachers with a bare comma ("Li Lei,Liu Weishu"); the UI shows
 * them comma-spaced and de-duplicated, per the display rule the Python client
 * already follows. */
private fun normaliseNames(raw: String): String = raw
    .split(",", "、", "，")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .joinToString(", ")

data class CourseRow(
    val code: String,
    val name: String,
    val teacher: String,
    val classGroup: String,
    val rooms: String,
    val meetingsText: String,
    val weekRangeText: String,
) {
    companion object {
        /** Collapses every meeting of the same course into one row. */
        fun group(entries: List<ClassEntry>): List<CourseRow> = entries
            .groupBy { if (it.courseCode.isNotEmpty()) it.courseCode else it.name }
            .values
            .map { meetings ->
                val first = meetings.first()
                CourseRow(
                    code = first.courseCode,
                    name = first.name,
                    teacher = normaliseNames(meetings.map { it.teacher }.joinToString(",")),
                    classGroup = first.classGroup,
                    rooms = normaliseNames(meetings.map { it.room }.joinToString(",")),
                    // A lab course repeats the same slot once per group; the
                    // timetable lists each, the course row should not.
                    meetingsText = meetings
                        .sortedWith(compareBy({ it.weekday }, { it.periodFrom }))
                        .map { "${Weekday.short(it.weekday)} ${it.periodFrom}-${it.periodTo}" }
                        .distinct()
                        .joinToString(", "),
                    weekRangeText = first.weekRangeText,
                )
            }
            .sortedWith(compareBy({ it.code }, { it.name }))
    }
}

data class GradeRecord(
    val code: String,
    val name: String,
    val nameEn: String,
    val termLabel: String,
    val termLabelEn: String,
    val credits: Double,
    val grade: String,
    val score: String,
    val category: String,
    val passed: Boolean,
) {
    companion object {
        fun from(raw: JSONObject) = GradeRecord(
            code = raw.optString("kcdm", ""),
            name = raw.optString("kcmc", ""),
            nameEn = raw.optString("kcmc_en", ""),
            termLabel = raw.optString("xnxqmc", ""),
            termLabelEn = raw.optString("xnxqmcen", ""),
            credits = raw.optString("xf", "").toDoubleOrNull() ?: 0.0,
            grade = raw.optString("xscj", ""),
            score = raw.optString("zzcj", ""),
            category = raw.optString("kcxz", ""),
            passed = raw.optString("sfyjf", "1") != "0",
        )
    }
}

data class ExamRecord(
    val course: String,
    val code: String,
    val date: String,
    val session: String,
    val timeText: String,
    val periodFrom: Int,
    val periodTo: Int,
    val building: String,
    val room: String,
    val campus: String,
    val seat: String,
) {
    val sortKey: String get() = date

    companion object {
        fun from(raw: JSONObject): ExamRecord {
            val from = raw.optString("KSJC", "").toIntOrNull() ?: 0
            val to = raw.optString("JSJC", "").toIntOrNull() ?: from
            val clock = raw.optString("KSJTSJ", "").ifEmpty { PeriodTimes.range(from, to) }
            return ExamRecord(
                course = raw.optString("KCMC", ""),
                code = raw.optString("KCDM", ""),
                date = raw.optString("KSRQ", ""),
                session = raw.optString("KSSJDMC", ""),
                timeText = clock,
                periodFrom = from,
                periodTo = to,
                building = raw.optString("JXLMC", ""),
                room = raw.optString("JXCDMC", ""),
                campus = raw.optString("XIAOQUBMC", ""),
                seat = raw.optString("ZWH", ""),
            )
        }
    }
}

object Weekday {
    private val short = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** 1 = Monday … 7 = Sunday (TIS `xqN`). */
    fun short(weekday: Int): String = short.getOrNull(weekday - 1) ?: "?"

    fun isWeekend(weekday: Int): Boolean = weekday >= 6
}

/** Convenience for JSON arrays that may be null. */
internal fun JSONObject.arrayOrEmpty(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
