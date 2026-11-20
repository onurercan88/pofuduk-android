package com.pofuduk.core.provider

import com.pofuduk.core.datetime.LocalTimeParser
import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.HourlyForecast
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.util.plusSeconds
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.startOfHour
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** Open-Meteo adapter. Free API is NON-COMMERCIAL only (see API_INTEGRATIONS.md); attribution CC BY 4.0. */
class OpenMeteoProvider(
    private val http: HttpClient,
    private val now: () -> Instant = { Instant.now() },
    private val baseUrl: String = "https://api.open-meteo.com/v1/forecast",
    val cacheLifetimeSeconds: Double = 30.0 * 60,
) : WeatherProvider {
    override val source = WeatherDataSource.OPEN_METEO
    override val maxForecastDays = 7

    internal fun url(place: Place): String {
        val params = listOf(
            "latitude" to String.format(Locale.ROOT, "%.4f", place.coordinate.latitude),
            "longitude" to String.format(Locale.ROOT, "%.4f", place.coordinate.longitude),
            "current" to "temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,precipitation,wind_speed_10m,wind_gusts_10m,wind_direction_10m",
            "hourly" to "temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,uv_index",
            "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,uv_index_max,sunrise,sunset",
            "timezone" to place.timeZoneIdentifier,
            "forecast_days" to "5",
            "wind_speed_unit" to "kmh",
        )
        // Commas and slashes are kept readable (same request shape as iOS).
        return baseUrl + "?" + params.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8").replace("%2C", ",").replace("%2F", "/")
        }
    }

    override suspend fun fetchSnapshot(place: Place): WeatherSnapshot {
        val response = http.get(url(place))
        when (response.statusCode) {
            200 -> Unit
            429 -> throw WeatherException(WeatherError.RATE_LIMITED)
            else -> throw WeatherException(WeatherError.INVALID_RESPONSE)
        }
        val dto = try {
            json.decodeFromString(OpenMeteoResponse.serializer(), response.text)
        } catch (e: Exception) {
            throw WeatherException(WeatherError.INVALID_RESPONSE)
        }
        return map(dto, place, now(), cacheLifetimeSeconds)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun map(dto: OpenMeteoResponse, place: Place, retrievedAt: Instant, lifetimeSeconds: Double): WeatherSnapshot {
            val zone = place.timeZone
            val cur = dto.current ?: throw WeatherException(WeatherError.NO_DATA)
            val curTime = LocalTimeParser.dateTime(cur.time, zone) ?: throw WeatherException(WeatherError.NO_DATA)
            val temp = cur.temperature_2m ?: throw WeatherException(WeatherError.NO_DATA)

            val hourly = mutableListOf<HourlyForecast>()
            dto.hourly?.let { h ->
                for ((i, t) in h.time.withIndex()) {
                    val time = LocalTimeParser.dateTime(t, zone) ?: continue
                    val tt = h.temperature_2m.getOrNull(i) ?: continue
                    hourly += HourlyForecast(
                        time = time, temperature = tt, feelsLike = h.apparent_temperature?.getOrNull(i),
                        condition = condition(h.weather_code?.getOrNull(i)),
                        precipitationProbability = h.precipitation_probability?.getOrNull(i)?.roundedInt(),
                        precipitationMm = h.precipitation?.getOrNull(i),
                        windSpeedKmh = h.wind_speed_10m?.getOrNull(i), windGustKmh = h.wind_gusts_10m?.getOrNull(i),
                        uvIndex = h.uv_index?.getOrNull(i),
                    )
                }
            }

            val daily = mutableListOf<DailyForecast>()
            dto.daily?.let { d ->
                for ((i, t) in d.time.withIndex()) {
                    val date = LocalTimeParser.day(t, zone) ?: continue
                    val lo = d.temperature_2m_min?.getOrNull(i) ?: continue
                    val hi = d.temperature_2m_max?.getOrNull(i) ?: continue
                    daily += DailyForecast(
                        date = date, minTemperature = lo, maxTemperature = hi,
                        condition = condition(d.weather_code?.getOrNull(i)),
                        precipitationProbability = d.precipitation_probability_max?.getOrNull(i)?.roundedInt(),
                        precipitationSumMm = d.precipitation_sum?.getOrNull(i), maxWindKmh = d.wind_speed_10m_max?.getOrNull(i),
                        uvIndexMax = d.uv_index_max?.getOrNull(i),
                        sunrise = d.sunrise?.getOrNull(i)?.let { LocalTimeParser.dateTime(it, zone) },
                        sunset = d.sunset?.getOrNull(i)?.let { LocalTimeParser.dateTime(it, zone) },
                    )
                }
            }

            // `current` has no UV index → use the current hour's hourly value.
            val hourStart = curTime.startOfHour(zone)
            val currentUV = hourly.firstOrNull { it.time == hourStart }?.uvIndex

            val current = CurrentWeather(
                time = curTime, temperature = temp, feelsLike = cur.apparent_temperature ?: temp,
                condition = currentCondition(cur.weather_code, cur.precipitation),
                humidity = (cur.relative_humidity_2m ?: 0.0).roundedInt(),
                windSpeedKmh = cur.wind_speed_10m ?: 0.0, windGustKmh = cur.wind_gusts_10m,
                windDirectionDegrees = cur.wind_direction_10m?.roundedInt(),
                uvIndex = currentUV, isDay = (cur.is_day ?: 1) == 1,
            )

            return WeatherSnapshot(
                place, current, hourly, daily, WeatherDataSource.OPEN_METEO,
                retrievedAt = retrievedAt, expiresAt = retrievedAt.plusSeconds(lifetimeSeconds),
            ).sorted()
        }

        /**
         * The current weather code can say "cloudy" while the model's current precipitation amount
         * (sum of the preceding 15 minutes) shows it is raining. Measured amount wins over the code.
         */
        fun currentCondition(wmo: Int?, precipitationMm: Double?): WeatherCondition {
            val fromCode = condition(wmo)
            if (fromCode.isPrecipitation || precipitationMm == null || precipitationMm < 0.1) return fromCode
            return if (precipitationMm < 0.5) WeatherCondition.DRIZZLE else WeatherCondition.RAIN
        }

        /** WMO weather interpretation codes. */
        fun condition(wmo: Int?): WeatherCondition = when (wmo ?: -1) {
            0, 1 -> WeatherCondition.CLEAR
            2 -> WeatherCondition.PARTLY_CLOUDY
            3 -> WeatherCondition.CLOUDY
            45, 48 -> WeatherCondition.FOG
            51, 53, 55 -> WeatherCondition.DRIZZLE
            56, 57, 66, 67 -> WeatherCondition.SLEET
            61, 63, 80, 81 -> WeatherCondition.RAIN
            65, 82 -> WeatherCondition.HEAVY_RAIN
            71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
            95, 96, 99 -> WeatherCondition.THUNDERSTORM
            else -> WeatherCondition.CLOUDY
        }
    }
}

