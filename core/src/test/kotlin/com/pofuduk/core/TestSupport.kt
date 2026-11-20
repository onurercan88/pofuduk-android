package com.pofuduk.core

import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.HourlyForecast
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.provider.HttpClient
import com.pofuduk.core.provider.HttpResponse
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.util.localInstant
import java.time.Instant
import java.time.ZoneId

object TestClock {
    val istanbul: ZoneId = ZoneId.of("Europe/Istanbul")

    /** 2026-11-14 (a Saturday) at the given local Istanbul time. */
    fun date(hour: Int, minute: Int = 0, day: Int = 14): Instant = localInstant(2026, 11, day, hour, minute, istanbul)

    /** Hourly entries starting at local midnight; [configure] returns the hour for a given index. */
    fun hours(count: Int = 24, configure: (Int, Instant) -> HourlyForecast): List<HourlyForecast> {
        val start = date(0)
        return (0 until count).map { configure(it, start.plusSeconds(it * 3600L)) }
    }

    fun snapshot(
        hourly: List<HourlyForecast>,
        current: CurrentWeather? = null,
        now: Instant,
        daily: List<DailyForecast> = emptyList(),
        retrievedAt: Instant? = null,
        expiresInSeconds: Long = 1800,
        source: WeatherDataSource = WeatherDataSource.OPEN_METEO,
    ): WeatherSnapshot {
        val retrieved = retrievedAt ?: now
        val cur = current ?: CurrentWeather(now, 20.0, 20.0, WeatherCondition.CLEAR, humidity = 50, windSpeedKmh = 10.0)
        val place = MockWeatherData.istanbul.copy(source = LocationSource.MANUAL)
        return WeatherSnapshot(place, cur, hourly, daily, source, retrieved, retrieved.plusSeconds(expiresInSeconds)).sorted()
    }
}

/** Canned HTTP responses; records requested URLs. */
class StubHttpClient(json: String = "{}", status: Int = 200, private val error: Throwable? = null) : HttpClient {
    private val responses = mutableListOf(HttpResponse(json.toByteArray(), status))
    val requestedUrls = mutableListOf<String>()
    val requestedHeaders = mutableListOf<Map<String, String>>()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requestedUrls += url
        requestedHeaders += headers
        error?.let { throw it }
        return if (responses.size > 1) responses.removeAt(0) else responses[0]
    }
}

val networkError = WeatherException(WeatherError.NETWORK)

/** Shorthand for hourly test data. */
fun hourly(
    time: Instant,
    temperature: Double,
    condition: WeatherCondition,
    probability: Int? = null,
    mm: Double? = null,
    feelsLike: Double? = null,
    wind: Double? = null,
    gust: Double? = null,
    uv: Double? = null,
) = HourlyForecast(time, temperature, feelsLike, condition, probability, mm, wind, gust, uv)

object Fixtures {
    const val OPEN_METEO = """
    {"utc_offset_seconds":10800,
     "current":{"time":"2026-11-14T09:15","temperature_2m":18.4,"apparent_temperature":16.9,"relative_humidity_2m":71,
                "is_day":1,"weather_code":2,"wind_speed_10m":14.2,"wind_gusts_10m":30.1,"wind_direction_10m":200},
     "hourly":{"time":["2026-11-14T09:00","2026-11-14T10:00","2026-11-14T14:00"],
               "temperature_2m":[18.1,19.0,21.5],"apparent_temperature":[17.0,18.0,20.0],
               "precipitation_probability":[5,10,75],"precipitation":[0.0,0.0,3.1],"weather_code":[2,3,63],
               "wind_speed_10m":[14,15,20],"wind_gusts_10m":[30,31,40],"uv_index":[2.5,3.0,null]},
     "daily":{"time":["2026-11-14","2026-11-15"],"weather_code":[63,0],"temperature_2m_max":[22.0,20.0],
              "temperature_2m_min":[13.0,11.0],"precipitation_sum":[6.2,0.0],"precipitation_probability_max":[80,5],
              "wind_speed_10m_max":[25,12],"uv_index_max":[3.5,4.0],
              "sunrise":["2026-11-14T07:02","2026-11-15T07:03"],"sunset":["2026-11-14T16:52","2026-11-15T16:51"]}}
    """

    /** WeatherKit REST shape (as returned by the proxy); Istanbul is UTC+3. */
    const val WEATHERKIT = """
    {"currentWeather":{"name":"CurrentWeather","asOf":"2026-11-14T12:00:00Z","conditionCode":"PartlyCloudy","daylight":true,
                       "humidity":0.71,"precipitationIntensity":0.0,"pressure":1012.3,"temperature":18.4,
                       "temperatureApparent":16.9,"uvIndex":2,"windDirection":200,"windGust":30.1,"windSpeed":14.2},
     "forecastHourly":{"name":"HourlyForecast","hours":[
        {"forecastStart":"2026-11-14T06:00:00Z","conditionCode":"PartlyCloudy","temperature":18.1,"temperatureApparent":17.0,
         "precipitationChance":0.05,"precipitationAmount":0.0,"windSpeed":14,"windGust":30,"uvIndex":2},
        {"forecastStart":"2026-11-14T07:00:00Z","conditionCode":"MostlyCloudy","temperature":19.0,"temperatureApparent":18.0,
         "precipitationChance":0.1,"precipitationAmount":0.0,"windSpeed":15,"windGust":31,"uvIndex":3},
        {"forecastStart":"2026-11-14T11:00:00Z","conditionCode":"Rain","temperature":21.5,"temperatureApparent":20.0,
         "precipitationChance":0.75,"precipitationAmount":3.1,"windSpeed":20,"windGust":40,"uvIndex":1}]},
     "forecastDaily":{"name":"DailyForecast","days":[
        {"forecastStart":"2026-11-13T21:00:00Z","conditionCode":"Rain","temperatureMax":22.0,"temperatureMin":13.0,
         "precipitationChance":0.8,"sunrise":"2026-11-14T04:02:00Z","sunset":"2026-11-14T13:52:00Z"},
        {"forecastStart":"2026-11-14T21:00:00Z","conditionCode":"Clear","temperatureMax":20.0,"temperatureMin":11.0,
         "precipitationChance":0.05,"sunrise":"2026-11-15T04:03:00Z","sunset":"2026-11-15T13:51:00Z"}]}}
    """
}
