package com.pofuduk.core

import com.pofuduk.core.alert.MGMOfficialPage
import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.datetime.TurkishGrammar
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.mock.MockWeatherData.Scenario
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.BriefingLength
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.PersonalityStyle
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterPersonalityEngineTest {
    private val now = TestClock.date(9)
    private val summary = WeatherSummaryEngine()

    private fun briefing(scenario: Scenario, style: PersonalityStyle = PersonalityStyle.FRIENDLY, warnings: List<OfficialWarning> = emptyList()): Briefing {
        val a = summary.analyze(MockWeatherData.snapshot(scenario, now), now)
        return CharacterPersonalityEngine(style).briefing(a, warnings)
    }

    private fun warning(level: WarningLevel, hazard: String, fromOffset: Long, untilOffset: Long) = OfficialWarning(
        "1", level, hazard, "", listOf("İstanbul"), validFrom = now.plusSeconds(fromOffset),
        validUntil = now.plusSeconds(untilOffset), sourceUrl = MGMOfficialPage.WARNINGS,
    )

    @Test fun morningGreetingAndDemoDisclosure() {
        val b = briefing(Scenario.SUNNY)
        assertTrue(b.sentences[0].startsWith("Günaydın"))
        assertTrue(b.fullText.contains("örnek verilerle"))
    }

    @Test fun rainyAfternoonMentionsStartTimeAndUmbrella() {
        val b = briefing(Scenario.RAINY_AFTERNOON)
        assertTrue(b.fullText.contains("Saat 14.00 civarında yağmur başlaması bekleniyor"), b.fullText)
        assertTrue(b.fullText.contains("Orta şiddette"))
        assertTrue(b.fullText.contains("Şemsiye"))
        // 09.00 is still dry: the look shows now, the words cover the afternoon rain.
        assertNotEquals(WeatherTheme.RAINY, b.theme)
    }

    @Test fun lookFollowsTheWeatherNow() {
        val at = TestClock.date(15)
        assertEquals(WeatherTheme.STORMY, summary.analyze(MockWeatherData.snapshot(Scenario.STORMY, at), at).theme)
        assertEquals(WeatherTheme.RAINY, summary.analyze(MockWeatherData.snapshot(Scenario.RAINY_AFTERNOON, at), at).theme)
    }

    @Test fun stormIsSeriousEvenInPlayfulStyle() {
        val b = briefing(Scenario.STORMY, PersonalityStyle.PLAYFUL)
        assertEquals(CharacterMood.WARNING, b.restingMood)
        assertFalse(b.fullText.contains("suçlama"))
        assertTrue(b.fullText.contains("dikkatli"))
    }

    @Test fun possibleHoursAreNotMergedIntoLikelyPeriod() {
        val a = summary.analyze(MockWeatherData.snapshot(Scenario.STORMY, now), now)
        assertEquals(TestClock.date(12), a.precipitation?.start)
        assertEquals(7, a.precipitation?.durationHours)
        assertFalse(briefing(Scenario.STORMY).fullText.contains("Şu sıralar"))
    }

    @Test fun rainUntilEndOfDayIsNotGivenFakeDuration() {
        assertTrue(briefing(Scenario.SNOWY).fullText.contains("gün sonuna kadar"))
    }

    @Test fun orangeWarningIsFirstAfterGreeting() {
        val b = briefing(Scenario.SUNNY, PersonalityStyle.PLAYFUL, listOf(warning(WarningLevel.ORANGE, "Kuvvetli yağış", -3600, 3600)))
        val firstFact = b.sentences.indexOfFirst { it.contains("turuncu") }
        val current = b.sentences.indexOfFirst { it.contains("şu an") }
        assertTrue(firstFact >= 0)
        assertTrue(firstFact < current)
        assertEquals(CharacterMood.WARNING, b.restingMood)
    }

    @Test fun expiredWarningIsNotSpoken() {
        assertFalse(briefing(Scenario.SUNNY, warnings = listOf(warning(WarningLevel.RED, "Fırtına", -7200, -3600))).fullText.contains("MGM"))
    }

    @Test fun snowAndWindThemes() {
        assertEquals(WeatherTheme.SNOWY, briefing(Scenario.SNOWY).theme)
        assertTrue(briefing(Scenario.SNOWY).fullText.contains("eksi"))
        assertEquals(WeatherTheme.WINDY, briefing(Scenario.WINDY).theme)
        assertTrue(briefing(Scenario.WINDY).fullText.contains("kilometre"))
    }

    @Test fun everyNumberInBriefingComesFromData() {
        val snap = MockWeatherData.snapshot(Scenario.SUNNY, now)
        assertTrue(briefing(Scenario.SUNNY).fullText.contains(TurkishFormatting.temperature(snap.current.temperature)))
    }

    // Time of day

    private fun briefingAt(scenario: Scenario, hour: Int): Briefing {
        val at = TestClock.date(hour)
        return CharacterPersonalityEngine().briefing(summary.analyze(MockWeatherData.snapshot(scenario, at), at))
    }

    @Test fun morningTellsTheWholeDay() {
        val text = briefingAt(Scenario.SUNNY, 9).fullText
        assertTrue(text.contains("Bugün sıcaklık"))
        assertFalse(text.contains("Yarın"))
    }

    @Test fun eveningSkipsTodaysRangeAndTellsTonightAndTomorrow() {
        val b = briefingAt(Scenario.SUNNY, 17)
        val text = b.fullText
        assertFalse(text.contains("Bugün sıcaklık"), text)
        assertTrue(text.contains("Bu gece sıcaklık"), text)
        assertTrue(text.contains("Yarın sıcaklık"), text)
        assertTrue(text.contains("Bu akşam için yağış beklenmiyor"), text)
        assertFalse(text.contains("Dışarı çıkmak için"), text)
        assertFalse(b.advice.any { it.contains("gün içinde") })
    }

    @Test fun afternoonTalksAboutTheRestOfTheDay() {
        val text = briefingAt(Scenario.SUNNY, 14).fullText
        assertFalse(text.contains("Bugün sıcaklık"), text)
        assertTrue(text.contains("Günün geri kalanında") || text.contains("Akşama doğru"), text)
        assertFalse(text.contains("Yarın"), text)
    }

    @Test fun tomorrowRainIsTold() {
        val at = TestClock.date(19)
        val snap = MockWeatherData.snapshot(Scenario.SUNNY, at).copy(
            daily = listOf(DailyForecast(TestClock.date(0, day = 15), 9.0, 14.0, WeatherCondition.RAIN, 80, 6.0)),
        )
        val text = CharacterPersonalityEngine().briefing(summary.analyze(snap, at)).fullText
        assertTrue(text.contains("Yarın sıcaklık 9 ile 14 derece arasında; yağış bekleniyor, ihtimali yüzde 80."), text)
    }

    @Test fun turkishLocative() {
        assertEquals("İstanbul'da", TurkishGrammar.locative("İstanbul"))
        assertEquals("İzmir'de", TurkishGrammar.locative("İzmir"))
        assertEquals("Kars'ta", TurkishGrammar.locative("Kars"))
        assertEquals("Rize'de", TurkishGrammar.locative("Rize"))
        assertEquals("Bursa'da", TurkishGrammar.locative("Bursa"))
    }
}

