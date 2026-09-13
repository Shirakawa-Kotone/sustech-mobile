package edu.sustech.mobile.tis

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.sso.CasLogin
import edu.sustech.mobile.sso.Session
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Server phrases that mean "your session is gone" rather than "your request was
 * wrong". They arrive inside a normal error envelope, so they have to be
 * recognised and turned into a silent re-login — never shown as a network error.
 */
private val STALE_MARKERS = listOf("请用户重新登录", "重新登录", "无效会话", "未登录", "请登录", "用户未登录")

/**
 * The TIS read surface the app uses — timetable, courses, grades, exams.
 *
 * TIS is CAS-fronted: there is no login API here. The session arrives as
 * cookies gathered by [CasLogin] from the stored school account and rides along
 * on every request through the shared cookie jar.
 *
 * Writes (course selection, bidding, evaluation) are deliberately absent — the
 * web client owns those, and they are irreversible.
 */
class TisApi(private val http: OkHttpClient) {

    // -- Reads ----------------------------------------------------------------

    /** POST /component/querydangqianxnxq — the active term. */
    fun currentSemester(): Semester = Cache.get("tis.semester", Cache.TTL_SEMESTER) {
        currentSemesterUncached()
    }

    private fun currentSemesterUncached(): Semester = withRelogin {
        val body = postForm("/component/querydangqianxnxq", emptyMap())
        val json = parse(body) ?: throw ApiException("TIS returned a non-JSON semester", signInRequired = true)
        if (!json.has("XN") || !json.has("XQ")) {
            throw ApiException("TIS did not report the current semester", signInRequired = true)
        }
        return@withRelogin Semester.from(json)
    }

    /**
     * POST /component/querydangqianzc — the current teaching week.
     *
     * Answers with a bare number while a term is running; an empty body means
     * the term has not started, and an error page means the session is stale.
     */
    fun currentWeek(): Int? = withRelogin {
        val body = postForm("/component/querydangqianzc", emptyMap()).trim()
        return@withRelogin body.toIntOrNull()
    }

    /** POST /xszykb/queryxszykbzhou — personal timetable for one week. */
    fun weekSchedule(week: Int, semester: Semester = currentSemester()): List<ClassEntry> =
        Cache.get("tis.week.$week", Cache.TTL_LIST) { weekScheduleUncached(week, semester) }

    private fun weekScheduleUncached(week: Int, semester: Semester): List<ClassEntry> = withRelogin {
        val body = postForm(
            "/xszykb/queryxszykbzhou",
            mapOf("xn" to semester.year, "xq" to semester.term, "zc" to week.toString()),
        )
        return@withRelogin parseEntries(body)
    }

    /** POST /xszykb/queryxszykbzong — the whole term, every meeting. */
    fun semesterSchedule(semester: Semester = currentSemester()): List<ClassEntry> =
        Cache.get("tis.schedule", Cache.TTL_LIST) { semesterScheduleUncached(semester) }

    private fun semesterScheduleUncached(semester: Semester): List<ClassEntry> = withRelogin {
        val body = postForm(
            "/xszykb/queryxszykbzong",
            mapOf("xn" to semester.year, "xq" to semester.term),
        )
        return@withRelogin parseEntries(body)
    }

    /**
     * POST /cjgl/grcjcx/grcjcx — posted grades.
     *
     * `pageSize` is 500 because the endpoint pages, and one page is enough for
     * a whole degree; the Python client does the same.
     */
    fun grades(): List<GradeRecord> = Cache.get("tis.grades", Cache.TTL_LIST) { gradesUncached() }

    private fun gradesUncached(): List<GradeRecord> = withRelogin {
        val payload = JSONObject()
            .put("xn", JSONObject.NULL)
            .put("xq", JSONObject.NULL)
            .put("kcmc", JSONObject.NULL)
            .put("cxbj", "-1")
            .put("pylx", "1")
            .put("current", 1)
            .put("pageSize", 500)
        val body = postJson("/cjgl/grcjcx/grcjcx", payload)
        val json = parse(body) ?: throw ApiException("TIS returned a non-JSON grade list", signInRequired = true)
        val rows = json.optJSONObject("content")?.optJSONArray("list") ?: JSONArray()
        return@withRelogin (0 until rows.length()).map { GradeRecord.from(rows.getJSONObject(it)) }
    }

