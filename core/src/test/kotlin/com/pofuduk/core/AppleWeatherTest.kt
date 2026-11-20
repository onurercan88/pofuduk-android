package com.pofuduk.core

import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.provider.AppleWeatherConditionMapper
import com.pofuduk.core.provider.AppleWeatherSnapshotBuilder
import com.pofuduk.core.provider.CurrentConditionsObservation
import com.pofuduk.core.provider.CurrentConditionsProvider
import com.pofuduk.core.provider.CurrentConditionsReconciler
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.provider.WeatherKitProxyProvider
import com.pofuduk.core.provider.WeatherRepository
import com.pofuduk.core.repository.CrossCheckedWeatherRepository
import com.pofuduk.core.store.CoreJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val istanbulManual: Place get() = MockWeatherData.istanbul.copy(source = LocationSource.MANUAL)

class AppleWeatherSnapshotBuilderTest {
    private val now = TestClock.date(15)

    private fun current(condition: String = "cloudy", intensity: Double = 0.0) = AppleWeatherSnapshotBuilder.Current(
        time = now, temperature = 12.4, feelsLike = 10.9, condition = condition, humidity = 0.81, windKmh = 18.0,
        gustKmh = 31.0, windDirectionDegrees = 365.0, uvIndex = 2, isDaylight = true, precipitationIntensityMmPerHour = intensity,
    )

    private fun hours(day: Int = 14, rainMm: Double = 0.5) = (0 until 24).map { h ->
        AppleWeatherSnapshotBuilder.Hour(
            time = TestClock.date(h, day = day), temperature = 10.0 + h % 6, feelsLike = 9.0,
            condition = if (h >= 14) "rain" else "mostlyCloudy", precipitationChance = if (h >= 14) 0.8 else 0.1,
            precipitationMm = if (h >= 14) rainMm else 0.0, windKmh = h.toDouble(), gustKmh = null, uvIndex = if (h == 12) 4 else 1,
        )
    }

    private fun day(d: Int) = AppleWeatherSnapshotBuilder.Day(
        date = TestClock.date(0, day = d), low = 8.0, high = 16.0, condition = "rain", precipitationChance = 0.75,
        sunrise = TestClock.date(7, day = d), sunset = TestClock.date(17, day = d),
    )

    private fun build(current: AppleWeatherSnapshotBuilder.Current = current()): WeatherSnapshot =
        AppleWeatherSnapshotBuilder.snapshot(istanbulManual, current, hours(), listOf(day(14), day(15)), now, 1800.0)

    @Test fun mapsUnitsAndSource() {
        val s = build()
        assertEquals(WeatherDataSource.APPLE_WEATHER, s.source)
        assertEquals(81, s.current.humidity)
        assertEquals(5, s.current.windDirectionDegrees, "365° wraps to 5°")
        assertEquals(WeatherCondition.CLOUDY, s.current.condition)
        assertEquals(24, s.hourly.size)
        assertEquals(80, s.hourly[15].precipitationProbability)
        assertEquals(WeatherCondition.RAIN, s.hourly[15].condition)
        assertEquals(WeatherCondition.CLOUDY, s.hourly[3].condition, "mostlyCloudy → cloudy")
        assertEquals(now.plusSeconds(1800), s.expiresAt)
    }

    @Test fun dailyTotalsComeFromThatDaysHours() {
        val s = build()
        assertEquals(2, s.daily.size)
        assertEquals(5.0, s.daily[0].precipitationSumMm!!, 1e-9, "10 wet hours × 0.5 mm")
        assertEquals(23.0, s.daily[0].maxWindKmh)
        assertEquals(4.0, s.daily[0].uvIndexMax)
        assertEquals(75, s.daily[0].precipitationProbability)
        assertNull(s.daily[1].precipitationSumMm, "No hourly data for that day")
    }

    @Test fun measuredRainOverridesDryCurrentCondition() {
        assertEquals(WeatherCondition.RAIN, build(current("cloudy", 0.8)).current.condition)
        assertEquals(WeatherCondition.DRIZZLE, build(current("cloudy", 0.2)).current.condition)
        assertEquals(WeatherCondition.THUNDERSTORM, build(current("thunderstorms")).current.condition)
    }

