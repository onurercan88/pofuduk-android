package com.pofuduk.core.mock

import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.provider.WeatherRepository
import java.time.Instant

/** Labelled demo mode: every place gets the chosen sample scenario (source = DEMO, always disclosed). */
class DemoWeatherRepository(
    private val scenario: MockWeatherData.Scenario,
    private val now: () -> Instant = { Instant.now() },
) : WeatherRepository {
    override suspend fun snapshot(place: Place, forceRefresh: Boolean): WeatherSnapshot =
        MockWeatherData.snapshot(scenario, now(), place.copy(source = LocationSource.DEMO))
}
