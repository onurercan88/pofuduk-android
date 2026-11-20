package com.pofuduk.core

import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.PrecipitationIntensity
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherCondition.CLEAR
import com.pofuduk.core.model.WeatherCondition.CLOUDY
import com.pofuduk.core.model.WeatherCondition.RAIN
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.provider.OpenMeteoProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherSummaryEngineTest {
    private val engine = WeatherSummaryEngine()
    private val now = TestClock.date(9)

    @Test fun sunnyDayHasNoPrecipitationAndSunnyTheme() {
        val a = engine.analyze(MockWeatherData.snapshot(MockWeatherData.Scenario.SUNNY, now), now)
        assertNull(a.precipitation)
        assertTrue(a.hasHourlyCoverage)
        assertEquals(WeatherTheme.SUNNY, a.theme)
        assertTrue(a.isHighUV)
    }

    @Test fun rainBeginningAt14() {
        val hours = TestClock.hours { i, t ->
            val wet = i in 14..16
            hourly(t, 22.0, if (wet) RAIN else CLEAR, if (wet) 80 else 5, if (wet) 1.5 else 0.0)
        }
        val a = engine.analyze(TestClock.snapshot(hours, now = now), now)
        val p = assertNotNull(a.precipitation)
        assertEquals(TestClock.date(14), p.start)
        assertEquals(3, p.durationHours)
        assertEquals(PrecipitationIntensity.LIGHT, p.intensity)
        assertTrue(p.isLikely)
        // Dry now, rain at 14.00: the look stays dry, the wording knows about the rain.
        assertEquals(WeatherTheme.SUNNY, a.theme)
        assertEquals(WeatherTheme.RAINY, a.outlookTheme)
    }

    private fun cloudyNow(t: Instant) = CurrentWeather(t, 15.0, 15.0, CLOUDY, humidity = 70, windSpeedKmh = 10.0)

    @Test fun cloudyNowWithRainLaterDoesNotLookRainy() {
        val hours = TestClock.hours { i, t ->
            val wet = i in 17..18
            hourly(t, 15.0, if (wet) RAIN else CLOUDY, if (wet) 85 else 10, if (wet) 2.0 else 0.0)
        }
        val a = engine.analyze(TestClock.snapshot(hours, cloudyNow(now), now), now)
        assertEquals(WeatherTheme.CLOUDY, a.theme)
        assertEquals(WeatherTheme.RAINY, a.outlookTheme)
    }

    @Test fun rainWithinTheHourLooksRainy() {
        val at = TestClock.date(13, 30)
        val hours = TestClock.hours { i, t ->
            val wet = i in 14..16
            hourly(t, 15.0, if (wet) RAIN else CLOUDY, if (wet) 85 else 10, if (wet) 2.0 else 0.0)
        }
        val a = engine.analyze(TestClock.snapshot(hours, cloudyNow(at), at), at)
        assertEquals(WeatherTheme.RAINY, a.theme)
    }

    @Test fun openMeteoCurrentPrecipitationOverridesCloudyCode() {
        assertEquals(RAIN, OpenMeteoProvider.currentCondition(3, 1.2))
        assertEquals(WeatherCondition.DRIZZLE, OpenMeteoProvider.currentCondition(3, 0.2))
        assertEquals(CLOUDY, OpenMeteoProvider.currentCondition(3, 0.0))
        assertEquals(CLOUDY, OpenMeteoProvider.currentCondition(3, null))
        assertEquals(WeatherCondition.THUNDERSTORM, OpenMeteoProvider.currentCondition(95, 4.0))
    }

    @Test fun heavyPrecipitationRequiresAmounts() {
        val hours = TestClock.hours { i, t -> hourly(t, 18.0, if (i == 12) RAIN else CLOUDY, if (i == 12) 95 else 0, if (i == 12) 12.0 else 0.0) }
        assertEquals(PrecipitationIntensity.HEAVY, engine.analyze(TestClock.snapshot(hours, now = now), now).precipitation?.intensity)
    }

    @Test fun highProbabilityWithoutAmountsIsNeverHeavy() {
        val hours = TestClock.hours { i, t -> hourly(t, 18.0, if (i == 12) RAIN else CLOUDY, if (i == 12) 100 else 0, null) }
        assertEquals(PrecipitationIntensity.UNKNOWN, engine.analyze(TestClock.snapshot(hours, now = now), now).precipitation?.intensity)
    }

    @Test fun lowRainProbabilityIsIgnored() {
        val hours = TestClock.hours { _, t -> hourly(t, 18.0, CLOUDY, 20, 0.0) }
        assertNull(engine.analyze(TestClock.snapshot(hours, now = now), now).precipitation)
    }

    @Test fun possibleRainIsMarkedUncertain() {
        val hours = TestClock.hours { i, t -> hourly(t, 18.0, CLOUDY, if (i == 15) 40 else 0, if (i == 15) 0.3 else 0.0) }
        assertEquals(false, engine.analyze(TestClock.snapshot(hours, now = now), now).precipitation?.isLikely)
    }

    @Test fun strongWind() {
        val hours = TestClock.hours { _, t -> hourly(t, 15.0, WeatherCondition.PARTLY_CLOUDY, 0, 0.0, wind = 30.0, gust = 70.0) }
        val a = engine.analyze(TestClock.snapshot(hours, now = now), now)
        assertEquals(true, a.wind?.isStrong)
        assertEquals(WeatherTheme.WINDY, a.theme)
    }

    @Test fun temperatureSwing() {
        val hours = TestClock.hours { i, t -> hourly(t, if (i < 12) 8.0 else 21.0, CLEAR, 0, 0.0) }
        val a = engine.analyze(TestClock.snapshot(hours, now = now), now)
        assertEquals(8.0, a.minTemperature)
        assertEquals(21.0, a.maxTemperature)
        assertTrue(a.temperatureSwingIsNotable)
    }

    @Test fun missingForecastMeansNoCoverageAndNoClaims() {
        val a = engine.analyze(TestClock.snapshot(emptyList(), now = now), now)
        assertFalse(a.hasHourlyCoverage)
        assertNull(a.precipitation)
        assertFalse(CharacterPersonalityEngine().briefing(a).fullText.contains("yağış beklenmiyor"))
    }

    @Test fun staleForecastIsFlagged() {
        val snap = TestClock.snapshot(emptyList(), now = now, retrievedAt = TestClock.date(6), expiresInSeconds = 1800)
        val a = engine.analyze(snap, now)
        assertTrue(a.isStale)
        assertTrue(CharacterPersonalityEngine().briefing(a).fullText.contains("güncel olmayabilir"))
    }

    @Test fun pastRainIsIgnored() {
        val hours = TestClock.hours { i, t -> hourly(t, 18.0, if (i == 5) RAIN else CLEAR, if (i == 5) 90 else 0, if (i == 5) 2.0 else 0.0) }
        assertNull(engine.analyze(TestClock.snapshot(hours, now = now), now).precipitation)
    }
}
