package edu.sustech.mobile.ui.tis

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.tis.Gpa
import edu.sustech.mobile.tis.GradeRecord
import edu.sustech.mobile.ui.ListFragment

/**
 * Grades — every posted grade, newest term first, with the credit-weighted GPA
 * the Python client computes from the same records.
 */
class GradesFragment : ListFragment<GradeRecord>(R.layout.fragment_tis_week) {

    override fun rowLayout() = R.layout.item_tis_grade

    override fun emptyText() = getString(R.string.tis_grades_empty)

    override fun errorText(error: Throwable): String =
        if (error is ApiException && error.signInRequired) getString(R.string.tis_not_signed_in)
        else super.errorText(error)

    override suspend fun fetch(): List<GradeRecord> =
        App.tis.grades().sortedWith(compareByDescending<GradeRecord> { it.termLabel }.thenBy { it.name })

    override fun onLoaded(rows: List<GradeRecord>) {
        val (gpa, credits) = Gpa.of(rows)
        view?.findViewById<TextView>(R.id.week_header)?.text = getString(
            R.string.tis_gpa,
            String.format("%.3f", gpa),
            String.format("%.1f", credits),
        )
    }

    override fun bindRow(view: View, item: GradeRecord, position: Int) {
        view.findViewById<TextView>(R.id.grade_term).text = item.termLabelEn.ifEmpty { item.termLabel }
        view.findViewById<TextView>(R.id.grade_name).text = item.name
        view.findViewById<TextView>(R.id.grade_meta).text = listOf(
            item.code,
            if (item.nameEn.isNotEmpty()) item.nameEn else "",
            getString(R.string.tis_credits, String.format("%.1f", item.credits)),
            if (item.score.isNotEmpty()) getString(R.string.tis_score, item.score) else "",
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        view.findViewById<TextView>(R.id.grade_value).text = item.grade
    }
}
