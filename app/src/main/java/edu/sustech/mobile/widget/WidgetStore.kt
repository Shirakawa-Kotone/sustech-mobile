package edu.sustech.mobile.widget

import android.content.Context

/**
 * The last snapshot a widget successfully showed, kept on disk.
 *
 * The in-memory [edu.sustech.mobile.core.Cache] cannot serve a widget: the
 * provider process is started for a broadcast and torn down again, so every
 * refresh would start from nothing and a failed fetch would leave the card
 * blank or staring at an error. This store is the widget's own memory — the
 * card shows the newest data it has, and says how old that is.
 */
object WidgetStore {

    private const val FILE = "widget_cache"
    private const val KEY_TITLE = "title"
    private const val KEY_LINES = "lines"
    private const val KEY_AT = "at"
    /** Unit separator: cannot appear in the copy these snapshots carry. */
    private const val SEP = "\u001F"

    private fun store(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, widgetId: Int, snapshot: WidgetSnapshot) {
        store(context).edit()
            .putString("$KEY_TITLE$widgetId", snapshot.title)
            .putString("$KEY_LINES$widgetId", snapshot.lines.joinToString(SEP))
            .putLong("$KEY_AT$widgetId", snapshot.at)
            .apply()
    }

    fun load(context: Context, widgetId: Int): WidgetSnapshot? {
        val prefs = store(context)
        val title = prefs.getString("$KEY_TITLE$widgetId", null) ?: return null
        val at = prefs.getLong("$KEY_AT$widgetId", 0L)
        if (at == 0L) return null
        val lines = prefs.getString("$KEY_LINES$widgetId", "")
            .orEmpty()
            .split(SEP)
            .filter { it.isNotEmpty() }
        return WidgetSnapshot(title, lines, at)
    }

    fun clear(context: Context, widgetId: Int) {
        store(context).edit()
            .remove("$KEY_TITLE$widgetId")
            .remove("$KEY_LINES$widgetId")
            .remove("$KEY_AT$widgetId")
            .apply()
    }
}
