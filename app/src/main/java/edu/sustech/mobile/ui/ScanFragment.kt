package edu.sustech.mobile.ui

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.pms.ScanJob

/** 扫描文档 — scans waiting for pickup, with per-document delete. */
class ScanFragment : PmsListFragment<ScanJob>(R.layout.fragment_list) {

    override fun rowLayout() = R.layout.item_scan_job

    override fun emptyText() = getString(R.string.scan_empty)

    override suspend fun fetch(): List<ScanJob> = App.api.scanJobs()

    override fun bindRow(view: View, item: ScanJob, position: Int) {
        view.findViewById<TextView>(R.id.scan_name).text = item.fileName
        view.findViewById<TextView>(R.id.scan_meta).text =
            "ID ${item.jobId} · ${getString(R.string.scan_size)} ${item.fileSizeText} · ${item.submittedAt}"
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
