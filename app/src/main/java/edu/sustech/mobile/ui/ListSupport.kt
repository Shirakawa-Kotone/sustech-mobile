package edu.sustech.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import edu.sustech.mobile.R
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo

/** A tab that reloads its own data when the toolbar refresh button is hit. */
interface Refreshable {
    fun refresh()
}

/** Minimal RecyclerView adapter: one row layout, one bind lambda. */
class SimpleAdapter<T>(
    @LayoutRes private val layoutId: Int,
    private val onBind: (View, T, Int) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter<T>.Holder>() {

    private val rows = ArrayList<T>()

    fun submit(items: List<T>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = onBind(holder.itemView, rows[position], position)

    override fun getItemCount(): Int = rows.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view)
}

/**
 * Shared plumbing for the five tabs: swipe-to-refresh, an empty/loading
 * label, uniform error text, and a single [fetch] the subclass implements.
 */
abstract class PmsListFragment<T>(@LayoutRes layoutRes: Int) : Fragment(layoutRes), Refreshable {

    protected lateinit var list: RecyclerView
    protected lateinit var empty: View
    protected lateinit var swipe: SwipeRefreshLayout
    protected lateinit var adapter: SimpleAdapter<T>

    @LayoutRes
    protected abstract fun rowLayout(): Int

    protected abstract fun bindRow(view: View, item: T, position: Int)

    protected abstract suspend fun fetch(): List<T>

    /** Text shown when [fetch] returns nothing. */
    protected open fun emptyText(): String = getString(R.string.empty_none)

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        swipe = view.findViewById(R.id.swipe)
        adapter = SimpleAdapter(rowLayout()) { row, item, position -> bindRow(row, item, position) }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        swipe.setOnRefreshListener { load() }
        load()
    }

    override fun refresh() = load()

    protected fun load() {
        swipe.isRefreshing = true
        runIo(
            block = { fetch() },
            onOk = { rows ->
                swipe.isRefreshing = false
                adapter.submit(rows)
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                if (rows.isEmpty()) (empty as? android.widget.TextView)?.text = emptyText()
                onLoaded(rows)
            },
            onErr = { error ->
                swipe.isRefreshing = false
                adapter.submit(emptyList())
                empty.visibility = View.VISIBLE
                (empty as? android.widget.TextView)?.text = error.friendly(requireContext())
            },
        )
    }

    protected open fun onLoaded(rows: List<T>) = Unit

    protected fun showError(error: Throwable) {
        android.widget.Toast.makeText(requireContext(), error.friendly(requireContext()), android.widget.Toast.LENGTH_LONG).show()
    }
}
