package com.pofuduk.core

import com.pofuduk.core.alert.MGMOfficialPage
import com.pofuduk.core.engine.ClothingRecommendationEngine
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.mock.MockWeatherData.Scenario
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherCondition.CLOUDY
import com.pofuduk.core.model.WeatherCondition.RAIN
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.notification.InMemoryNotificationLog
import com.pofuduk.core.notification.MorningBriefingEngine
import com.pofuduk.core.notification.MorningBriefingSettings
import com.pofuduk.core.notification.NotificationCategory
import com.pofuduk.core.notification.NotificationPreferences
import com.pofuduk.core.notification.QuietHours
import com.pofuduk.core.notification.RainNotificationEngine
import com.pofuduk.core.notification.StoredNotificationLog
import com.pofuduk.core.store.InMemoryKeyValueStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RainNotificationEngineTest {
    private val engine = RainNotificationEngine()
    private val now = TestClock.date(13, 30)
    private val prefs = NotificationPreferences(isEnabled = true)

    private fun rainAt(
        hour: Int, mm: Double = 1.2, prob: Int = 80, condition: WeatherCondition = RAIN, retrievedAt: Instant? = null,
    ): WeatherSnapshot = TestClock.snapshot(
        TestClock.hours { i, t -> hourly(t, 18.0, if (i == hour) condition else CLOUDY, if (i == hour) prob else 5, if (i == hour) mm else 0.0, wind = 10.0) },
        now = now, retrievedAt = retrievedAt,
    )

    private fun plan(snapshot: WeatherSnapshot, p: NotificationPreferences = prefs, at: Instant = now, warnings: List<OfficialWarning> = emptyList()) =
        engine.plan(snapshot, warnings, p, InMemoryNotificationLog(), at)

    @Test fun rainExpectedWithin30Minutes() {
        val n = assertNotNull(plan(rainAt(14)).firstOrNull())
        assertEquals(NotificationCategory.RAIN_SOON, n.category)
        assertEquals(now.plusSeconds(60), n.fireDate, "Lead time already passed → notify right away")
        assertTrue(n.body.startsWith("Yaklaşık 30 dakika içinde İstanbul'da yağmur bekleniyor"), n.body)
        assertTrue(n.body.contains("Kaynak: Open-Meteo.com tahmini"), "Source must be stated")
    }

    @Test fun leadTimeIsRespected() {
        assertEquals(TestClock.date(16, 30), plan(rainAt(17)).firstOrNull()?.fireDate)
    }

    @Test fun belowUserThresholdNoNotification() {
        assertTrue(plan(rainAt(15), prefs.copy(rainProbabilityThreshold = 90)).isEmpty())
    }

    @Test fun duplicateNotificationsAreSuppressed() {
        val log = InMemoryNotificationLog()
        engine.plan(rainAt(14), preferences = prefs, log = log, now = now).forEach { log.record(it.id, it.fireDate) }
        val later = now.plusSeconds(5 * 60) // already delivered
        assertTrue(engine.plan(rainAt(14, retrievedAt = later), preferences = prefs, log = log, now = later).isEmpty())
    }

    @Test fun rescheduleBeforeDeliveryIsAllowed() {
        val log = InMemoryNotificationLog()
        engine.plan(rainAt(17), preferences = prefs, log = log, now = now).forEach { log.record(it.id, it.fireDate) }
        assertEquals(1, engine.plan(rainAt(17), preferences = prefs, log = log, now = now).size, "Not yet fired → may be replaced")
    }

    @Test fun quietHours() {
        val night = TestClock.date(22, 30)
        val snap = TestClock.snapshot(TestClock.hours { i, t -> hourly(t, 12.0, if (i == 23) RAIN else CLOUDY, if (i == 23) 90 else 0, if (i == 23) 2.0 else 0.0) }, now = night)
        assertTrue(plan(snap, at = night).isEmpty())
        assertEquals(1, plan(snap, prefs.copy(quietHours = QuietHours(enabled = false)), night).size)
    }

    @Test fun staleForecastNeverNotifies() {
        val snap = rainAt(14, retrievedAt = now.minusSeconds(2 * 3600))
        assertTrue(snap.isStale(now))
        assertTrue(plan(snap).isEmpty())
    }

    @Test fun disabledOrCategoryOffMeansNothing() {
        assertTrue(plan(rainAt(14), prefs.copy(isEnabled = false)).isEmpty())
        assertTrue(plan(rainAt(14), prefs.copy(enabledCategories = prefs.enabledCategories - NotificationCategory.RAIN_SOON)).isEmpty())
    }

    @Test fun heavyAndThunderCategories() {
        assertEquals(NotificationCategory.HEAVY_RAIN, plan(rainAt(15, mm = 10.0)).firstOrNull()?.category)
        assertEquals(NotificationCategory.SEVERE_WEATHER, plan(rainAt(15, condition = WeatherCondition.THUNDERSTORM)).firstOrNull()?.category)
    }

    @Test fun severeOfficialWarningHasPriorityAndBreaksQuietHours() {
        val night = TestClock.date(23)
        val snap = TestClock.snapshot(TestClock.hours { _, t -> hourly(t, 12.0, RAIN, 90, 2.0) }, now = night)
        val w = OfficialWarning(
            "w1", WarningLevel.ORANGE, "Kuvvetli yağış", "Sel riskine dikkat.", listOf("İstanbul"),
            validFrom = night.minusSeconds(60), validUntil = night.plusSeconds(7200), sourceUrl = MGMOfficialPage.WARNINGS,
        )
        val result = plan(snap, prefs.copy(quietHours = QuietHours(enabled = true)), night, listOf(w))
        val first = assertNotNull(result.firstOrNull())
        assertEquals(NotificationCategory.MGM_WARNING, first.category)
        assertTrue(first.isTimeSensitive)
        assertTrue(first.body.contains("Kaynak: MGM (resmi uyarı)"))
        assertFalse(result.any { it.category == NotificationCategory.RAIN_SOON }, "Forecast notifications respect quiet hours")
    }

    @Test fun forecastBeyondHorizonIsNotScheduled() {
        // Rain 8 h after retrieval: outside the 6 h horizon → wait for a fresher forecast.
        val early = TestClock.date(6)
        val snap = TestClock.snapshot(
            TestClock.hours { i, t -> hourly(t, 15.0, if (i == 14) RAIN else WeatherCondition.CLEAR, if (i == 14) 90 else 0, if (i == 14) 2.0 else 0.0) },
            now = early, expiresInSeconds = 12 * 3600,
        )
        assertTrue(plan(snap, at = early).isEmpty())
    }

    @Test fun strongWindAndTemperatureChange() {
        val snap = TestClock.snapshot(
            TestClock.hours { i, t -> hourly(t, 15.0, CLOUDY, 0, 0.0, wind = if (i == 16) 50.0 else 10.0, gust = if (i == 16) 75.0 else 15.0) },
            now = now,
            daily = listOf(
                DailyForecast(TestClock.date(0), 10.0, 22.0, CLOUDY),
                DailyForecast(TestClock.date(0, day = 15), 3.0, 11.0, CLOUDY),
            ),
        )
        val result = plan(snap, prefs.copy(enabledCategories = prefs.enabledCategories + NotificationCategory.TEMPERATURE_CHANGE))
        assertTrue(result.any { it.category == NotificationCategory.STRONG_WIND && it.body.contains("75 kilometreye") })
        val temp = result.firstOrNull { it.category == NotificationCategory.TEMPERATURE_CHANGE }
        assertEquals(TestClock.date(19), temp?.fireDate)
        assertTrue(temp?.body?.contains("11 derece daha serin") == true, temp?.body ?: "")
    }

    @Test fun quietHoursWrapMidnight() {
        val q = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(q.contains(TestClock.date(23), TestClock.istanbul))
        assertTrue(q.contains(TestClock.date(6, 59), TestClock.istanbul))
        assertFalse(q.contains(TestClock.date(7), TestClock.istanbul))
    }

    @Test fun storedLogRoundTripAndPrune() {
        val log = StoredNotificationLog(InMemoryKeyValueStore())
        log.record("a", TestClock.date(10))
        log.record("b", TestClock.date(12))
        assertEquals(TestClock.date(10), log.fireDate("a"))
        log.prune(TestClock.date(11))
        assertNull(log.fireDate("a"))
        assertEquals(TestClock.date(12), log.fireDate("b"))
    }
}

