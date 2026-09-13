package edu.sustech.mobile.ui.tis

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.tis.ExamRecord
import edu.sustech.mobile.ui.ListFragment

/** Exams — the published exam schedule; TIS returns an empty list until it is out. */
class ExamsFragment : ListFragment<ExamRecord>(R.layout.fragment_list) {

    override fun cachePrefix() = "tis.exams"

    override fun rowLayout() = R.layout.item_tis_exam

    override fun emptyText() = getString(R.string.tis_exams_empty)

    override fun errorText(error: Throwable): String =
        if (error is ApiException && error.signInRequired) getString(R.string.tis_not_signed_in)
        else super.errorText(error)

    override suspend fun fetch(): List<ExamRecord> = App.tis.exams()

    override fun bindRow(view: View, item: ExamRecord, position: Int) {
        view.findViewById<TextView>(R.id.exam_date).text = item.date
        view.findViewById<TextView>(R.id.exam_name).text = item.course
        view.findViewById<TextView>(R.id.exam_meta).text = listOf(
            item.timeText,
            item.session,
            item.building,
            item.room,
            if (item.seat.isNotEmpty()) getString(R.string.tis_exam_seat, item.seat) else "",
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
