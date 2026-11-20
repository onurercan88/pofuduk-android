package com.pofuduk.core

import com.pofuduk.core.conversation.ConversationManager
import com.pofuduk.core.conversation.WeatherAnswerEngine
import com.pofuduk.core.data.TurkishProvinces
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.ClothingRecommendationEngine
import com.pofuduk.core.engine.WeatherProviderFallbackEngine
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.notification.InMemoryNotificationLog
import com.pofuduk.core.notification.MorningBriefingEngine
import com.pofuduk.core.notification.NotificationCategory
import com.pofuduk.core.notification.NotificationPreferences
import com.pofuduk.core.notification.QuietHours
import com.pofuduk.core.notification.RainNotificationEngine
import com.pofuduk.core.provider.OpenMeteoProvider
import com.pofuduk.core.provider.UrlConnectionHttpClient
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.provider.WeatherProvider
import com.pofuduk.core.repository.CachedWeatherRepository
import com.pofuduk.core.repository.FileWeatherCacheStore
import com.pofuduk.core.repository.InMemoryWeatherCacheStore
import com.pofuduk.core.speech.PlaybackStatus
import com.pofuduk.core.speech.SubtitleTrack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Always fails with [error] (stands in for providers that are not shipped on Android yet). */
private class FailingProvider(private val error: WeatherError, override val source: WeatherDataSource = WeatherDataSource.WEATHER_API) : WeatherProvider {
    override val maxForecastDays = 3
    override suspend fun fetchSnapshot(place: Place): WeatherSnapshot = throw WeatherException(error)
}

class OpenMeteoProviderTest {
    private val place = MockWeatherData.istanbul
    private val now = TestClock.date(9, 20)

    @Test fun mapsCurrentHourlyDailyInLocalTime() = runTest {
        val snap = OpenMeteoProvider(StubHttpClient(Fixtures.OPEN_METEO), now = { now }).fetchSnapshot(place)
        assertEquals(WeatherDataSource.OPEN_METEO, snap.source)
        assertEquals(18.4, snap.current.temperature)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, snap.current.condition)
        assertEquals(2.5, snap.current.uvIndex, "Current UV comes from the current hour")
        assertEquals(3, snap.hourly.size)
        assertEquals(TestClock.date(14), snap.hourly[2].time)
        assertEquals(75, snap.hourly[2].precipitationProbability)
        assertEquals(WeatherCondition.RAIN, snap.hourly[2].condition)
        assertNull(snap.hourly[2].uvIndex)
        assertEquals(TestClock.date(0), snap.daily.first().date)
        assertEquals(TestClock.date(16, 52), snap.daily.first().sunset)
        assertEquals(now.plusSeconds(1800), snap.expiresAt)
    }

    @Test fun requestUsesPlaceTimeZoneAndNoKey() = runTest {
        val http = StubHttpClient(Fixtures.OPEN_METEO)
        OpenMeteoProvider(http).fetchSnapshot(place)
        val url = http.requestedUrls.first()
        assertTrue(url.contains("timezone=Europe/Istanbul"), url)
        assertTrue(url.contains("latitude=40.9900"), url)
        assertTrue(!url.contains("key", ignoreCase = true), url)
    }

    @Test fun httpErrorsMap() = runTest {
        suspend fun assertThrows(http: StubHttpClient, expected: WeatherError) {
            val e = assertFailsWith<WeatherException> { OpenMeteoProvider(http).fetchSnapshot(place) }
            assertEquals(expected, e.error)
        }
        assertThrows(StubHttpClient(status = 429), WeatherError.RATE_LIMITED)
        assertThrows(StubHttpClient(status = 500), WeatherError.INVALID_RESPONSE)
        assertThrows(StubHttpClient("not json"), WeatherError.INVALID_RESPONSE)
        assertThrows(StubHttpClient("{}"), WeatherError.NO_DATA)
    }

    @Test fun wmoCodes() {
        assertEquals(WeatherCondition.HEAVY_RAIN, OpenMeteoProvider.condition(65))
        assertEquals(WeatherCondition.THUNDERSTORM, OpenMeteoProvider.condition(95))
        assertEquals(WeatherCondition.SNOW, OpenMeteoProvider.condition(75))
        assertEquals(WeatherCondition.FOG, OpenMeteoProvider.condition(45))
    }
}

class FallbackAndCacheTest {
    private val place = MockWeatherData.istanbul
    private val now = TestClock.date(9)

    private fun openMeteo(http: StubHttpClient, at: Instant) = OpenMeteoProvider(http, now = { at })

    @Test fun fallbackUsesSecondProviderWhenFirstFails() = runTest {
        val outcome = WeatherProviderFallbackEngine(listOf(FailingProvider(WeatherError.NETWORK), openMeteo(StubHttpClient(Fixtures.OPEN_METEO), now))).fetch(place)
        assertEquals(WeatherDataSource.OPEN_METEO, outcome.snapshot.source)
        assertEquals(1, outcome.failures.size)
    }

    @Test fun allProvidersFailingThrowsMostInformativeError() = runTest {
        val e = assertFailsWith<WeatherException> {
            WeatherProviderFallbackEngine(listOf(FailingProvider(WeatherError.RATE_LIMITED), openMeteo(StubHttpClient(error = networkError), now))).fetch(place)
        }
        assertEquals(WeatherError.RATE_LIMITED, e.error)
    }

    @Test fun freshCacheAvoidsNetwork() = runTest {
        val http = StubHttpClient(Fixtures.OPEN_METEO)
        val repo = CachedWeatherRepository(WeatherProviderFallbackEngine(listOf(openMeteo(http, now))), InMemoryWeatherCacheStore(), now = { now })
        repo.snapshot(place, false)
        repo.snapshot(place, false)
        assertEquals(1, http.requestedUrls.size)
    }

