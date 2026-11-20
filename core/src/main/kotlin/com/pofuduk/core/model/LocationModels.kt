package com.pofuduk.core.model

import com.pofuduk.core.util.roundedHalfAway
import kotlinx.serialization.Serializable

/** How the user wants their location determined. IP is only ever used when explicitly chosen. */
@Serializable
enum class LocationMode {
    /** Device location (while-in-use permission only). */
    AUTOMATIC,
    /** Approximate, IP-based. User opt-in; always labelled approximate. */
    IP_APPROXIMATE,
    /** A city/district the user picked. */
    MANUAL,
}

enum class LocationPermission { NOT_DETERMINED, AUTHORIZED, DENIED, RESTRICTED, SERVICES_DISABLED }

/** Why a location could not be resolved automatically; the UI offers alternatives. */
enum class LocationIssue {
    PERMISSION_DENIED, SERVICES_DISABLED, GPS_UNAVAILABLE, IP_UNAVAILABLE, NO_MANUAL_PLACE;

    val turkishExplanation: String
        get() = when (this) {
            PERMISSION_DENIED -> "Konum izni verilmedi. Sorun değil! İstersen bir şehir seçebilir ya da yaklaşık konumunu kullanabilirsin."
            SERVICES_DISABLED -> "Cihazında konum servisleri kapalı. Bir şehir seçerek devam edebilirsin."
            GPS_UNAVAILABLE -> "Şu an konumunu belirleyemedim. Tekrar deneyebilir ya da bir şehir seçebilirsin."
            IP_UNAVAILABLE -> "Yaklaşık konumunu bulamadım. Bir şehir seçerek devam edebilirsin."
            NO_MANUAL_PLACE -> "Havayı anlatabilmem için bir şehir seçmen yeterli."
        }
}

sealed interface LocationResolution {
    data class Resolved(val place: Place) : LocationResolution
    /** Show Pofuduk's explanation *before* the system permission prompt. */
    data object NeedsPermissionExplanation : LocationResolution
    data class NeedsUserChoice(val issue: LocationIssue) : LocationResolution
}

enum class LocationError { PERMISSION_DENIED, UNAVAILABLE, NOT_FOUND }

class LocationException(val error: LocationError) : Exception(error.name)

/** Persisted user choice. Contains only what the user selected; GPS fixes are not stored. */
@Serializable
data class LocationPreference(
    val mode: LocationMode = LocationMode.AUTOMATIC,
    val manualPlace: Place? = null,
    /**
     * Last place used for a forecast, coarsened to ~1 km (2 decimals). Used only for background
     * notification refresh, which never asks for location.
     */
    val lastPlace: Place? = null,
) {
    /** Place for background work: the user's manual choice, else the last (coarsened) place. */
    val backgroundPlace: Place? get() = if (mode == LocationMode.MANUAL) manualPlace else lastPlace

    companion object {
        fun coarsened(place: Place): Place = place.copy(
            coordinate = Coordinate(
                latitude = (place.coordinate.latitude * 100).roundedHalfAway() / 100,
                longitude = (place.coordinate.longitude * 100).roundedHalfAway() / 100,
            ),
        )
    }
}
