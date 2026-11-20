package com.pofuduk.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.pofuduk.core.data.TurkishProvinces
import com.pofuduk.core.model.Coordinate
import com.pofuduk.core.model.LocationError
import com.pofuduk.core.model.LocationException
import com.pofuduk.core.model.LocationPermission
import com.pofuduk.core.model.LocationPreference
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume

/**
 * One-shot device location: coarse permission only, foreground only, no Google Play Services dependency.
 * The fix is coarsened to ~1 km (2 decimals) *before* it is used anywhere (geocoding, forecast, storage).
 */
class DeviceLocationProvider(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val turkish = Locale.forLanguageTag("tr-TR")

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun servicesEnabled(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.isLocationEnabled
    } else {
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /** [askedBefore]: the app already showed the system prompt once (Android can't tell "never asked" apart). */
    fun permission(askedBefore: Boolean): LocationPermission = when {
        hasPermission() -> if (servicesEnabled()) LocationPermission.AUTHORIZED else LocationPermission.SERVICES_DISABLED
        askedBefore -> LocationPermission.DENIED
        else -> LocationPermission.NOT_DETERMINED
    }

    suspend fun currentPlace(): Place {
        if (!hasPermission()) throw LocationException(LocationError.PERMISSION_DENIED)
        if (!servicesEnabled()) throw LocationException(LocationError.UNAVAILABLE)
        // Weather needs ~1 km: a recent fix is enough; otherwise ask each provider briefly, and as a last resort
        // accept an older fix (a forecast for where the user was 2 h ago beats no forecast).
        val location = lastKnown(maxAgeMinutes = 15) ?: freshLocation() ?: lastKnown(maxAgeMinutes = 120)
            ?: throw LocationException(LocationError.UNAVAILABLE)
        val coordinate = Coordinate(location.latitude, location.longitude)
        return LocationPreference.coarsened(place(coordinate))
    }

    private val providers: List<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission") // checked in currentPlace(); SecurityException handled per provider
    private fun lastKnown(maxAgeMinutes: Long): Location? {
        val maxAgeNanos = maxAgeMinutes * 60 * 1_000_000_000
        return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < maxAgeNanos }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    /**
     * Asks all enabled providers at once and takes the first fix, within one overall budget — a silent provider
     * (e.g. network without a connection) can no longer delay the others.
     */
    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(): Location? = withTimeoutOrNull(FRESH_BUDGET_MS) {
        channelFlow {
            for (provider in providers) {
                launch {
                    val location = try {
                        single(provider)
                    } catch (e: SecurityException) {
                        null // e.g. GPS with coarse-only permission before Android 12
                    }
                    if (location != null) send(location)
                }
            }
        }.firstOrNull()
    }

    private companion object {
        const val FRESH_BUDGET_MS = 10_000L
    }

    @SuppressLint("MissingPermission")
    private suspend fun single(provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderDisabled(provider: String) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    /** Province/district for a (coarsened) coordinate; offline nearest-province fallback. */
    private suspend fun place(coordinate: Coordinate): Place {
        val rounded = LocationPreference.coarsened(placeAt(coordinate, null, null, null)).coordinate
        val address = reverseGeocode(rounded)
        val province = address?.adminArea?.let(::cleanProvince)
        val district = address?.subAdminArea ?: address?.locality
        val fallback = if (address == null) TurkishProvinces.nearest(rounded)?.name else null
        val name = province ?: fallback ?: district ?: "Bulunduğun konum"
        return placeAt(rounded, name, district?.takeIf { it != name }, province ?: fallback)
    }

    private fun placeAt(c: Coordinate, name: String?, district: String?, province: String?) = Place(
        name = name ?: "Bulunduğun konum", district = district, province = province, coordinate = c,
        timeZoneIdentifier = ZoneId.systemDefault().id, source = LocationSource.GPS,
    )

    /** Geocoders sometimes return "İstanbul İli"/"Istanbul Province"; match against the offline list when possible. */
    private fun cleanProvince(raw: String): String {
        val trimmed = raw.removeSuffix(" İli").removeSuffix(" Province").trim()
        return TurkishProvinces.all.firstOrNull { TurkishProvinces.normalize(it.name) == TurkishProvinces.normalize(trimmed) }?.name
            ?: trimmed
    }

    private suspend fun reverseGeocode(c: Coordinate): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, turkish)
        return withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(c.latitude, c.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(c.latitude, c.longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
    }

    /** District/city search limited to Turkey (Android Geocoder; the query text goes to the platform geocoder). */
    suspend fun search(query: String): List<Place> {
        if (!Geocoder.isPresent() || query.trim().length < 2) return emptyList()
        val geocoder = Geocoder(context, turkish)
        val (s, w, n, e) = listOf(35.8, 25.6, 42.2, 44.9)
        val addresses: List<Address> = withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(query, 8, s, w, n, e, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocationName(query, 8, s, w, n, e).orEmpty() }.getOrDefault(emptyList())
                }
            }
        } ?: emptyList()
        return addresses
            .filter { it.countryCode == "TR" && it.hasLatitude() && it.hasLongitude() }
            .map { a ->
                val province = a.adminArea?.let(::cleanProvince)
                val district = a.subAdminArea ?: a.locality ?: a.featureName
                Place(
                    name = province ?: district ?: query, district = district?.takeIf { it != province }, province = province,
                    coordinate = LocationPreference.coarsened(placeAt(Coordinate(a.latitude, a.longitude), null, null, null)).coordinate,
                    timeZoneIdentifier = "Europe/Istanbul", source = LocationSource.MANUAL,
                )
            }
            .distinctBy { it.displayName }
    }
}
