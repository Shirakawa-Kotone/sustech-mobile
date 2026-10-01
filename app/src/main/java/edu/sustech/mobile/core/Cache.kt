package edu.sustech.mobile.core

/**
 * Tiny in-memory TTL cache.
 *
 * Both services are slow — a campus round trip costs hundreds of milliseconds
 * and the print box is slower — and every screen used to refetch whenever it was
 * opened or resumed. Two taps in a row cannot change a timetable, so reads are
 * remembered here. Anything the user does on purpose (pull to refresh, the
 * toolbar button) invalidates first, so the gesture still means "fetch now".
 *
 * Loads are single-flight per key. Two screens asking for the timetable in the
 * same breath — the sign-in verification and the Today tab, on a cold start —
 * must not both walk the CAS re-login path: the second one would find the
 * re-login already running and give up, which surfaced as a spurious "session
 * expired" on a session that was fine. The second caller now waits for the
 * first and gets its answer.
 *
 * Failures are remembered too, but only for a few seconds: four screens hitting
 * a dead network in the same breath should not each wait for their own timeout,
 * yet a retry a moment later must still be able to succeed.
 */
object Cache {

    private class Entry(val value: Any?, val expiresAt: Long, val failure: ApiException?)

    private const val FAILURE_TTL_MS = 5_000L

    private val entries = HashMap<String, Entry>()

    /** One monitor per key, so a slow key cannot block the others. */
    private val locks = HashMap<String, Any>()

    /** Everything, e.g. when the account changes. */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** Every entry whose key starts with [prefix]. */
    fun invalidate(prefix: String) {
        synchronized(entries) {
            entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) }
        }
    }

    /** [load]'s value for [key], remembered for [ttlMs] unless [force]. */
    fun <T> get(key: String, ttlMs: Long, force: Boolean = false, load: () -> T): T {
        if (!force) lookup<T>(key)?.let { return it }
        val lock = synchronized(entries) { locks.getOrPut(key) { Any() } }
        synchronized(lock) {
            // Another caller may have filled the entry while we waited.
            if (!force) lookup<T>(key)?.let { return it }
            val now = System.currentTimeMillis()
            return try {
                val value = load()
                synchronized(entries) { entries[key] = Entry(value, now + ttlMs, null) }
                value
            } catch (e: ApiException) {
                synchronized(entries) { entries[key] = Entry(null, now + FAILURE_TTL_MS, e) }
                throw e
            }
        }
    }

    /** The remembered value for [key], or null when there is nothing live. */
    private fun <T> lookup(key: String): T? {
        val now = System.currentTimeMillis()
        val remembered = synchronized(entries) { entries[key] } ?: return null
        if (remembered.expiresAt <= now) return null
        @Suppress("UNCHECKED_CAST")
        (remembered.value as? T)?.let { return it }
        remembered.failure?.let { throw it }
        return null
    }

    /** Hours: a timetable does not change inside a session. */
    const val TTL_SEMESTER = 10 * 60_000L
    const val TTL_SLOTS = 30 * 60_000L

    /** Minutes: server state a user might reasonably want re-read. */
    const val TTL_LIST = 2 * 60_000L
    const val TTL_LIVE = 20_000L
    const val TTL_STATIONS = 60_000L
}