    @Test fun unknownConditionFallsBackToCloudy() {
        assertEquals(WeatherCondition.CLOUDY, AppleWeatherSnapshotBuilder.condition("somethingNew"))
    }

    @Test fun emptyForecastIsNoData() {
        val e = assertFailsWith<WeatherException> {
            AppleWeatherSnapshotBuilder.snapshot(istanbulManual, current(), emptyList(), emptyList(), now, 1800.0)
        }
        assertEquals(WeatherError.NO_DATA, e.error)
    }

    @Test fun worksWithBriefingEngine() {
        assertEquals(WeatherDataSource.APPLE_WEATHER, WeatherSummaryEngine().analyze(build(), now).source)
    }
}

private class FixedRepository(val snapshot: WeatherSnapshot) : WeatherRepository {
    override suspend fun snapshot(place: Place, forceRefresh: Boolean) = snapshot
}

private class StubChecker(
    private val result: Result<CurrentConditionsObservation>,
    private val delayMillis: Long = 0,
) : CurrentConditionsProvider {
    var calls = 0
    override val source = WeatherDataSource.APPLE_WEATHER
    override suspend fun currentConditions(place: Place): CurrentConditionsObservation {
        calls++
        if (delayMillis > 0) delay(delayMillis)
        return result.getOrThrow()
    }
}

class CrossCheckTest {
    private val now = TestClock.date(15)

    private fun current(c: WeatherCondition) = CurrentWeather(now, 12.0, 11.0, c, humidity = 80, windSpeedKmh = 10.0)

    private fun observation(c: WeatherCondition, mm: Double? = null, minutesAgo: Long = 0) =
        CurrentConditionsObservation(c, mm, now.minusSeconds(minutesAgo * 60))

    private fun reconcile(cur: WeatherCondition, obs: CurrentConditionsObservation) =
        CurrentConditionsReconciler.reconcile(current(cur), obs, now).condition

    @Test fun observedRainReplacesCloudy() {
        val merged = CurrentConditionsReconciler.reconcile(current(WeatherCondition.CLOUDY), observation(WeatherCondition.RAIN), now)
        assertEquals(WeatherCondition.RAIN, merged.condition)
        assertEquals(12.0, merged.temperature, "only the condition changes")
    }

    @Test fun intensityTurnsCloudyObservationIntoPrecipitation() {
        assertEquals(WeatherCondition.DRIZZLE, reconcile(WeatherCondition.CLOUDY, observation(WeatherCondition.CLOUDY, 0.3)))
        assertEquals(WeatherCondition.RAIN, reconcile(WeatherCondition.CLEAR, observation(WeatherCondition.CLOUDY, 1.2)))
        assertEquals(WeatherCondition.CLOUDY, reconcile(WeatherCondition.CLOUDY, observation(WeatherCondition.CLOUDY, 0.05)))
    }

    @Test fun dryObservationNeverRemovesProviderPrecipitation() {
        assertEquals(WeatherCondition.RAIN, reconcile(WeatherCondition.RAIN, observation(WeatherCondition.CLEAR, 0.0)))
    }

    @Test fun onlyHeavierPrecipitationReplacesLighter() {
        assertEquals(WeatherCondition.THUNDERSTORM, reconcile(WeatherCondition.DRIZZLE, observation(WeatherCondition.THUNDERSTORM)))
        assertEquals(WeatherCondition.HEAVY_RAIN, reconcile(WeatherCondition.HEAVY_RAIN, observation(WeatherCondition.DRIZZLE)))
        assertEquals(WeatherCondition.SNOW, reconcile(WeatherCondition.SNOW, observation(WeatherCondition.RAIN)))
    }

    @Test fun oldObservationIsIgnored() {
        assertEquals(WeatherCondition.CLOUDY, reconcile(WeatherCondition.CLOUDY, observation(WeatherCondition.RAIN, minutesAgo = 45)))
    }

