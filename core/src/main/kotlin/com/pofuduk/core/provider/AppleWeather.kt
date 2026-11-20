package com.pofuduk.core.provider

import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.HourlyForecast
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.plusSeconds
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.secondsSince
import java.time.Instant
import kotlin.math.abs

/**
 * Maps Apple Weather condition codes to Pofuduk conditions. Accepts the WeatherKit framework raw values
 * ("mostlyCloudy") and the REST API codes ("MostlyCloudy"). Unknown → null.
 */
object AppleWeatherConditionMapper {
    fun condition(rawValue: String): WeatherCondition? = when (rawValue.replaceFirstChar { it.lowercaseChar() }) {
        "clear", "mostlyClear", "hot", "frigid" -> WeatherCondition.CLEAR
        "partlyCloudy" -> WeatherCondition.PARTLY_CLOUDY
        "mostlyCloudy", "cloudy" -> WeatherCondition.CLOUDY
        "foggy", "haze", "smoky" -> WeatherCondition.FOG
        "breezy", "windy", "blowingDust" -> WeatherCondition.WINDY
        "drizzle", "freezingDrizzle" -> WeatherCondition.DRIZZLE
        "rain", "sunShowers", "freezingRain" -> WeatherCondition.RAIN
        "heavyRain" -> WeatherCondition.HEAVY_RAIN
        "isolatedThunderstorms", "scatteredThunderstorms", "thunderstorms", "strongStorms",
        "tropicalStorm", "hurricane" -> WeatherCondition.THUNDERSTORM
        "snow", "heavySnow", "flurries", "sunFlurries", "blizzard", "blowingSnow" -> WeatherCondition.SNOW
        "sleet", "wintryMix", "hail" -> WeatherCondition.SLEET
        else -> null
    }
}

/** A second, independent reading of the weather right now (Apple Weather). Used only to correct "now". */
data class CurrentConditionsObservation(
    val condition: WeatherCondition,
    /** Millimetres per hour; null when the service doesn't report it. */
    val precipitationIntensityMmPerHour: Double? = null,
    val observedAt: Instant,
)

interface CurrentConditionsProvider {
    val source: WeatherDataSource
    suspend fun currentConditions(place: Place): CurrentConditionsObservation
}

/**
 * Merges a cross-check observation into the provider's current weather.
 * Precipitation reported by either source wins (a missed shower is worse than an extra umbrella); a dry observation
 * never removes the provider's precipitation, and only a heavier kind replaces a lighter one.
 */
object CurrentConditionsReconciler {
    /** Older observations are ignored (seconds). */
    const val MAX_OBSERVATION_AGE = 30.0 * 60

    fun reconcile(current: CurrentWeather, observation: CurrentConditionsObservation, now: Instant): CurrentWeather {
        if (abs(now.secondsSince(observation.observedAt)) > MAX_OBSERVATION_AGE) return current
        val observed = effectiveCondition(observation)
        if (!observed.isPrecipitation || severity(observed) <= severity(current.condition)) return current
        return current.copy(condition = observed)
    }

    /** A non-precipitation condition with a measurable intensity counts as drizzle/rain (same thresholds as Open-Meteo). */
    fun effectiveCondition(observation: CurrentConditionsObservation): WeatherCondition {
        val mm = observation.precipitationIntensityMmPerHour
        if (observation.condition.isPrecipitation || mm == null || mm < 0.1) return observation.condition
        return if (mm < 0.5) WeatherCondition.DRIZZLE else WeatherCondition.RAIN
    }

    internal fun severity(c: WeatherCondition): Int = when (c) {
        WeatherCondition.DRIZZLE -> 1
        WeatherCondition.RAIN, WeatherCondition.SNOW, WeatherCondition.SLEET -> 2
        WeatherCondition.HEAVY_RAIN -> 3
        WeatherCondition.THUNDERSTORM -> 4
        else -> 0
    }
}

/**
 * Builds a [WeatherSnapshot] from Apple Weather values (metric). Port of the iOS `AppleWeatherSnapshotBuilder`;
 * on Android the values come from the WeatherKit REST API through Pofuduk's proxy.
 */