class MorningBriefingTest {
    private val engine = MorningBriefingEngine()
    private val tz = TestClock.istanbul

    @Test fun shouldGreetWindowAndOncePerDay() {
        var s = MorningBriefingSettings() // 07:30
        assertFalse(engine.shouldGreet(TestClock.date(7, 10), s, tz))
        assertTrue(engine.shouldGreet(TestClock.date(7, 45), s, tz))
        assertFalse(engine.shouldGreet(TestClock.date(12, 5), s, tz))
        s = s.copy(lastGreetedDay = MorningBriefingEngine.dayKey(TestClock.date(8), tz))
        assertFalse(engine.shouldGreet(TestClock.date(9), s, tz), "Once per day")
        assertTrue(engine.shouldGreet(TestClock.date(9, day = 15), s, tz), "Next day again")
        assertFalse(engine.shouldGreet(TestClock.date(9, day = 15), s.copy(isEnabled = false), tz))
    }

    @Test fun customTime() {
        assertTrue(engine.shouldGreet(TestClock.date(6, 20), MorningBriefingSettings(minuteOfDay = 6 * 60 + 15), tz))
    }

    private fun briefing(scenario: Scenario, hour: Int, warnings: List<OfficialWarning> = emptyList()): Briefing {
        val now = TestClock.date(hour)
        val snap = MockWeatherData.snapshot(scenario, now).copy(source = WeatherDataSource.OPEN_METEO)
        val a = WeatherSummaryEngine().analyze(snap, now)
        val c = ClothingRecommendationEngine().recommend(snap, now = now)
        return engine.briefing(snap, a, warnings, c, now)
    }

