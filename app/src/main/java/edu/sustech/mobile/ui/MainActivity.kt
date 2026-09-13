package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App

/** Hosts the five tabs. One fragment instance per tab, kept alive across switches. */
class MainActivity : AppCompatActivity() {

    private val tabs = LinkedHashMap<Int, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            show(tabFragment(item.itemId))
            true
        }
        if (savedInstanceState == null) show(tabFragment(R.id.nav_stations))
    }

    private fun tabFragment(id: Int): Fragment = tabs.getOrPut(id) {
        when (id) {
            R.id.nav_jobs -> JobsFragment()
            R.id.nav_scan -> ScanFragment()
            R.id.nav_usage -> UsageFragment()
            R.id.nav_account -> AccountFragment()
            else -> StationsFragment()
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            (supportFragmentManager.findFragmentById(R.id.container) as? Refreshable)?.refresh()
            true
        }
        R.id.action_logout -> {
            App.cookies.clear()
            Toast.makeText(this, R.string.logout_done, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