    @Test fun staleCacheIsReturnedMarkedStaleWhenOffline() = runTest {
        val cache = InMemoryWeatherCacheStore()
        CachedWeatherRepository(WeatherProviderFallbackEngine(listOf(openMeteo(StubHttpClient(Fixtures.OPEN_METEO), now))), cache, now = { now })
            .snapshot(place, false)
        val later = now.plusSeconds(2 * 3600)
        val offline = CachedWeatherRepository(WeatherProviderFallbackEngine(listOf(openMeteo(StubHttpClient(error = networkError), later))), cache, now = { later })
        val snap = offline.snapshot(place, false)
        assertTrue(snap.isStale(later), "Old data must stay flagged as stale")
        val a = WeatherSummaryEngine().analyze(snap, later)
        assertTrue(CharacterPersonalityEngine().briefing(a).fullText.contains("güncel olmayabilir"))
    }

    @Test fun tooOldCacheIsNotUsed() = runTest {
        val cache = InMemoryWeatherCacheStore()
        CachedWeatherRepository(WeatherProviderFallbackEngine(listOf(openMeteo(StubHttpClient(Fixtures.OPEN_METEO), now))), cache, now = { now })
            .snapshot(place, false)
        val muchLater = now.plusSeconds(7 * 3600)
        val offline = CachedWeatherRepository(WeatherProviderFallbackEngine(listOf(openMeteo(StubHttpClient(error = networkError), muchLater))), cache, now = { muchLater })
        assertEquals(WeatherError.NETWORK, assertFailsWith<WeatherException> { offline.snapshot(place, false) }.error)
    }

    @Test fun fileCacheRoundTrip() = runTest {
        val dir = Files.createTempDirectory("pofuduk-cache").toFile()
        val store = FileWeatherCacheStore(dir)
        val snap = MockWeatherData.snapshot(MockWeatherData.Scenario.SUNNY, now)
        store.save(snap, "k")
        assertEquals(snap, store.load("k"))
        store.removeAll()
        assertNull(store.load("k"))
        dir.deleteRecursively()
    }
}

/** End-to-end pipelines with deterministic fixtures (no live network). */
class IntegrationTest {
    private val now = TestClock.date(9, 20)
    private val place = TurkishProvinces.search("İstanbul")[0].place

    @Test fun providerToSpokenBriefingWithMatchingSubtitles() = runTest {
        val repo = CachedWeatherRepository(
            WeatherProviderFallbackEngine(listOf(FailingProvider(WeatherError.NETWORK), OpenMeteoProvider(StubHttpClient(Fixtures.OPEN_METEO), now = { now }))),
            InMemoryWeatherCacheStore(), now = { now },
        )
        val snapshot = repo.snapshot(place, false)
        assertEquals(WeatherDataSource.OPEN_METEO, snapshot.source, "Fell back to the second provider")

        val analysis = WeatherSummaryEngine().analyze(snapshot, now)
        val clothing = ClothingRecommendationEngine().recommend(snapshot, now = now)
        val briefing = CharacterPersonalityEngine().briefing(analysis, clothing = clothing)
        assertTrue(briefing.fullText.contains("İstanbul'da şu an 18 derece"), briefing.fullText)
        assertTrue(briefing.fullText.contains("Saat 14.00 civarında yağmur"), briefing.fullText)

        var track = SubtitleTrack.load(briefing.sentences)
        for (i in briefing.sentences.indices) track = track.didStart(i).didFinish(i)
        assertEquals(briefing.sentences, track.spokenSentences, "Subtitles equal spoken text")
        assertEquals(PlaybackStatus.FINISHED, track.status)
    }

    @Test fun conversationOverOpenMeteoFixture() = runTest {
        val snapshot = OpenMeteoProvider(StubHttpClient(Fixtures.OPEN_METEO), now = { now }).fetchSnapshot(place)
        val answer = WeatherAnswerEngine().answer(ConversationManager().interpret("Bugün şemsiye gerekir mi?", now), snapshot, now)
        assertTrue(answer.fullText.startsWith("Evet, şemsiye almanı öneririm."), answer.fullText)
        assertTrue(answer.fullText.contains("Orta şiddette"), "3.1 mm/h → moderate from amounts")
    }

    @Test fun forecastToNotificationsToMorningBriefing() = runTest {
        val snapshot = OpenMeteoProvider(StubHttpClient(Fixtures.OPEN_METEO), now = { now }).fetchSnapshot(place)
        val prefs = NotificationPreferences(isEnabled = true, quietHours = QuietHours(enabled = false))
        val plan = RainNotificationEngine().plan(snapshot, preferences = prefs, log = InMemoryNotificationLog(), now = now)
        assertEquals(NotificationCategory.RAIN_SOON, plan.firstOrNull()?.category)
        assertEquals(TestClock.date(13, 30), plan.firstOrNull()?.fireDate)

        val morning = MorningBriefingEngine().briefing(snapshot, WeatherSummaryEngine().analyze(snapshot, now), clothing = null, now = now)
        assertEquals("Günaydın!", morning.sentences.first())
    }

    /** Opt-in live check of the real provider: POFUDUK_LIVE_TESTS=1. Skipped in normal runs. */
    @Test fun liveOpenMeteo() = runTest {
        assumeTrue(System.getenv("POFUDUK_LIVE_TESTS") == "1", "Live network tests are opt-in (POFUDUK_LIVE_TESTS=1).")
        val snapshot = OpenMeteoProvider(UrlConnectionHttpClient()).fetchSnapshot(place)
        assertTrue(snapshot.hourly.size >= 72)
        assertTrue(snapshot.daily.size >= 3)
    }
}