    @Test fun morningArcRainAndUmbrella() {
        val b = briefing(Scenario.RAINY_AFTERNOON, 8)
        assertEquals("Günaydın!", b.sentences.first())
        assertTrue(b.fullText.contains("sabah"), b.fullText)
        assertTrue(b.fullText.contains("dereceyle başlıyoruz"), b.fullText)
        assertTrue(b.fullText.contains("Öğleden sonra sıcaklık"), b.fullText)
        assertTrue(b.fullText.contains("Ancak öğleden sonra yağmur bekleniyor."), b.fullText)
        assertEquals("Dışarı çıkacaksan şemsiyeni hazırla!", b.sentences.last())
    }

    @Test fun sunnyMorningIsCheerfulAndMentionsUV() {
        val b = briefing(Scenario.SUNNY, 8)
        assertTrue(b.fullText.contains("Bugün senin için güzel bir gün olabilir."))
        assertTrue(b.fullText.contains("Gün boyu yağış beklenmiyor."))
        assertTrue(b.fullText.contains("UV indeksi 7"))
        assertEquals(CharacterMood.HAPPY, b.restingMood)
    }

    @Test fun lateMorningUsesCurrentTemperature() {
        assertTrue(briefing(Scenario.CLOUDY, 11).fullText.contains("şu an"))
    }

    @Test fun officialWarningComesRightAfterGreetingAndIsSerious() {
        val now = TestClock.date(8)
        val w = OfficialWarning(
            "1", WarningLevel.RED, "Fırtına", "", listOf("İstanbul"),
            validFrom = now.minusSeconds(60), validUntil = now.plusSeconds(3600), sourceUrl = MGMOfficialPage.WARNINGS,
        )
        val b = briefing(Scenario.SUNNY, 8, listOf(w))
        assertEquals("Günaydın.", b.sentences[0])
        assertTrue(b.sentences[1].contains("kırmızı seviyede"))
        assertFalse(b.fullText.contains("güzel bir gün"))
        assertEquals(CharacterMood.WARNING, b.restingMood)
    }

    @Test fun clothingIncludedWhenNoteworthy() {
        assertTrue(briefing(Scenario.SNOWY, 8).fullText.contains("mont"))
    }

    @Test fun notificationScheduledForNextBriefingTime() {
        val evening = TestClock.date(21)
        val snap = MockWeatherData.snapshot(Scenario.RAINY_AFTERNOON, evening)
        val n = assertNotNull(engine.notification(snap, MorningBriefingSettings(sendsNotification = true), evening))
        assertEquals(TestClock.date(7, 30, day = 15), n.fireDate)
        assertEquals("morningBriefing-2026-11-15", n.id)
        assertTrue(n.body.contains("Kaynak:"))
        assertTrue(n.body.contains("Yağış ihtimali"))
    }

    @Test fun noNotificationWhenOffOrForecastTooOld() {
        val evening = TestClock.date(21)
        val snap = MockWeatherData.snapshot(Scenario.SUNNY, evening)
        assertNull(engine.notification(snap, MorningBriefingSettings(), evening), "Text notification is opt-in")
        val old = snap.copy(retrievedAt = TestClock.date(9)) // ~22.5 h before 07:30 tomorrow
        assertNull(engine.notification(old, MorningBriefingSettings(sendsNotification = true), evening))
    }
}
