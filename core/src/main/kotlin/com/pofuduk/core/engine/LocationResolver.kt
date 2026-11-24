package com.pofuduk.core.engine

import com.pofuduk.core.model.LocationError
import com.pofuduk.core.model.LocationException
import com.pofuduk.core.model.LocationIssue
import com.pofuduk.core.model.LocationMode
import com.pofuduk.core.model.LocationPermission
import com.pofuduk.core.model.LocationPreference
import com.pofuduk.core.model.LocationResolution
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import com.pofuduk.core.store.JsonStore
import com.pofuduk.core.store.KeyValueStore
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant

/**
 * Decides how to obtain the place for the forecast. Pure policy, independent of weather providers.
 * - Never requests location without an explanation first.
 * - Never falls back to IP silently: IP is used only when the user chose IP_APPROXIMATE.
 */
class LocationResolver {
    suspend fun resolve(
        preference: LocationPreference,
        permission: LocationPermission,
        gps: suspend () -> Place,
        ip: (suspend () -> Place)?,
        now: Instant = Instant.now(),
    ): LocationResolution = when (preference.mode) {
        LocationMode.MANUAL -> preference.manualPlace?.let { LocationResolution.Resolved(it) }
            ?: LocationResolution.NeedsUserChoice(LocationIssue.NO_MANUAL_PLACE)

        LocationMode.IP_APPROXIMATE -> if (ip == null) {
            LocationResolution.NeedsUserChoice(LocationIssue.IP_UNAVAILABLE)
        } else {
            try {
                LocationResolution.Resolved(ip().copy(source = LocationSource.IP_APPROXIMATE))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocationResolution.NeedsUserChoice(LocationIssue.IP_UNAVAILABLE)
            }
        }

        LocationMode.AUTOMATIC -> when (permission) {
            LocationPermission.NOT_DETERMINED -> LocationResolution.NeedsPermissionExplanation
            LocationPermission.DENIED, LocationPermission.RESTRICTED -> LocationResolution.NeedsUserChoice(LocationIssue.PERMISSION_DENIED)
            LocationPermission.SERVICES_DISABLED -> LocationResolution.NeedsUserChoice(LocationIssue.SERVICES_DISABLED)
            LocationPermission.AUTHORIZED -> try {
                LocationResolution.Resolved(gps().copy(source = LocationSource.GPS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LocationException) {
                if (e.error == LocationError.PERMISSION_DENIED) {
                    LocationResolution.NeedsUserChoice(LocationIssue.PERMISSION_DENIED)
                } else {
                    lastKnown(preference, now)
                }
            } catch (e: Exception) {
                lastKnown(preference, now)
            }
        }
    }

    /**
     * A device fix can fail now and then (indoors, cold start). Rather than "Konumunu belirleyemedim", the
     * last GPS place is used if it was confirmed within [LAST_KNOWN_MAX_AGE], labelled "Son bilinen konum".
     */
    private fun lastKnown(preference: LocationPreference, now: Instant): LocationResolution {
        val last = preference.lastPlace
        val at = preference.lastPlaceAt
        if (last != null && last.source == LocationSource.GPS && at != null) {
            val age = Duration.between(at, now)
            if (!age.isNegative && age <= LAST_KNOWN_MAX_AGE) {
                return LocationResolution.Resolved(last.copy(source = LocationSource.LAST_KNOWN))
            }
        }
        return LocationResolution.NeedsUserChoice(LocationIssue.GPS_UNAVAILABLE)
    }

    companion object {
        val LAST_KNOWN_MAX_AGE: Duration = Duration.ofHours(12)
    }
}

/** Persists the user's location preference. */
class LocationPreferenceStore(store: KeyValueStore) {
    private val json = JsonStore(store, "pofuduk.locationPreference", LocationPreference.serializer())
    fun load(): LocationPreference = json.load() ?: LocationPreference()
    fun save(preference: LocationPreference) = json.save(preference)
    fun clear() = json.clear()
}
