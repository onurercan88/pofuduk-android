package com.pofuduk.app.location

import com.pofuduk.core.engine.LocationPreferenceStore
import com.pofuduk.core.engine.LocationResolver
import com.pofuduk.core.model.LocationMode
import com.pofuduk.core.model.LocationPreference
import com.pofuduk.core.model.LocationResolution
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import com.pofuduk.core.store.KeyValueStore

/** App-facing location API. Policy lives in [LocationResolver] (core). No IP location on Android. */
class LocationService(private val device: DeviceLocationProvider, private val kv: KeyValueStore) {
    private val store = LocationPreferenceStore(kv)
    private val resolver = LocationResolver()

    var preference: LocationPreference = store.load()
        private set

    private var askedBefore: Boolean
        get() = kv.getString(ASKED_KEY) == "1"
        set(value) = if (value) kv.putString(ASKED_KEY, "1") else kv.remove(ASKED_KEY)

    val permission get() = device.permission(askedBefore)

    suspend fun resolve(): LocationResolution {
        val result = resolver.resolve(preference, permission, gps = { device.currentPlace() }, ip = null)
        if (result is LocationResolution.Resolved) {
            preference = preference.copy(lastPlace = LocationPreference.coarsened(result.place))
            store.save(preference)
        }
        return result
    }

    /** The system prompt was answered (granted or not). Switches back to device location. */
    fun permissionAnswered() {
        askedBefore = true
        setMode(LocationMode.AUTOMATIC)
    }

    fun setMode(mode: LocationMode) {
        preference = preference.copy(mode = mode)
        store.save(preference)
    }

    fun selectManual(place: Place) {
        preference = LocationPreference(LocationMode.MANUAL, place.copy(source = LocationSource.MANUAL), preference.lastPlace)
        store.save(preference)
    }

    suspend fun search(query: String): List<Place> = device.search(query)

    /** Forget the saved location choice and last place. */
    fun reset() {
        store.clear()
        askedBefore = false
        preference = LocationPreference()
    }

    private companion object {
        const val ASKED_KEY = "pofuduk.locationPermissionAsked"
    }
}