    /** POST /component/queryKsxxByXs — student exam schedule (empty until published). */
    fun exams(): List<ExamRecord> = Cache.get("tis.exams", Cache.TTL_LIST) { examsUncached() }

    private fun examsUncached(): List<ExamRecord> = withRelogin {
        val body = postJson("/component/queryKsxxByXs", JSONObject())
        val json = parseArray(body) ?: throw ApiException("TIS returned no exam list", signInRequired = true)
        return@withRelogin (0 until json.length())
            .map { ExamRecord.from(json.getJSONObject(it)) }
            .sortedBy { it.sortKey }
    }

    /** True when the stored session is still good (cheap probe). */
    fun isSignedIn(): Boolean = runCatching { currentSemester() }.isSuccess

    /**
     * POST /component/queryKbjg — the semester's teaching grid: which clock times
     * each period runs at. The table is not the same every year, and the values
     * this app used to hard-code were the exam-hall ones, so it asks TIS and
     * hands the answer to [PeriodTimes].
     */
    fun loadSlotTimes(semester: Semester = currentSemester(), week: Int? = null): Map<Int, Pair<String, String>> =
        Cache.get("tis.slots", Cache.TTL_SLOTS) {
            withRelogin {
                val body = postForm(
                    "/component/queryKbjg",
                    mapOf(
                        "xn" to semester.year,
                        "xq" to semester.term,
                        "zc" to (week ?: currentWeek() ?: 1).toString(),
                    ),
                )
                val content = parse(body)?.optJSONArray("content") ?: JSONArray()
                val grid = LinkedHashMap<Int, Pair<String, String>>()
                for (index in 0 until content.length()) {
                    val row = content.optJSONObject(index) ?: continue
                    val slot = row.optString("xj", "").toIntOrNull() ?: continue
                    val from = row.optString("kssj", "")
                    val to = row.optString("jssj", "")
                    if (from.isNotEmpty() && to.isNotEmpty()) grid[slot] = from to to
                }
                PeriodTimes.useFetched(grid)
                grid
            }
        }

    /**
     * Retries once after a silent CAS re-login when TIS says the session is
     * gone — the app never asks the user to sign in again.
     *
     * The desktop user agent matters here: TIS refuses the mobile web UI, and
     * the native client identifies as the same desktop client the Python
     * authorizer does.
     */
    private fun <T> withRelogin(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.signInRequired && Session.reloginCourses() != null) block() else throw e
    }

    // -- Transport ------------------------------------------------------------

    private fun postForm(path: String, form: Map<String, String>): String {
        val builder = okhttp3.FormBody.Builder()
        for ((key, value) in form) builder.add(key, value)
        return execute(path, builder.build())
    }

    private fun postJson(path: String, payload: JSONObject): String =
        execute(path, payload.toString().toRequestBody(JSON))

    private fun execute(path: String, body: RequestBody): String {
        val request = Request.Builder()
            .url(Hosts.TIS + path)
            .post(body)
            .header("User-Agent", CasLogin.UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", Hosts.TIS + "/")
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code in 300..399) {
                    // TIS answers a dead session by redirecting rather than by
                    // saying so; the redirect target is the login flow.
                    throw ApiException("TIS session expired (HTTP ${response.code})", signInRequired = true)
                }
                if (response.code == 401 || response.code == 403) {
                    throw ApiException("TIS refused the request (HTTP ${response.code})", signInRequired = true)
                }
                body
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        // A stale session makes TIS answer with an HTML page, a JSON blob, or a
        // terse "无效会话，未登录" inside an otherwise fine envelope — never a data
        // payload. All of those mean one thing: sign in again, silently.
        if (STALE_MARKERS.any { text.contains(it) }) {
            throw ApiException("TIS session expired", signInRequired = true)
        }
        return text
    }

    private fun parse(text: String): JSONObject? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArray(text: String): JSONArray? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("[")) return null
        return try {
            JSONArray(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEntries(body: String): List<ClassEntry> {
        val array = parseArray(body) ?: throw ApiException("TIS returned no timetable", signInRequired = true)
        return (0 until array.length())
            .mapNotNull { ClassEntry.from(array.getJSONObject(it)) }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
