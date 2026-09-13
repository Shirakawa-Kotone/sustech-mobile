package edu.sustech.mobile.tis

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Hosts
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The TIS read surface the app uses — timetable, courses, grades, exams.
 *
 * TIS is CAS-fronted: there is no login API here. The session arrives as
 * cookies captured by [edu.sustech.mobile.ui.WebLoginActivity] after the user
 * signs in on the real page, and rides along on every request through the
 * shared cookie jar.
 *
 * Writes (course selection, bidding, evaluation) are deliberately absent — the
 * web client owns those, and they are irreversible.
 */
class TisApi(private val http: OkHttpClient) {

    // -- Reads ----------------------------------------------------------------

    /** POST /component/querydangqianxnxq — the active term. */
    fun currentSemester(): Semester {
        val body = postForm("/component/querydangqianxnxq", emptyMap())
        val json = parse(body) ?: throw ApiException("TIS returned a non-JSON semester", signInRequired = true)
        if (!json.has("XN") || !json.has("XQ")) {
            throw ApiException("TIS did not report the current semester", signInRequired = true)
        }
        return Semester.from(json)
    }

    /**
     * POST /component/querydangqianzc — the current teaching week.
     *
     * Answers with a bare number while a term is running; an empty body means
     * the term has not started, and an error page means the session is stale.
     */
    fun currentWeek(): Int? {
        val body = postForm("/component/querydangqianzc", emptyMap()).trim()
        return body.toIntOrNull()
    }

    /** POST /xszykb/queryxszykbzhou — personal timetable for one week. */
    fun weekSchedule(week: Int, semester: Semester = currentSemester()): List<ClassEntry> {
        val body = postForm(
            "/xszykb/queryxszykbzhou",
            mapOf("xn" to semester.year, "xq" to semester.term, "zc" to week.toString()),
        )
        return parseEntries(body)
    }

    /** POST /xszykb/queryxszykbzong — the whole term, every meeting. */
    fun semesterSchedule(semester: Semester = currentSemester()): List<ClassEntry> {
        val body = postForm(
            "/xszykb/queryxszykbzong",
            mapOf("xn" to semester.year, "xq" to semester.term),
        )
        return parseEntries(body)
    }

    /**
     * POST /cjgl/grcjcx/grcjcx — posted grades.
     *
     * `pageSize` is 500 because the endpoint pages, and one page is enough for
     * a whole degree; the Python client does the same.
     */
    fun grades(): List<GradeRecord> {
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
        return (0 until rows.length()).map { GradeRecord.from(rows.getJSONObject(it)) }
    }

    /** POST /component/queryKsxxByXs — student exam schedule (empty until published). */
    fun exams(): List<ExamRecord> {
        val body = postJson("/component/queryKsxxByXs", JSONObject())
        val json = parseArray(body) ?: throw ApiException("TIS returned no exam list", signInRequired = true)
        return (0 until json.length())
            .map { ExamRecord.from(json.getJSONObject(it)) }
            .sortedBy { it.sortKey }
    }

    /** True when the stored session is still good (cheap probe). */
    fun isSignedIn(): Boolean = runCatching { currentSemester() }.isSuccess

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
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", Hosts.TIS + "/")
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw ApiException("TIS refused the request (HTTP ${response.code})", signInRequired = true)
                }
                body
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        // A stale session makes TIS answer with an HTML page or a JSON blob
        // that tells the user to log in again — never a data payload.
        if (text.contains("请用户重新登录") || text.contains("重新登录")) {
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
