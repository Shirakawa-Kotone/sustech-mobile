package edu.sustech.mobile.ui.pms

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.pms.ColorMode
import edu.sustech.mobile.pms.Duplex
import edu.sustech.mobile.pms.PrintJob
import edu.sustech.mobile.ui.ListFragment

/**
 * Print queue — documents already uploaded and waiting at any printer, with
 * the per-job delete the website performs on the same list.
 */
class JobsFragment : ListFragment<PrintJob>(R.layout.fragment_jobs) {

    override fun cachePrefix() = "pms.jobs"

    override fun rowLayout() = R.layout.item_print_job

    override fun emptyText() = getString(R.string.jobs_empty)

    override suspend fun fetch(): List<PrintJob> = App.api.printJobs()

    override fun onReady(view: View) {
        view.findViewById<ExtendedFloatingActionButton>(R.id.fab_upload).setOnClickListener {
            startActivity(Intent(requireContext(), UploadActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from an upload the queue changed. onViewCreated runs
        // first, so a non-null view means the list plumbing is in place.
        if (view != null) load()
    }

    override fun bindRow(view: View, item: PrintJob, position: Int) {
        view.findViewById<TextView>(R.id.job_name).text = item.fileName
        view.findViewById<TextView>(R.id.job_options).text = optionsText(item)
        view.findViewById<TextView>(R.id.job_meta).text =
            getString(R.string.jobs_id, item.jobId, item.uploadedAt)
        view.findViewById<ImageButton>(R.id.job_delete).setOnClickListener { confirmDelete(item) }
    }

    /** Same five facts the site shows per queued document. */
    private fun optionsText(job: PrintJob): String {
        val parts = ArrayList<String>()
        if (job.paper.isNotEmpty()) parts.add(job.paper)
        if (job.totalPages > 0) parts.add(getString(R.string.jobs_pages, job.totalPages))
        parts.add(getString(R.string.jobs_copies, job.copies))
        parts.add(getString(if (job.isColor) R.string.upload_color_color else R.string.upload_color_bw))
        parts.add(
            getString(
                when (job.duplex) {
                    Duplex.SHORT_EDGE -> R.string.upload_duplex_short
                    Duplex.LONG_EDGE -> R.string.upload_duplex_long
                    else -> R.string.upload_duplex_single
                },
            ),
        )
        return parts.joinToString(" · ")
    }

    private fun confirmDelete(job: PrintJob) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.jobs_delete_title)
            .setMessage(getString(R.string.jobs_delete_message, job.fileName, job.jobId))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> delete(job) }
            .show()
    }

    private fun delete(job: PrintJob) {
        runIo(
            block = { App.api.deletePrintJob(job.jobId) },
            onOk = { serverMessage ->
                App.toast(
                    if (serverMessage == null) getString(R.string.jobs_deleted, job.fileName)
                    else getString(R.string.jobs_delete_failed, serverMessage),
                )
                load()
            },
            onErr = { error -> showError(error) },
        )
    }
}
