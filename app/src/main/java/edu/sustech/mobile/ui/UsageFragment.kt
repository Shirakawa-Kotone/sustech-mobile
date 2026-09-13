package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.pms.ReportType
import edu.sustech.mobile.pms.UsageRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 使用记录 — the paginated print / scan / copy history, with the same date
 * range and type filters the report page has.
 */
class UsageFragment : PmsListFragment<UsageRecord>(R.layout.fragment_usage) {

    private val display = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val types = listOf(ReportType.PRINT, ReportType.SCAN, ReportType.COPY)

    private var page = 1
    private var totalPages = 1

    override fun rowLayout() = R.layout.item_usage

    override fun emptyText() = getString(R.string.usage_empty)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val calendar = Calendar.getInstance()
        view.findViewById<EditText>(R.id.input_end).setText(display.format(calendar.time))
        calendar.add(Calendar.DAY_OF_YEAR, -90)
        view.findViewById<EditText>(R.id.input_begin).setText(display.format(calendar.time))

        view.findViewById<Spinner>(R.id.type_spinner).adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.usage_type_print),
                getString(R.string.usage_type_scan),
                getString(R.string.usage_type_copy),
            ),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        view.findViewById<MaterialButton>(R.id.btn_query).setOnClickListener {
            page = 1
            load()
        }
        view.findViewById<MaterialButton>(R.id.btn_prev).setOnClickListener {
            if (page > 1) {
                page -= 1
                load()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_next).setOnClickListener {
            if (page < totalPages) {
                page += 1
                load()
            }
        }
    }

    override suspend fun fetch(): List<UsageRecord> {
        val root = view ?: return emptyList()
        val begin = toWire(root.findViewById<EditText>(R.id.input_begin).text.toString())
        val end = toWire(root.findViewById<EditText>(R.id.input_end).text.toString())
        val type = types.getOrElse(root.findViewById<Spinner>(R.id.type_spinner).selectedItemPosition) {
            ReportType.PRINT
        }
        val result = App.api.usage(begin, end, type, page, PAGE_SIZE)
        totalPages = result.second
        return result.first
    }

    override fun onLoaded(rows: List<UsageRecord>) {
        view?.findViewById<TextView>(R.id.page_label)?.text = getString(R.string.usage_page, page, totalPages)
    }

    override fun bindRow(view: View, item: UsageRecord, position: Int) {
        view.findViewById<TextView>(R.id.usage_when).text = item.happenedAt
        view.findViewById<TextView>(R.id.usage_money).text = item.moneyText
        view.findViewById<TextView>(R.id.usage_detail).text =
            "${item.typeLabel} · ${item.paper.ifEmpty { "—" }} · ${item.pages} 页 · ${item.settleLabel} · #${item.mfpSn}"
    }

    /** The form takes either yyyy-MM-dd or yyyyMMdd; the API wants yyyyMMdd. */
    private fun toWire(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        display.parse(trimmed)?.let { return WIRE.format(it) }
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length == 8) return digits
        throw IllegalArgumentException("date: $trimmed")
    }

    private companion object {
        const val PAGE_SIZE = 20
        val WIRE = SimpleDateFormat("yyyyMMdd", Locale.US)
    }
}