// DTOs (field names match the API).

@Suppress("PropertyName")
@Serializable
internal data class OpenMeteoResponse(
    val utc_offset_seconds: Int? = null,
    val current: Current? = null,
    val hourly: Hourly? = null,
    val daily: Daily? = null,
) {
    @Serializable
    data class Current(
        val time: String,
        val temperature_2m: Double? = null,
        val apparent_temperature: Double? = null,
        val relative_humidity_2m: Double? = null,
        val is_day: Int? = null,
        val weather_code: Int? = null,
        val precipitation: Double? = null,
        val wind_speed_10m: Double? = null,
        val wind_gusts_10m: Double? = null,
        val wind_direction_10m: Double? = null,
    )

    @Serializable
    data class Hourly(
        val time: List<String>,
        val temperature_2m: List<Double?>,
        val apparent_temperature: List<Double?>? = null,
        val precipitation_probability: List<Double?>? = null,
        val precipitation: List<Double?>? = null,
        val weather_code: List<Int?>? = null,
        val wind_speed_10m: List<Double?>? = null,
        val wind_gusts_10m: List<Double?>? = null,
        val uv_index: List<Double?>? = null,
    )

    @Serializable
    data class Daily(
        val time: List<String>,
        val weather_code: List<Int?>? = null,
        val temperature_2m_max: List<Double?>? = null,
        val temperature_2m_min: List<Double?>? = null,
        val precipitation_sum: List<Double?>? = null,
        val precipitation_probability_max: List<Double?>? = null,
        val wind_speed_10m_max: List<Double?>? = null,
        val uv_index_max: List<Double?>? = null,
        val sunrise: List<String?>? = null,
        val sunset: List<String?>? = null,
    )
}
