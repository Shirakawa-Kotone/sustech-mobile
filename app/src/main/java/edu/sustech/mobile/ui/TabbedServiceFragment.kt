package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import edu.sustech.mobile.R

/** One inner page of a service: a tab title and the fragment behind it. */
data class ServicePage(@StringRes val title: Int, val factory: () -> Fragment)

/**
 * A service whose own navigation is a tab strip.
 *
 * Used by both print and TIS: the shell owns services, the service owns its
 * pages, and no service ever touches the bottom bar.
 *
 * [R.id.tabs_status] / [R.id.tabs_sign_in] are optional — a service that needs
 * a sign-in banner shows them, one that does not never sees them.
 */
abstract class TabbedServiceFragment(@LayoutRes layoutRes: Int) : Fragment(layoutRes), Refreshable {

    protected abstract fun pages(): List<ServicePage>

    private val cache = LinkedHashMap<Int, Fragment>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabLayout = view.findViewById<TabLayout>(R.id.tabs)
        for (page in pages()) {
            tabLayout.addTab(tabLayout.newTab().setText(page.title))
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = show(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        show(0)
        onServiceViewReady(view)
    }

    /** Hook for extra chrome (sign-in banners) before the first load. */
    protected open fun onServiceViewReady(view: View) = Unit

    protected fun show(position: Int) {
        val page = pages().getOrNull(position) ?: return
        val fragment = cache.getOrPut(position) { page.factory() }
        parentFragmentManager.beginTransaction()
            .replace(R.id.tabs_container, fragment)
            .commitAllowingStateLoss()
    }

    /** Rebuilds the visible page from scratch (used after signing in). */
    protected fun reloadCurrent() {
        val position = (view?.findViewById<TabLayout>(R.id.tabs))?.selectedTabPosition ?: 0
        val page = pages().getOrNull(position) ?: return
        cache[position] = page.factory()
        show(position)
    }

    protected fun banner(message: String?, signIn: (() -> Unit)?) {
        val root = view ?: return
        root.findViewById<TextView>(R.id.tabs_status)?.apply {
            visibility = if (message == null) View.GONE else View.VISIBLE
            text = message.orEmpty()
        }
        root.findViewById<MaterialButton>(R.id.tabs_sign_in)?.apply {
            visibility = if (signIn == null) View.GONE else View.VISIBLE
            setOnClickListener { signIn?.invoke() }
        }
    }

    override fun refresh() {
        (parentFragmentManager.findFragmentById(R.id.tabs_container) as? Refreshable)?.refresh()
    }
}
