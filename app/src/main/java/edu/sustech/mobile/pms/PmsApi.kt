package edu.sustech.mobile.pms

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.AppConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * PMS failures are [edu.sustech.mobile.core.ApiException]s; the alias keeps the
 * call sites readable inside this package.
 */
typealias PmsException = edu.sustech.mobile.core.ApiException

/**
 * The whole PMS HTTP surface, one method per website page.
 *
 * Everything the 联创 print site does lives behind `/api/client/…`; the
 * envelope is always `{code, message, result}` and `code == 0` means success.
 * This class owns the wire format so the UI never touches a JSONObject.
 *
 * The base URL is read on every call, so switching servers at runtime (login
 * screen or account tab) takes effect without rebuilding the client.
 */
class PmsApi(
    private val http: OkHttpClient,
    private val baseUrl: () -> String = { AppConfig.DEFAULT_BASE_URL },
) {

    // -- Endpoints the site exposes -------------------------------------------

    /** POST /client/Auth/Check — who is signed in. Also the session probe. */
    fun check(): AccountInfo {
        val body = postJson("/api/client/Auth/Check", JSONObject())
        val result = body.optJSONObject("result") ?: JSONObject()
        return AccountInfo(
            trueName = result.optString("szTrueName", ""),
            logonName = result.optString("szLogonName", ""),
            raw = result,
        )
    }

    /** GET /client/Station/GetSrvList — the print-point dropdown. */
    fun serverGroups(): List<ServerGroup> {
        val array = getArray("/api/client/Station/GetSrvList", mapOf("timestamp" to "0"))
        return (0 until array.length()).map { ServerGroup.from(array.getJSONObject(it)) }
    }

    /** GET /client/Station/GetList — every printer / copier / scanner. */
    fun stations(): List<Station> {
        val array = getArray("/api/client/Station/GetList", mapOf("timestamp" to "0"))
        return (0 until array.length()).map { Station.from(array.getJSONObject(it)) }
    }

    /** GET /client/PrintJob/Get — uploaded but not yet printed. */
    fun printJobs(): List<PrintJob> {
        val array = getArray("/api/client/PrintJob/Get", mapOf("timestamp" to "0"))
        return (0 until array.length()).map { PrintJob.from(array.getJSONObject(it)) }
    }

    /**
     * POST /client/PrintJob/Del — delete a queued document.
     *
     * Returns null on success, or the server message on failure (the site
     * reports "already printed" as `code = -1`, not as an HTTP error).
     */
    fun deletePrintJob(jobId: Long): String? {
        val body = postJson(
            "/api/client/PrintJob/Del",
            JSONObject().put("dwJobId", jobId).put("dwOldJobId", jobId),
            throwOnError = false,
        )
        val code = body.optInt("code", -1)
        return if (code == 0) null else body.optString("message", "code=$code")
    }

    /** GET /client/Scan/Get — scanned documents waiting for pickup. */
    fun scanJobs(): List<ScanJob> {
        val array = getArray("/api/client/Scan/Get", mapOf("timestamp" to "0"))
        return (0 until array.length()).map { ScanJob.from(array.getJSONObject(it)) }
    }

    /** POST /client/Scan/Del — delete a scanned document. */
    fun deleteScanJob(jobId: Long): String? {
        val body = postJson(
            "/api/client/Scan/Del",
            JSONObject().put("dwJobId", jobId),
            throwOnError = false,
        )
        val code = body.optInt("code", -1)
        return if (code == 0) null else body.optString("message", "code=$code")
    }

    /**
     * POST /client/Report/DetailPage — paginated usage records.
     * Returns the rows plus the total page count the server reports.
     */
    fun usage(
        begin: String,
        end: String,
        type: Int,
        page: Int,
        pageSize: Int,
    ): Pair<List<UsageRecord>, Int> {
        val payload = JSONObject()
            .put("dwBeginDate", begin)
            .put("dwEndDate", end)
            .put("dwType", type)
            .put("dwPageNo", page)
            .put("dwRowCount", pageSize)
        val body = postJson("/api/client/Report/DetailPage", payload)
        val array = body.optJSONArray("result") ?: JSONArray()
        val rows = (0 until array.length()).map { UsageRecord.from(array.getJSONObject(it)) }
        val totalPages = body.optInt("dwTotalPage", 1).coerceAtLeast(1)
        return rows to totalPages
    }

    /**
     * POST /client/CloudPrint/Upload — the 云打印 page.
     *
     * Uploading is free; money is taken at the printer when the job is
     * collected. `dwFrom = 0` means "all pages" and `dwTo` is ignored then.
     */
    fun upload(
        file: File,
        options: UploadOptions,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val url = (baseUrl() + "/api/client/CloudPrint/Upload").toHttpUrlOrNull()
            ?: throw PmsException("Bad server URL: ${baseUrl()}")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("szPath", file.name, ProgressRequestBody(file, onProgress))
            .addFormDataPart("dwColor", options.color.toString())
            .addFormDataPart("dwPaperId", options.paper.toString())
            .addFormDataPart("dwDuplex", options.duplex.toString())
            .addFormDataPart("dwFrom", options.pageFrom.toString())
            .addFormDataPart("dwTo", options.pageTo.toString())
            .addFormDataPart("dwCopies", options.copies.toString())
            .addFormDataPart("BackURL", "result.html")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Referer", baseUrl() + "/client/new/cprintPc/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val text = execute(request, allowErrorStatus = true)
        if (text.startsWith("Access forbidden")) {
            throw PmsException("Off campus", offCampus = true)
        }
        val json = parseOrNull(text) ?: throw PmsException("Non-JSON response from upload")
        val code = json.optInt("code", -1)
        if (code != 0) throw PmsException(json.optString("message", "code=$code"))
        return json.optString("message", "").ifEmpty { "ok" }
    }

    // -- Transport ------------------------------------------------------------

    private fun getArray(path: String, params: Map<String, String>): JSONArray {
        val builder = (baseUrl() + path).toHttpUrlOrNull()?.newBuilder()
            ?: throw PmsException("Bad server URL: ${baseUrl()}")
        for ((k, v) in params) builder.addQueryParameter(k, v)
        val request = Request.Builder()
            .url(builder.build())
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", baseUrl() + "/client/new/cprintPc/")
            .build()
        val json = parseOrNull(execute(request)) ?: throw PmsException("Non-JSON response — signed in?")
        val code = json.optInt("code", -1)
        if (code != 0) throw PmsException(json.optString("message", "code=$code"))
        return json.optJSONArray("result") ?: JSONArray()
    }

    private fun postJson(path: String, payload: JSONObject, throwOnError: Boolean = true): JSONObject {
        val request = Request.Builder()
            .url(baseUrl() + path)
            .post(payload.toString().toRequestBody(JSON))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", baseUrl() + "/client/new/cprintPc/")
            .build()
        val json = parseOrNull(execute(request)) ?: throw PmsException("Non-JSON response — signed in?")
        if (throwOnError && json.optInt("code", -1) != 0) {
            val code = json.optInt("code", -1)
            throw PmsException(json.optString("message", "code=$code"))
        }
        return json
    }

    private fun execute(request: Request, allowErrorStatus: Boolean = false): String {
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 403 || text.startsWith("Access forbidden")) {
                    throw PmsException("Off campus (HTTP ${response.code})", offCampus = true)
                }
                if (response.code == 413) {
                    throw PmsException("File too large (HTTP 413)")
                }
                if (!allowErrorStatus && response.code >= 400) {
                    throw PmsException("HTTP ${response.code}")
                }
                return text
            }
        } catch (e: IOException) {
            throw PmsException(e.message ?: "network error")
        }
    }

    private fun parseOrNull(text: String): JSONObject? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Options for a cloud-print upload — the five controls on the 云打印 page. */
data class UploadOptions(
    val color: Int = ColorMode.BW,
    val paper: Int = Paper.UNSPECIFIED,
    val duplex: Int = Duplex.SINGLE,
    val pageFrom: Int = 0,
    val pageTo: Int = 0,
    val copies: Int = 1,
) {
    /** The site sends dwFrom=0 for "all pages" and ignores dwTo. */
    fun normalised(): UploadOptions {
        val from = if (pageFrom <= 0) 0 else pageFrom.coerceAtLeast(1)
        val to = if (from == 0) 0 else maxOf(from, if (pageTo <= 0) from else pageTo)
        return copy(pageFrom = from, pageTo = to, copies = copies.coerceAtLeast(1))
    }
}

/** File body that reports upload progress to the UI. */
private class ProgressRequestBody(
    private val file: File,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress(sent, total)
            }
        }
    }
}
