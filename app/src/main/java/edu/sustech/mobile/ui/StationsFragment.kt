package edu.sustech.mobile.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.pms.ServerGroup
import edu.sustech.mobile.pms.Station

/**
 * 打印点 — every printer / copier / scanner on campus, with the same
 * server-group filter the website's dropdown offers.
 */
class StationsFragment : PmsListFragment<Station>(R.layout.fragment_stations) {

    private var groups: List<ServerGroup> = emptyList()
    private var stations: List<Station> = emptyList()
    private var selectedGroup = 0

    override fun rowLayout() = R.layout.item_station

    override fun emptyText() = getString(R.string.empty_none)

    override suspend fun fetch(): List<Station> {
        groups = App.api.serverGroups()
        stations = App.api.stations()
        return visible()
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val spinner = view.findViewById<Spinner>(R.id.group_spinner)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedGroup = if (position == 0) 0 else groups.getOrNull(position - 1)?.sn ?: 0
                adapter.submit(visible())
                updateCount()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    override fun onLoaded(rows: List<Station>) {
        val labels = ArrayList<String>()
        labels.add(getString(R.string.stations_all_groups))
        labels.addAll(groups.map { it.name })
        val spinner = view?.findViewById<Spinner>(R.id.group_spinner) ?: return
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        updateCount()
    }

    private fun visible(): List<Station> =
        if (selectedGroup == 0) stations else stations.filter { it.serverGroup == selectedGroup }

    private fun updateCount() {
        view?.findViewById<TextView>(R.id.stations_count)?.text = getString(R.string.stations_count, visible().size)
    }

    override fun bindRow(view: View, item: Station, position: Int) {
        view.findViewById<TextView>(R.id.station_name).text = item.name
        val state = view.findViewById<TextView>(R.id.station_state)
        state.text = item.stateText
        state.setTextColor(
            when {
                item.isIdle -> requireContext().getColor(R.color.ok)
                item.isBusy -> requireContext().getColor(R.color.warn)
                else -> requireContext().getColor(R.color.bad)
            }
        )
        view.findViewById<TextView>(R.id.station_functions).text = item.functionsText.ifEmpty { "—" }
        view.findViewById<TextView>(R.id.station_meta).text =
            getString(R.string.station_paper) + ": " + (item.papers.joinToString(", ").ifEmpty { "—" }) +
            " · " + getString(R.string.station_device) + ": " + item.devSn
    }
}
