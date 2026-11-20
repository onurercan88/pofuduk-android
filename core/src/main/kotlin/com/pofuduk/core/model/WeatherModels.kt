@file:UseSerializers(InstantSerializer::class)

package com.pofuduk.core.model

import com.pofuduk.core.util.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.ZoneId

@Serializable
data class Coordinate(val latitude: Double, val longitude: Double)

/** How a place was determined. IP-based locations are always approximate. */
@Serializable
enum class LocationSource { GPS, IP_APPROXIMATE, MANUAL, DEMO }

@Serializable
data class Place(
    val name: String,
    val district: String? = null,
    val province: String? = null,
    val coordinate: Coordinate,
    val timeZoneIdentifier: String,
    val source: LocationSource,
) {
    val timeZone: ZoneId
        get() = runCatching { ZoneId.of(timeZoneIdentifier) }.getOrDefault(ZoneId.systemDefault())

    val displayName: String
        get() = if (district != null && district != name) "$district, $name" else name
}

/** Provider-independent weather condition. */
@Serializable
enum class WeatherCondition {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG,
    DRIZZLE, RAIN, HEAVY_RAIN, THUNDERSTORM,
    SNOW, SLEET,
    WINDY;

    val isPrecipitation: Boolean
        get() = when (this) {
            DRIZZLE, RAIN, HEAVY_RAIN, THUNDERSTORM, SNOW, SLEET -> true
            else -> false
        }
}

@Serializable
data class CurrentWeather(
    val time: Instant,
    val temperature: Double,
    val feelsLike: Double,
    val condition: WeatherCondition,
    val humidity: Int,
    val windSpeedKmh: Double,
    val windGustKmh: Double? = null,
    val windDirectionDegrees: Int? = null,
    val uvIndex: Double? = null,
    val isDay: Boolean = true,
)

@Serializable
data class HourlyForecast(
    val time: Instant,
    val temperature: Double,
    val feelsLike: Double? = null,
    val condition: WeatherCondition,
    /** 0...100 */
    val precipitationProbability: Int? = null,
    /** Millimetres expected within this hour. */
    val precipitationMm: Double? = null,
    val windSpeedKmh: Double? = null,
    val windGustKmh: Double? = null,
    val uvIndex: Double? = null,
)

@Serializable
data class DailyForecast(
    /** Start of the day in the place's time zone. */
    val date: Instant,
    val minTemperature: Double,
    val maxTemperature: Double,
    val condition: WeatherCondition,
    val precipitationProbability: Int? = null,
    val precipitationSumMm: Double? = null,
    val maxWindKmh: Double? = null,
    val uvIndexMax: Double? = null,
    val sunrise: Instant? = null,
    val sunset: Instant? = null,
)

@Serializable
enum class WeatherDataSource {
    WEATHER_API, OPEN_METEO,
    /** Apple Weather (WeatherKit, later via a backend proxy). */
    APPLE_WEATHER,
    /** Built-in sample data. Must always be labelled as such in the UI. */
    DEMO;

    val attribution: String
        get() = when (this) {
            WEATHER_API -> "WeatherAPI.com"
            OPEN_METEO -> "Open-Meteo.com"
            APPLE_WEATHER -> "Apple Weather"
            DEMO -> "Örnek veri"
        }
}

/**
 * A forecast plus provenance. Never present an expired snapshot as live.
 * `hourly` and `daily` are kept sorted by time ([sorted] is applied by every factory in core).
 */
@Serializable
data class WeatherSnapshot(
    val place: Place,
    val current: CurrentWeather,
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>,
    val source: WeatherDataSource,
    val retrievedAt: Instant,
    val expiresAt: Instant,
    /** Set when `current` was cross-checked against a second source (needs its own attribution). */
    val crossCheckSource: WeatherDataSource? = null,
) {
    fun isStale(at: Instant): Boolean = !at.isBefore(expiresAt)

    fun sorted(): WeatherSnapshot = copy(hourly = hourly.sortedBy { it.time }, daily = daily.sortedBy { it.date })
}
