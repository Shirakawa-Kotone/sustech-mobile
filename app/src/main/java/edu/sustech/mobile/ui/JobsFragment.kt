package edu.sustech.mobile.ui

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.pms.PrintJob

/**
 * 打印文档 — documents already uploaded and waiting at the printer, with the
 * per-job delete the website performs on the same list.
 */
class JobsFragment : PmsListFragment<PrintJob>(R.layout.fragment_jobs) {

    override fun rowLayout() = R.layout.item_print_job

    override fun emptyText() = getString(R.string.jobs_empty)

    override suspend fun fetch(): List<PrintJob> = App.api.printJobs()

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        view.findViewById<TextView>(R.id.job_options).text = item.optionsText
        view.findViewById<TextView>(R.id.job_meta).text = "ID ${item.jobId} · ${item.uploadedAt}"
        view.findViewById<ImageButton>(R.id.job_delete).setOnClickListener { confirmDelete(item) }
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
                val message = if (serverMessage == null) {
                    getString(R.string.jobs_deleted, job.fileName)
                } else {
                    getString(R.string.jobs_delete_failed, serverMessage)
                }
                android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
                load()
            },
            onErr = { error -> showError(error) },
        )
    }
}
