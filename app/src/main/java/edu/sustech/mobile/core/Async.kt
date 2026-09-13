package edu.sustech.mobile.core

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import edu.sustech.mobile.R
import edu.sustech.mobile.pms.PmsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [block] on the IO dispatcher and hands the result back on the main
 * thread, routing every failure through [onErr].
 *
 * Every screen uses this instead of hand-rolling thread + handler plumbing,
 * so error presentation is uniform across tabs.
 */
fun <T> LifecycleOwner.runIo(
    block: suspend () -> T,
    onOk: (T) -> Unit,
    onErr: (Throwable) -> Unit = {},
) {
    lifecycleScope.launch {
        try {
            val result = withContext(Dispatchers.IO) { block() }
            onOk(result)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onErr(t)
        }
    }
}

/** Human-readable, translated message for any failure the API can raise. */
fun Throwable.friendly(context: Context): String = when (this) {
    is PmsException -> if (offCampus) context.getString(R.string.empty_off_campus) else message.orEmpty()
    else -> context.getString(R.string.error_network, message ?: this::class.java.simpleName)
}
