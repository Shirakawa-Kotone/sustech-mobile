package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.service.ServiceModule
import edu.sustech.mobile.service.Services

/**
 * The whole SUSTech service catalog.
 *
 * Implemented services open; the rest are listed as planned so the app states
 * its scope honestly instead of pretending to be a print client.
 */
class ServicesFragment : Fragment(R.layout.fragment_services), Refreshable {

    private lateinit var adapter: SimpleAdapter<ServiceModule>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = SimpleAdapter(R.layout.item_service) { row, module, _ -> bind(row, module) }

        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ServicesFragment.adapter
        }
        adapter.submit(Services.all)
    }

    private fun bind(row: View, module: ServiceModule) {
        row.findViewById<ImageView>(R.id.service_icon).setImageResource(module.icon)
        row.findViewById<TextView>(R.id.service_title).text = getString(module.title)
        row.findViewById<TextView>(R.id.service_summary).text = getString(module.summary)
        row.findViewById<TextView>(R.id.service_status).apply {
            text = getString(if (module.available) R.string.services_available else R.string.services_not_implemented)
            setTextColor(requireContext().getColor(if (module.available) R.color.ok else R.color.ink_muted))
        }
        row.setOnClickListener {
            if (module.available) {
                startActivity(ServiceActivity.intent(requireContext(), module))
            } else {
                App.toast(getString(R.string.not_implemented_body, getString(module.title)))
            }
        }
    }

    override fun refresh() = adapter.submit(Services.all)
}
