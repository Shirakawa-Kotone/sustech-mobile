package edu.sustech.mobile.library

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * The two library questions worth a phone screen: how full is it, and where is
 * that book.
 *
 * Neither needs an account, so both work off campus — which is the whole point,
 * since you ask them on the way there rather than after signing in.
 *
 * * **How full** — the library's own homepage server-renders a live headcount
 *   (`在馆人数`) and a per-building split. There is no JSON behind it; the
 *   number is in the HTML, so it is read from the HTML. There is no seat or
 *   "capacity" figure anywhere, so this reports people, not a percentage.
 * * **Where is it** — Primo's public record API, the same one the SPA calls.
 *   Search gives the record; the record gives `delivery.bestlocation`, which
 *   names the building, the collection, the call number and whether it is on
 *   the shelf. Reading the shelf mark off a search result is what turns "the
 *   library has it" into "it is on the 3rd floor of Lynn at TH7 /JC 1".
 */
class LibraryApi(private val http: OkHttpClient) {

    /** Who is in the building right now, and when it shuts. */
    data class Inside(val total: Int, val places: List<Place>, val hours: String) {
        /** True when the page answered but carried no count. */
        val isEmpty: Boolean get() = total <= 0 && places.isEmpty()
    }

    /** One building's share of the headcount. */
    data class Place(val name: String, val people: Int)

    /** One search result. */
    data class Book(
        val context: String,
        val docId: String,
        val title: String,
        val creator: String,
        val kind: String,
        val year: String,
    )

    /** Where a copy sits, and whether it can be had. */
    data class Shelf(
        val library: String,
        val collection: String,
        val callNumber: String,
        val status: String,
    ) {
        val onShelf: Boolean get() = status.equals("available", ignoreCase = true)
    }

    /** One record's holdings. */
    data class Record(
        val title: String,
        val creator: String,
        val year: String,
        val shelves: List<Shelf>,
        val electronicOnly: Boolean,
    )

    // -- How full -------------------------------------------------------------

    /**
     * The headcount, straight off the library homepage.
     *
     * Short TTL: the page itself is regenerated per request but its number moves
     * slowly, and a reader standing at the gate should get the newest one.
     */
    fun inside(): Inside = Cache.get("library.inside", Cache.TTL_LIVE) {
        val html = get(HOMEPAGE)
        Inside(
            total = TOTAL.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            places = PLACE.findAll(stripComments(html)).mapNotNull { match ->
                val people = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Place(match.groupValues[1].trim(), people)
            }.toList(),
            hours = HOURS.find(html)?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }

    // -- Where is it ----------------------------------------------------------

    /** Catalogue search. Returns at most [limit] records. */
    fun search(query: String, limit: Int = 20): List<Book> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return Cache.get("library.search.$trimmed.$limit", Cache.TTL_LIST) {
            val body = get(SEARCH + "?" + params(trimmed, limit, null))
            val docs = parse(body)?.optJSONArray("docs") ?: JSONArray()
            (0 until docs.length()).mapNotNull { index ->
                book(docs.optJSONObject(index) ?: return@mapNotNull null)
            }
        }
    }

    /** The holdings for one record: every copy, where it sits, and its state. */
    fun record(book: Book): Record = Cache.get("library.record.${book.docId}", Cache.TTL_LIST) {
        val body = get("$SEARCH/${book.context}/${book.docId}?vid=$VID&lang=en&inst=$INST")
        val json = parse(body) ?: throw ApiException("the catalogue returned a non-JSON record")
        val delivery = json.optJSONObject("delivery")
        val display = json.optJSONObject("pnx")?.optJSONObject("display")
        Record(
            title = first(display, "title").ifEmpty { book.title },
            creator = cleanCreator(first(display, "creator").ifEmpty { book.creator }),
            year = first(display, "creationdate").ifEmpty { book.year },
            shelves = shelves(delivery),
            electronicOnly = shelves(delivery).isEmpty() &&
                delivery?.optJSONArray("deliveryCategory")?.let { categories ->
                    (0 until categories.length()).any { categories.optString(it).contains("Alma-E") }
                } == true,
        )
    }

