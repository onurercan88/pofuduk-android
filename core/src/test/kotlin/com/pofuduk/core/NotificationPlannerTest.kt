package com.pofuduk.core

import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.WeatherCondition.CLOUDY
import com.pofuduk.core.model.WeatherCondition.RAIN
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.notification.InMemoryNotificationLog
import com.pofuduk.core.notification.MorningBriefingSettings
import com.pofuduk.core.notification.NotificationCategory
import com.pofuduk.core.notification.NotificationPlanner
import com.pofuduk.core.notification.NotificationPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPlannerTest {
    private val planner = NotificationPlanner()
    private val now = TestClock.date(13, 30)
    private val prefs = NotificationPreferences(isEnabled = true)
    private val rainAt15 = TestClock.snapshot(
        TestClock.hours(48) { i, t -> hourly(t, 18.0, if (i == 15) RAIN else CLOUDY, if (i == 15) 80 else 5, if (i == 15) 1.2 else 0.0) },
        now = now,
        daily = MockWeatherData.snapshot(MockWeatherData.Scenario.CLOUDY, now).daily,
    )

    @Test fun plansRainAndMorningAndRecordsThem() {
        val log = InMemoryNotificationLog()
        val plan = planner.plan(rainAt15, emptyList(), prefs, MorningBriefingSettings(sendsNotification = true), true, log, now)
        assertEquals(listOf(NotificationCategory.RAIN_SOON, NotificationCategory.MORNING_BRIEFING), plan.map { it.category })
        assertEquals(TestClock.date(7, 30, day = 15), plan.last().fireDate)
        plan.forEach { assertEquals(it.fireDate, log.fireDate(it.id)) }
        assertTrue(plan.first().body.startsWith("Saat 15.00 civarında İstanbul'da yağmur bekleniyor"), plan.first().body)
    }

    @Test fun nothingWithoutOptInPermissionOrRealData() {
        val log = InMemoryNotificationLog()
        val morning = MorningBriefingSettings(sendsNotification = true)
        assertTrue(planner.plan(rainAt15, emptyList(), prefs.copy(isEnabled = false), morning, true, log, now).isEmpty())
        assertTrue(planner.plan(rainAt15, emptyList(), prefs, morning, permissionGranted = false, log, now).isEmpty())
        assertTrue(planner.plan(rainAt15.copy(source = WeatherDataSource.DEMO), emptyList(), prefs, morning, true, log, now).isEmpty())
        assertTrue(planner.plan(null, emptyList(), prefs, morning, true, log, now).isEmpty())
    }

    @Test fun deliveredMorningNotificationIsNotRepeated() {
        val log = InMemoryNotificationLog()
        val morning = MorningBriefingSettings(sendsNotification = true)
        val first = planner.plan(rainAt15, emptyList(), prefs, morning, true, log, now)
        val m = first.first { it.category == NotificationCategory.MORNING_BRIEFING }
        // Next morning after delivery (07:35): today's 07:30 must not be planned again.
        val later = m.fireDate.plusSeconds(5 * 60)
        val again = planner.plan(rainAt15.copy(retrievedAt = later, expiresAt = later.plusSeconds(1800)), emptyList(), prefs, morning, true, log, later)
        assertTrue(again.none { it.id == m.id }, again.toString())
    }
}
