package com.pofuduk.core.provider

import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.util.startOfDay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/**
 * Apple Weather (WeatherKit REST API) through Pofuduk's own proxy (`proxy/weatherkit-worker`). The proxy holds the
 * WeatherKit key and signs the requests; the app never sees it. Coordinates are rounded to 2 decimals (~1 km) before
 * they leave the device, same as iOS. Response = WeatherKit REST JSON (metric), possibly trimmed by the proxy.
 *
 * Proxy contract:
 * - `GET {base}/v1/weather?lat&lon&tz&start` → currentWeather + forecastHourly + forecastDaily, [FORECAST_DAYS] days
 *   from `start` (local midnight, so daily rain totals can be built from the hours).
 * - `GET {base}/v1/current?lat&lon&tz` → currentWeather only (cross-check of "now"). WeatherKit requires a time zone.
 * - Optional header [APP_KEY_HEADER] when the proxy is configured with an app key.
 */
class WeatherKitProxyProvider(
    private val http: HttpClient,
    baseUrl: String,
    private val appKey: String = "",
    private val now: () -> Instant = { Instant.now() },
    val cacheLifetimeSeconds: Double = 30.0 * 60,
) : WeatherProvider, CurrentConditionsProvider {
    private val base = baseUrl.trimEnd('/')
    override val source = WeatherDataSource.APPLE_WEATHER
    override val maxForecastDays = FORECAST_DAYS

    private val headers: Map<String, String> get() = if (appKey.isBlank()) emptyMap() else mapOf(APP_KEY_HEADER to appKey)

    internal fun forecastUrl(place: Place, at: Instant): String = base + "/v1/weather?" + query(
        coordinateParams(place) + listOf(
            "tz" to place.timeZoneIdentifier,
            "start" to at.startOfDay(place.timeZone).toString(),
        ),
    )

    internal fun currentUrl(place: Place): String =
        base + "/v1/current?" + query(coordinateParams(place) + listOf("tz" to place.timeZoneIdentifier))

    override suspend fun fetchSnapshot(place: Place): WeatherSnapshot {
        val retrievedAt = now()
        val dto = fetch(forecastUrl(place, retrievedAt))
        return map(dto, place, retrievedAt, cacheLifetimeSeconds)
    }

    override suspend fun currentConditions(place: Place): CurrentConditionsObservation {
        val current = fetch(currentUrl(place)).currentWeather ?: throw WeatherException(WeatherError.NO_DATA)
        return CurrentConditionsObservation(
            condition = AppleWeatherSnapshotBuilder.condition(current.conditionCode),
            precipitationIntensityMmPerHour = current.precipitationIntensity,
            observedAt = parse(current.asOf),
        )
    }

    private suspend fun fetch(url: String): WeatherKitResponse {
        val response = http.get(url, headers)
        when (response.statusCode) {
            200 -> Unit
            401, 403 -> throw WeatherException(WeatherError.MISSING_API_KEY)
            429 -> throw WeatherException(WeatherError.RATE_LIMITED)
            in 500..599 -> throw WeatherException(WeatherError.NETWORK)
            else -> throw WeatherException(WeatherError.INVALID_RESPONSE)
        }
        return try {
            json.decodeFromString(WeatherKitResponse.serializer(), response.text)
        } catch (e: Exception) {
            throw WeatherException(WeatherError.INVALID_RESPONSE)
        }
    }

    companion object {
        const val FORECAST_DAYS = 7
        const val APP_KEY_HEADER = "X-Pofuduk-Key"
        private val json = Json { ignoreUnknownKeys = true }

        private fun coordinateParams(place: Place) = listOf(
            "lat" to String.format(Locale.ROOT, "%.2f", place.coordinate.latitude),
            "lon" to String.format(Locale.ROOT, "%.2f", place.coordinate.longitude),
        )

        private fun query(params: List<Pair<String, String>>) =
            params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("%2F", "/").replace("%3A", ":") }

        private fun parse(value: String): Instant = try {
            Instant.parse(value)
        } catch (e: Exception) {
            throw WeatherException(WeatherError.INVALID_RESPONSE)
        }

        internal fun map(dto: WeatherKitResponse, place: Place, retrievedAt: Instant, lifetimeSeconds: Double): WeatherSnapshot {
            val c = dto.currentWeather ?: throw WeatherException(WeatherError.NO_DATA)
            val current = AppleWeatherSnapshotBuilder.Current(
                time = parse(c.asOf), temperature = c.temperature, feelsLike = c.temperatureApparent ?: c.temperature,
                condition = c.conditionCode, humidity = c.humidity ?: 0.0, windKmh = c.windSpeed ?: 0.0, gustKmh = c.windGust,
                windDirectionDegrees = c.windDirection, uvIndex = c.uvIndex ?: 0, isDaylight = c.daylight ?: true,
                precipitationIntensityMmPerHour = c.precipitationIntensity ?: 0.0,
            )
            val hours = dto.forecastHourly?.hours.orEmpty().map { h ->
                AppleWeatherSnapshotBuilder.Hour(
                    time = parse(h.forecastStart), temperature = h.temperature, feelsLike = h.temperatureApparent ?: h.temperature,
                    condition = h.conditionCode, precipitationChance = h.precipitationChance ?: 0.0,
                    precipitationMm = h.precipitationAmount ?: 0.0, windKmh = h.windSpeed ?: 0.0, gustKmh = h.windGust,
                    uvIndex = h.uvIndex ?: 0,
                )
            }
            val days = dto.forecastDaily?.days.orEmpty().map { d ->
                AppleWeatherSnapshotBuilder.Day(
                    date = parse(d.forecastStart), low = d.temperatureMin, high = d.temperatureMax, condition = d.conditionCode,
                    precipitationChance = d.precipitationChance ?: 0.0,
                    sunrise = d.sunrise?.let(::parse), sunset = d.sunset?.let(::parse),
                )
            }
            return AppleWeatherSnapshotBuilder.snapshot(place, current, hours, days, retrievedAt, lifetimeSeconds)
        }
    }
}

// WeatherKit REST DTOs (only the fields Pofuduk uses; metric units).
@Serializable
internal data class WeatherKitResponse(
    val currentWeather: WKCurrent? = null,
    val forecastHourly: WKHourly? = null,
    val forecastDaily: WKDaily? = null,
)

@Serializable
internal data class WKCurrent(
    val asOf: String,
    val conditionCode: String,
    val temperature: Double,
    val temperatureApparent: Double? = null,
    val humidity: Double? = null,
    val windSpeed: Double? = null,
    val windGust: Double? = null,
    val windDirection: Double? = null,
    val uvIndex: Int? = null,
    val daylight: Boolean? = null,
    val precipitationIntensity: Double? = null,
)

@Serializable
internal data class WKHourly(val hours: List<WKHour> = emptyList())

@Serializable
internal data class WKHour(
    val forecastStart: String,
    val conditionCode: String,
    val temperature: Double,
    val temperatureApparent: Double? = null,
    val precipitationChance: Double? = null,
    val precipitationAmount: Double? = null,
    val windSpeed: Double? = null,
    val windGust: Double? = null,
    val uvIndex: Int? = null,
)

@Serializable
internal data class WKDaily(val days: List<WKDay> = emptyList())

@Serializable
internal data class WKDay(
    val forecastStart: String,
    val conditionCode: String,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val precipitationChance: Double? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
)