    /**
     * The physical copies in a delivery block.
     *
     * `holding` is the full list; `bestlocation` is the one copy Primo would put
     * first. A record with neither is electronic-only, which is a real answer
     * ("online, not on a shelf") and not an error.
     */
    private fun shelves(delivery: JSONObject?): List<Shelf> {
        val rows = delivery?.optJSONArray("holding") ?: JSONArray()
        val out = (0 until rows.length()).mapNotNull { index ->
            shelf(rows.optJSONObject(index) ?: return@mapNotNull null)
        }
        if (out.isNotEmpty()) return out
        return listOfNotNull(delivery?.optJSONObject("bestlocation")).mapNotNull { shelf(it) }
    }

    private fun shelf(row: JSONObject): Shelf? {
        val library = row.optString("mainLocation").trim()
        val collection = row.optString("subLocation").trim()
        val callNumber = row.optString("callNumber").trim()
        if (library.isEmpty() && callNumber.isEmpty()) return null
        return Shelf(
            library = library,
            collection = collection,
            callNumber = callNumber,
            status = row.optString("availabilityStatus").trim(),
        )
    }

    private fun book(doc: JSONObject): Book? {
        val control = doc.optJSONObject("pnx")?.optJSONObject("control")
        val display = doc.optJSONObject("pnx")?.optJSONObject("display")
        val docId = control?.optJSONArray("recordid")?.optString(0).orEmpty()
        val title = first(display, "title")
        if (docId.isEmpty() || title.isEmpty()) return null
        return Book(
            context = doc.optString("context", "L"),
            docId = docId,
            title = title,
            creator = cleanCreator(first(display, "creator")),
            kind = first(display, "type"),
            year = first(display, "creationdate"),
        )
    }

    /** A display field, which Primo always sends as a one-element array. */
    private fun first(display: JSONObject?, key: String): String =
        display?.optJSONArray(key)?.optString(0).orEmpty().trim()

    /**
     * Author strings repeat after a `$$Q` separator
     * (`曾兆华, 杨建文主编$$Q曾兆华, 杨建文主编`); keep the first form only.
     */
    private fun cleanCreator(raw: String): String =
        raw.substringBefore("\$\$").trim().removeSuffix(",").trim()

    // -- Transport ------------------------------------------------------------

    private fun params(query: String, limit: Int, offset: Int?): String = listOf(
        "vid=$VID", "inst=$INST", "tab=Everything", "scope=MyInstitution",
        "q=" + URLEncoder.encode("any,contains,$query", "UTF-8"),
        "lang=en", "sort=rank",
        "limit=$limit", "offset=${offset ?: 0}",
        "pcAvailability=true", "skipDelivery=N",
    ).joinToString("&")

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://sustc.primo.exlibrisgroup.com.cn/discovery/search?vid=$VID")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code >= 400) throw ApiException("library answered HTTP ${response.code}")
                body
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
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

    private companion object {
        const val UA = "Mozilla/5.0"

        /** The library's own homepage; the headcount is rendered into it. */
        const val HOMEPAGE = "https://lib.sustech.edu.cn/main.htm"

        const val SEARCH = "https://sustc.primo.exlibrisgroup.com.cn/primaws/rest/pub/pnxs"
        const val VID = "86SUSTC_INST:86SUSTC"
        const val INST = "86SUSTC"

        val TOTAL = Regex("""在馆人数：<span class="num">(\d+)</span>""")
        val PLACE = Regex("""class="name">([^<]+)</span><span class="num2">(\d+)</span>""")
        val HOURS = Regex("""class="num">(\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2})<""")

        /**
         * The homepage hides 涵泳 behind an HTML comment, so an entry that is
         * not shown must not be counted — the raw counts would otherwise
         * include a building the library has stopped advertising.
         */
        fun stripComments(html: String): String =
            html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
    }
}
