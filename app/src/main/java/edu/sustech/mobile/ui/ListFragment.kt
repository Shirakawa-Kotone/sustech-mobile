package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import edu.sustech.mobile.R
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo

/** A screen that reloads its own data when the toolbar refresh button is hit. */
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

    override fun onBindViewHolder(holder: Holder, position: Int) =
        onBind(holder.itemView, rows[position], position)

    override fun getItemCount(): Int = rows.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view)
}

/**
 * Shared plumbing every list screen uses: swipe-to-refresh, an empty/loading
 * label, uniform error text, and a single [fetch] the subclass implements.
 *
 * Service-agnostic on purpose — the print tabs and the TIS tabs both ride on
 * this, so behavior (error copy, refresh, empty state) never diverges.
 */
abstract class ListFragment<T>(@LayoutRes layoutRes: Int) : Fragment(layoutRes), Refreshable {

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        swipe = view.findViewById(R.id.swipe)
        adapter = SimpleAdapter(rowLayout()) { row, item, position -> bindRow(row, item, position) }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        swipe.setOnRefreshListener { load() }
        onReady(view)
        load()
    }

    /** Hook for subclasses that need extra view wiring before the first load. */
    protected open fun onReady(view: View) = Unit

    override fun refresh() = load()

    protected fun load() {
        swipe.isRefreshing = true
        runIo(
            block = { fetch() },
            onOk = { rows ->
                swipe.isRefreshing = false
                adapter.submit(rows)
                showEmpty(rows.isEmpty(), emptyText())
                onLoaded(rows)
            },
            onErr = { error ->
                swipe.isRefreshing = false
                adapter.submit(emptyList())
                showEmpty(true, errorText(error))
            },
        )
    }

    protected fun showEmpty(visible: Boolean, text: String) {
        empty.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) (empty as? TextView)?.text = text
    }

    protected open fun onLoaded(rows: List<T>) = Unit

    /** Subclasses can replace the generic error copy (say, "not signed in"). */
    protected open fun errorText(error: Throwable): String = error.friendly(requireContext())

    protected fun showError(error: Throwable) {
        Toast.makeText(requireContext(), error.friendly(requireContext()), Toast.LENGTH_LONG).show()
    }
}
