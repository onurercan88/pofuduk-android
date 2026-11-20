package com.pofuduk.core.mock

import com.pofuduk.core.model.Coordinate
import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.HourlyForecast
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.localDate
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos

/**
 * Deterministic sample data for previews, tests and the clearly labelled demo mode.
 * Never use as production weather information.
 */
object MockWeatherData {
    enum class Scenario { SUNNY, RAINY_AFTERNOON, STORMY, SNOWY, CLOUDY, WINDY }

    val istanbul = Place(
        name = "İstanbul", district = "Kadıköy", province = "İstanbul",
        coordinate = Coordinate(40.99, 29.03), timeZoneIdentifier = "Europe/Istanbul", source = LocationSource.DEMO,
    )

    /** Builds a snapshot whose hourly data starts at local midnight of [now] and spans 48 hours. */
    fun snapshot(scenario: Scenario, now: Instant, place: Place = istanbul): WeatherSnapshot {
        val zone = place.timeZone
        val dayStart = now.localDate(zone).atStartOfDay(zone).toInstant()

        val hourly = (0 until 48).map { i -> hour(scenario, i % 24, dayStart.plusSeconds(i * 3600L)) }
        val currentHour = now.hour(zone)
        val h = hourly[currentHour]
        val current = CurrentWeather(
            time = now, temperature = h.temperature, feelsLike = h.feelsLike ?: h.temperature,
            condition = h.condition, humidity = if (scenario == Scenario.SUNNY) 45 else 78,
            windSpeedKmh = h.windSpeedKmh ?: 10.0, windGustKmh = h.windGustKmh,
            windDirectionDegrees = 200, uvIndex = h.uvIndex, isDay = currentHour in 6 until 19,
        )
        val daily = (0 until 5).map { d ->
            val date = now.localDate(zone).plusDays(d.toLong()).atStartOfDay(zone).toInstant()
            val temps = (0 until 24).map { hour(scenario, it, date).temperature + (d % 2) }
            val base = hour(scenario, 15, date)
            DailyForecast(
                date = date, minTemperature = temps.min(), maxTemperature = temps.max(),
                condition = base.condition, precipitationProbability = base.precipitationProbability,
                precipitationSumMm = if (scenario == Scenario.SUNNY) 0.0 else 6.0,
                maxWindKmh = base.windSpeedKmh, uvIndexMax = if (scenario == Scenario.SUNNY) 7.0 else 3.0,
                sunrise = date.plusSeconds(7 * 3600), sunset = date.plusSeconds((17.5 * 3600).toLong()),
            )
        }
        return WeatherSnapshot(
            place, current, hourly, daily, WeatherDataSource.DEMO,
            retrievedAt = now, expiresAt = now.plusSeconds(30 * 60),
        )
    }

    private fun hour(scenario: Scenario, i: Int, time: Instant): HourlyForecast {
        // Smooth daily temperature curve peaking at 15:00.
        val curve = cos((i - 15).toDouble() / 24 * 2 * PI)
        return when (scenario) {
            Scenario.SUNNY -> {
                val t = 19 + 6 * curve
                HourlyForecast(time, t, t, WeatherCondition.CLEAR, 0, 0.0, 8.0, 15.0, if (i in 9..16) 7.0 else 1.0)
            }
            Scenario.RAINY_AFTERNOON -> {
                val t = 20 + 4 * curve
                val wet = i in 14..17
                HourlyForecast(
                    time, t, t - 1,
                    if (wet) WeatherCondition.RAIN else if (i < 12) WeatherCondition.CLEAR else WeatherCondition.PARTLY_CLOUDY,
                    if (wet) 80 else 10, if (wet) 3.2 else 0.0, 14.0, 25.0, 3.0,
                )
            }
            Scenario.STORMY -> {
                val t = 16 + 3 * curve
                val storm = i in 12..18
                HourlyForecast(
                    time, t, t - 2, if (storm) WeatherCondition.THUNDERSTORM else WeatherCondition.CLOUDY,
                    if (storm) 90 else 40, if (storm) 9.0 else 0.2,
                    if (storm) 45.0 else 25.0, if (storm) 70.0 else 35.0, 1.0,
                )
            }
            Scenario.SNOWY -> {
                val t = -1 + 3 * curve
                HourlyForecast(time, t, t - 4, WeatherCondition.SNOW, 85, 1.0, 15.0, 25.0, 1.0)
            }
            Scenario.CLOUDY -> {
                val t = 14 + 3 * curve
                HourlyForecast(time, t, t, WeatherCondition.CLOUDY, 15, 0.0, 12.0, 20.0, 2.0)
            }
            Scenario.WINDY -> {
                val t = 15 + 4 * curve
                HourlyForecast(time, t, t - 4, WeatherCondition.PARTLY_CLOUDY, 10, 0.0, 42.0, 65.0, 3.0)
            }
        }
    }
}
