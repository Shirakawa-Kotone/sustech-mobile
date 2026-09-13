package edu.sustech.mobile.ui.pms

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.pms.ScanJob
import edu.sustech.mobile.ui.ListFragment

/** Scans — documents waiting for pickup, with per-document delete. */
class ScanFragment : ListFragment<ScanJob>(R.layout.fragment_list) {

    override fun cachePrefix() = "pms.scans"

    override fun rowLayout() = R.layout.item_scan_job

    override fun emptyText() = getString(R.string.scan_empty)

    override suspend fun fetch(): List<ScanJob> = App.api.scanJobs()

    override fun bindRow(view: View, item: ScanJob, position: Int) {
        view.findViewById<TextView>(R.id.scan_name).text = item.fileName
        view.findViewById<TextView>(R.id.scan_meta).text =
            getString(R.string.scan_meta, item.jobId, item.fileSizeText, item.submittedAt)
        view.findViewById<ImageButton>(R.id.scan_delete).setOnClickListener { confirmDelete(item) }
    }

    private fun confirmDelete(job: ScanJob) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.scan_delete_title)
            .setMessage(getString(R.string.scan_delete_message, job.fileName, job.jobId))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> delete(job) }
            .show()
    }

    private fun delete(job: ScanJob) {
        runIo(
            block = { App.api.deleteScanJob(job.jobId) },
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