    @Test fun appleConditionMapping() {
        val m = AppleWeatherConditionMapper
        assertEquals(WeatherCondition.CLOUDY, m.condition("mostlyCloudy"))
        assertEquals(WeatherCondition.RAIN, m.condition("sunShowers"))
        assertEquals(WeatherCondition.HEAVY_RAIN, m.condition("heavyRain"))
        assertEquals(WeatherCondition.THUNDERSTORM, m.condition("scatteredThunderstorms"))
        assertEquals(WeatherCondition.SLEET, m.condition("wintryMix"))
        assertEquals(WeatherCondition.SNOW, m.condition("flurries"))
        assertEquals(WeatherCondition.FOG, m.condition("haze"))
        assertNull(m.condition("somethingNew"))
    }

    @Test fun restApiPascalCaseCodesMapLikeFrameworkValues() {
        val m = AppleWeatherConditionMapper
        assertEquals(WeatherCondition.CLOUDY, m.condition("MostlyCloudy"))
        assertEquals(WeatherCondition.THUNDERSTORM, m.condition("IsolatedThunderstorms"))
        assertEquals(WeatherCondition.CLEAR, m.condition("Clear"))
        assertEquals(WeatherCondition.WINDY, m.condition("BlowingDust"))
    }

    private fun repository(base: WeatherSnapshot, checker: StubChecker, timeoutMillis: Long = 5_000) =
        CrossCheckedWeatherRepository(FixedRepository(base), checker, timeoutMillis = timeoutMillis, now = { now })

    private fun base(source: WeatherDataSource = WeatherDataSource.OPEN_METEO, retrievedAt: Instant? = null) =
        TestClock.snapshot(emptyList(), current(WeatherCondition.CLOUDY), now, retrievedAt = retrievedAt, source = source)

    @Test fun repositoryAppliesObservationAndMarksSource() = runTest {
        val b = base()
        val result = repository(b, StubChecker(Result.success(observation(WeatherCondition.RAIN)))).snapshot(b.place, false)
        assertEquals(WeatherCondition.RAIN, result.current.condition)
        assertEquals(WeatherDataSource.APPLE_WEATHER, result.crossCheckSource)
        assertEquals(WeatherDataSource.OPEN_METEO, result.source)
    }

    @Test fun repositoryKeepsSnapshotWhenCheckFails() = runTest {
        val b = base()
        assertEquals(b, repository(b, StubChecker(Result.failure(networkError))).snapshot(b.place, false))
    }

    @Test fun repositoryTimesOut() = runTest {
        val b = base()
        val checker = StubChecker(Result.success(observation(WeatherCondition.RAIN)), delayMillis = 2_000)
        val result = repository(b, checker, timeoutMillis = 100).snapshot(b.place, false)
        assertEquals(WeatherCondition.CLOUDY, result.current.condition)
        assertNull(result.crossCheckSource)
    }

    @Test fun demoStaleAndSameSourceSnapshotsAreNotCrossChecked() = runTest {
        val checker = StubChecker(Result.success(observation(WeatherCondition.RAIN)))
        for (b in listOf(base(WeatherDataSource.DEMO), base(WeatherDataSource.APPLE_WEATHER), base(retrievedAt = now.minusSeconds(7200)))) {
            repository(b, checker).snapshot(b.place, false)
        }
        assertEquals(0, checker.calls)
    }

    @Test fun observationIsReusedWithinTtl() = runTest {
        val b = base()
        val checker = StubChecker(Result.success(observation(WeatherCondition.RAIN)))
        val repo = repository(b, checker)
        repo.snapshot(b.place, false)
        repo.snapshot(b.place, true)
        assertEquals(1, checker.calls)
    }

    @Test fun snapshotWithoutCrossCheckFieldStillDecodes() {
        val b = TestClock.snapshot(emptyList(), now = now)
        val obj = CoreJson.parseToJsonElement(CoreJson.encodeToString(WeatherSnapshot.serializer(), b)).jsonObject
        val trimmed = JsonObject(obj - "crossCheckSource")
        assertNull(CoreJson.decodeFromString(WeatherSnapshot.serializer(), trimmed.toString()).crossCheckSource)
    }
}

