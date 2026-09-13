package edu.sustech.mobile.ui.pms

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.pms.ServerGroup
import edu.sustech.mobile.pms.Station
import edu.sustech.mobile.pms.StationState
import edu.sustech.mobile.ui.ListFragment

/**
 * Stations — every printer / copier / scanner, with the server-group filter the
 * website's dropdown offers and the same idle/busy/fault reading.
 */
class StationsFragment : ListFragment<Station>(R.layout.fragment_stations) {

    private var groups: List<ServerGroup> = emptyList()
    private var stations: List<Station> = emptyList()
    private var selectedGroup = 0

    override fun rowLayout() = R.layout.item_station

    override suspend fun fetch(): List<Station> {
        groups = App.api.serverGroups()
        stations = App.api.stations()
        return visible()
    }

    override fun onReady(view: View) {
        view.findViewById<Spinner>(R.id.group_spinner).onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    selected: View?,
                    position: Int,
                    id: Long,
                ) {
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
        view?.findViewById<TextView>(R.id.stations_count)?.text =
            getString(R.string.stations_count, visible().size)
    }

    override fun bindRow(view: View, item: Station, position: Int) {
        view.findViewById<TextView>(R.id.station_name).text = item.name

        val state = view.findViewById<TextView>(R.id.station_state)
        val (label, colour) = when (item.state) {
            StationState.IDLE -> R.string.station_state_idle to R.color.ok
            StationState.BUSY -> R.string.station_state_busy to R.color.warn
            StationState.FAULT -> R.string.station_state_fault to R.color.bad
            StationState.NOT_OPEN -> R.string.station_state_not_open to R.color.ink_muted
        }
        state.setText(label)
        state.setTextColor(requireContext().getColor(colour))

        val functions = ArrayList<String>()
        if (item.canPrint) functions.add(getString(R.string.station_fn_print))
        if (item.canCopy) functions.add(getString(R.string.station_fn_copy))
        if (item.canScan) functions.add(getString(R.string.station_fn_scan))
        if (item.canColor) functions.add(getString(R.string.station_fn_color))
        view.findViewById<TextView>(R.id.station_functions).text = functions.joinToString(", ").ifEmpty { "—" }

        view.findViewById<TextView>(R.id.station_meta).text = listOf(
            getString(R.string.station_paper, item.papers.joinToString(", ").ifEmpty { "—" }),
            getString(R.string.station_device, item.devSn),
        ).joinToString(" · ")
    }
}
