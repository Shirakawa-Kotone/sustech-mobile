package edu.sustech.mobile.transit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Where the phone is, from the platform's own location service.
 *
 * Deliberately not Play Services' fused provider: the app ships to a campus
 * whose phones may have no Google stack at all, and "nearest stop" only needs
 * a coarse fix — a few hundred metres of error changes nothing when the
 * question is which building's stop to walk to.
 *
 * Everything here degrades to null, and every caller has to handle that: with
 * no permission the screen still lists stops, it just cannot rank them.
 */
object LocationProbe {

    val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun granted(context: Context): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The freshest fix the platform already has, or null.
     *
     * `getLastKnownLocation` is the right call for a screen that opens and
     * should be useful immediately: waiting for a live GPS fix would leave the
     * list unranked for ten seconds, and the user is standing still.
     */
    fun lastKnown(context: Context): Location? {
        if (!granted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
        }.getOrNull()
    }
}