object AppleWeatherSnapshotBuilder {
    data class Current(
        val time: Instant,
        val temperature: Double,
        val feelsLike: Double,
        val condition: String,
        /** 0...1 */
        val humidity: Double,
        val windKmh: Double,
        val gustKmh: Double?,
        val windDirectionDegrees: Double?,
        val uvIndex: Int,
        val isDaylight: Boolean,
        val precipitationIntensityMmPerHour: Double,
    )

    data class Hour(
        val time: Instant,
        val temperature: Double,
        val feelsLike: Double,
        val condition: String,
        /** 0...1 */
        val precipitationChance: Double,
        val precipitationMm: Double,
        val windKmh: Double,
        val gustKmh: Double?,
        val uvIndex: Int,
    )

    data class Day(
        val date: Instant,
        val low: Double,
        val high: Double,
        val condition: String,
        /** 0...1 */
        val precipitationChance: Double,
        val sunrise: Instant?,
        val sunset: Instant?,
    )

    /** Unknown Apple conditions fall back to cloudy (same as unknown codes from other providers). */
    fun condition(raw: String): WeatherCondition = AppleWeatherConditionMapper.condition(raw) ?: WeatherCondition.CLOUDY

    internal fun percent(chance: Double): Int = (chance.coerceIn(0.0, 1.0) * 100).roundedInt()

    /** Daily rain total, strongest wind and UV come from the hourly values of that local day (null beyond the hourly range). */
    fun snapshot(place: Place, current: Current, hours: List<Hour>, days: List<Day>, retrievedAt: Instant, lifetimeSeconds: Double): WeatherSnapshot {
        if (hours.isEmpty() && days.isEmpty()) throw WeatherException(WeatherError.NO_DATA)
        val zone = place.timeZone
        val hourly = hours.map { h ->
            HourlyForecast(
                time = h.time, temperature = h.temperature, feelsLike = h.feelsLike, condition = condition(h.condition),
                precipitationProbability = percent(h.precipitationChance), precipitationMm = maxOf(0.0, h.precipitationMm),
                windSpeedKmh = h.windKmh, windGustKmh = h.gustKmh, uvIndex = h.uvIndex.toDouble(),
            )
        }
        val hoursByDay = hourly.groupBy { it.time.localDate(zone) }
        val daily = days.map { d ->
            val day = d.date.localDate(zone)
            val dayHours = hoursByDay[day].orEmpty()
            DailyForecast(
                date = day.atStartOfDay(zone).toInstant(), minTemperature = d.low, maxTemperature = d.high,
                condition = condition(d.condition), precipitationProbability = percent(d.precipitationChance),
                precipitationSumMm = if (dayHours.isEmpty()) null else dayHours.sumOf { it.precipitationMm ?: 0.0 },
                maxWindKmh = dayHours.mapNotNull { it.windSpeedKmh }.maxOrNull(),
                uvIndexMax = dayHours.mapNotNull { it.uvIndex }.maxOrNull(),
                sunrise = d.sunrise, sunset = d.sunset,
            )
        }
        val observation = CurrentConditionsObservation(condition(current.condition), current.precipitationIntensityMmPerHour, current.time)
        val now = CurrentWeather(
            time = current.time, temperature = current.temperature, feelsLike = current.feelsLike,
            condition = CurrentConditionsReconciler.effectiveCondition(observation),
            humidity = (current.humidity.coerceIn(0.0, 1.0) * 100).roundedInt(),
            windSpeedKmh = current.windKmh, windGustKmh = current.gustKmh,
            windDirectionDegrees = current.windDirectionDegrees?.let { it.roundedInt() % 360 },
            uvIndex = current.uvIndex.toDouble(), isDay = current.isDaylight,
        )
        return WeatherSnapshot(
            place, now, hourly, daily, WeatherDataSource.APPLE_WEATHER,
            retrievedAt = retrievedAt, expiresAt = retrievedAt.plusSeconds(lifetimeSeconds),
        ).sorted()
    }
}