class PersonalizationTest {
    private val now = TestClock.date(15)

    private fun briefing(scenario: Scenario, style: PersonalityStyle, length: BriefingLength): Briefing {
        val snap = MockWeatherData.snapshot(scenario, now).copy(source = WeatherDataSource.OPEN_METEO)
        return CharacterPersonalityEngine(style, length).briefing(WeatherSummaryEngine().analyze(snap, now))
    }

    @Test fun shortBriefingIsShorterButKeepsFacts() {
        val short = briefing(Scenario.WINDY, PersonalityStyle.FRIENDLY, BriefingLength.SHORT)
        val detailed = briefing(Scenario.WINDY, PersonalityStyle.FRIENDLY, BriefingLength.DETAILED)
        assertTrue(short.sentences.size < detailed.sentences.size)
        assertTrue(short.fullText.contains("şu an"), "Current conditions always included")
        assertTrue(short.fullText.contains("kilometre"), "Strong wind is never dropped")
        assertFalse(short.fullText.contains("Hissedilen"))
    }

    @Test fun stylesChangeToneNotFacts() {
        val facts = { b: Briefing -> b.sentences.drop(1).dropLast(1).joinToString(" ") }
        val friendly = briefing(Scenario.RAINY_AFTERNOON, PersonalityStyle.FRIENDLY, BriefingLength.DETAILED)
        val playful = briefing(Scenario.RAINY_AFTERNOON, PersonalityStyle.PLAYFUL, BriefingLength.DETAILED)
        val calm = briefing(Scenario.RAINY_AFTERNOON, PersonalityStyle.CALM, BriefingLength.DETAILED)
        assertEquals(facts(friendly), facts(playful))
        assertEquals(facts(friendly), facts(calm))
        assertNotEquals(friendly.sentences.last(), playful.sentences.last())
        assertTrue(playful.fullText.contains("suçlama"))
        assertFalse(calm.sentences.last().contains("!"), "Calm style avoids exclamations")
    }

    @Test fun playfulIsNeverPlayfulInStorms() {
        val b = briefing(Scenario.STORMY, PersonalityStyle.PLAYFUL, BriefingLength.SHORT)
        assertEquals(CharacterMood.WARNING, b.restingMood)
        assertTrue(b.sentences.last().startsWith("Şakayı bir kenara bırakalım"))
    }

    @Test fun preferencesStoreRoundTripAndClamp() {
        val store = com.pofuduk.core.model.UserPreferencesStore(com.pofuduk.core.store.InMemoryKeyValueStore())
        assertEquals(com.pofuduk.core.model.UserPreferences(), store.load())
        val p = com.pofuduk.core.model.UserPreferences(
            style = PersonalityStyle.CALM, briefingLength = BriefingLength.SHORT,
            subtitleSize = com.pofuduk.core.model.SubtitleSize.HUGE, speechRateMultiplier = 3.0,
        )
        store.save(p)
        assertEquals(PersonalityStyle.CALM, store.load().style)
        assertEquals(1.25, store.load().clampedSpeechRate)
        store.clear()
        assertEquals(com.pofuduk.core.model.UserPreferences(), store.load())
        assertNotNull(store)
    }
}