class WeatherKitProxyProviderTest {
    private val now = TestClock.date(15)

    private fun provider(http: StubHttpClient, key: String = "") =
        WeatherKitProxyProvider(http, "https://proxy.example/", key, now = { now })

    @Test fun requestsRoundedCoordinatesFromLocalMidnight() = runTest {
        val http = StubHttpClient(Fixtures.WEATHERKIT)
        val place = istanbulManual.copy(coordinate = istanbulManual.coordinate.copy(latitude = 41.01384, longitude = 28.94966))
        provider(http, key = "secret").fetchSnapshot(place)
        assertEquals(
            "https://proxy.example/v1/weather?lat=41.01&lon=28.95&tz=Europe/Istanbul&start=2026-11-13T21:00:00Z",
            http.requestedUrls.single(),
        )
        assertEquals(mapOf(WeatherKitProxyProvider.APP_KEY_HEADER to "secret"), http.requestedHeaders.single())
    }

    @Test fun noAppKeyHeaderWhenNotConfigured() = runTest {
        val http = StubHttpClient(Fixtures.WEATHERKIT)
        provider(http).fetchSnapshot(istanbulManual)
        assertTrue(http.requestedHeaders.single().isEmpty())
    }

    @Test fun mapsRestResponse() = runTest {
        val s = provider(StubHttpClient(Fixtures.WEATHERKIT)).fetchSnapshot(istanbulManual)
        assertEquals(WeatherDataSource.APPLE_WEATHER, s.source)
        assertEquals(Instant.parse("2026-11-14T12:00:00Z"), s.current.time)
        assertEquals(18.4, s.current.temperature)
        assertEquals(16.9, s.current.feelsLike)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, s.current.condition)
        assertEquals(71, s.current.humidity)
        assertEquals(200, s.current.windDirectionDegrees)
        assertEquals(3, s.hourly.size)
        assertEquals(WeatherCondition.RAIN, s.hourly[2].condition)
        assertEquals(75, s.hourly[2].precipitationProbability)
        assertEquals(3.1, s.hourly[2].precipitationMm)
        assertEquals(2, s.daily.size)
        assertEquals(TestClock.date(0), s.daily[0].date)
        assertEquals(3.1, s.daily[0].precipitationSumMm!!, 1e-9)
        assertEquals(80, s.daily[0].precipitationProbability)
        assertEquals(Instant.parse("2026-11-14T04:02:00Z"), s.daily[0].sunrise)
        assertEquals(now.plusSeconds(1800), s.expiresAt)
    }

    @Test fun currentConditionsUseCurrentEndpoint() = runTest {
        val http = StubHttpClient(Fixtures.WEATHERKIT)
        val obs = provider(http).currentConditions(istanbulManual)
        assertEquals("https://proxy.example/v1/current?lat=40.99&lon=29.03&tz=Europe/Istanbul", http.requestedUrls.single())
        assertEquals(WeatherCondition.PARTLY_CLOUDY, obs.condition)
        assertEquals(0.0, obs.precipitationIntensityMmPerHour)
        assertEquals(Instant.parse("2026-11-14T12:00:00Z"), obs.observedAt)
    }

    @Test fun statusCodesMapToErrors() = runTest {
        for ((status, error) in listOf(401 to WeatherError.MISSING_API_KEY, 429 to WeatherError.RATE_LIMITED,
            502 to WeatherError.NETWORK, 400 to WeatherError.INVALID_RESPONSE)) {
            val e = assertFailsWith<WeatherException> { provider(StubHttpClient("{}", status)).fetchSnapshot(istanbulManual) }
            assertEquals(error, e.error, "HTTP $status")
        }
    }

    @Test fun malformedOrEmptyResponses() = runTest {
        val bad = assertFailsWith<WeatherException> { provider(StubHttpClient("<html>")).fetchSnapshot(istanbulManual) }
        assertEquals(WeatherError.INVALID_RESPONSE, bad.error)
        val empty = assertFailsWith<WeatherException> { provider(StubHttpClient("{}")).fetchSnapshot(istanbulManual) }
        assertEquals(WeatherError.NO_DATA, empty.error)
    }
}
