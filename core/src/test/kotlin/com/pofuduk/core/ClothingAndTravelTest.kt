package com.pofuduk.core

import com.pofuduk.core.data.TurkishProvinces
import com.pofuduk.core.datetime.TurkishGrammar
import com.pofuduk.core.conversation.DateReference
import com.pofuduk.core.conversation.WeatherQueryParser
import com.pofuduk.core.conversation.WeatherTopic
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.ClothingRecommendation
import com.pofuduk.core.engine.ClothingRecommendationEngine
import com.pofuduk.core.engine.TravelWeatherSummaryEngine
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.mock.MockWeatherData.Scenario
import com.pofuduk.core.model.Coordinate
import com.pofuduk.core.model.HourlyForecast
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition.CLEAR
import com.pofuduk.core.model.WeatherCondition.CLOUDY
import com.pofuduk.core.model.WeatherCondition.PARTLY_CLOUDY
import com.pofuduk.core.model.WeatherCondition.RAIN
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClothingRecommendationEngineTest {
    private val engine = ClothingRecommendationEngine()
    private val now = TestClock.date(8)

    private fun snap(make: (Int, Instant) -> HourlyForecast) = TestClock.snapshot(TestClock.hours(48, make), now = now)
    private fun ids(r: ClothingRecommendation?) = r?.items?.map { it.id }?.toSet() ?: emptySet()

    @Test fun hotWeather() {
        val r = assertNotNull(engine.recommend(snap { _, t -> hourly(t, 31.0, CLEAR, 0, 0.0, feelsLike = 33.0, wind = 8.0, uv = 8.0) }, now = now))
        assertTrue(ids(r).containsAll(listOf("lightClothes", "sunscreen", "sunglasses")))
        assertFalse("coat" in ids(r))
        assertTrue(r.summary.contains("hafif"), r.summary)
        assertTrue(r.summary.contains("güneşten korunmayı"))
        assertTrue(r.isNoteworthy)
    }

    @Test fun coldWeather() {
        val r = assertNotNull(engine.recommend(snap { _, t -> hourly(t, 0.0, CLOUDY, 0, 0.0, feelsLike = -3.0, wind = 10.0) }, now = now))
        assertTrue(ids(r).containsAll(listOf("thickCoat", "scarf", "gloves", "beanie", "sweater")))
        assertTrue(r.summary.contains("Kalın bir mont"), r.summary)
        assertTrue(r.summary.contains("eksi 3 derece"), r.summary)
    }

    @Test fun windyWeatherExplainsChill() {
        val r = assertNotNull(engine.recommend(snap { _, t -> hourly(t, 18.0, PARTLY_CLOUDY, 0, 0.0, feelsLike = 14.0, wind = 45.0, gust = 65.0) }, now = now))
        assertTrue("windbreaker" in ids(r))
        assertTrue(r.summary.contains("rüzgâr nedeniyle"), r.summary)
        assertTrue(r.reasons.any { it.contains("Rüzgâr saatte 45") })
    }

    @Test fun rainyWeather() {
        val r = assertNotNull(engine.recommend(snap { i, t ->
            val wet = i in 14..16
            hourly(t, 17.0, if (wet) RAIN else CLOUDY, if (wet) 85 else 5, if (wet) 2.0 else 0.0, feelsLike = 17.0, wind = 10.0)
        }, now = now))
        assertTrue(ids(r).containsAll(listOf("umbrella", "waterproofShoes")))
        assertTrue(r.reasons.any { it.contains("Saat 14.00 civarında yağış") })
    }

    @Test fun largeMorningEveningDifferenceSuggestsLayers() {
        val r = assertNotNull(engine.recommend(snap { i, t ->
            val h = i % 24
            val temp = if (h < 11) 7.0 else if (h < 18) 19.0 else 9.0
            hourly(t, temp, CLEAR, 0, 0.0, feelsLike = temp, wind = 5.0)
        }, now = now))
        assertTrue(r.suggestsLayers)
        assertTrue(r.summary.contains("Katmanlı"), r.summary)
        assertTrue(r.reasons.any { it.contains("gün içinde fark büyük") })
    }

    @Test fun mildDayIsNotNoteworthy() {
        val s = snap { _, t -> hourly(t, 22.0, CLEAR, 0, 0.0, feelsLike = 22.0, wind = 8.0, uv = 3.0) }
        val r = assertNotNull(engine.recommend(s, now = now))
        assertFalse(r.isNoteworthy)
        val a = WeatherSummaryEngine().analyze(s, now)
        assertFalse(CharacterPersonalityEngine().briefing(a, clothing = r).fullText.contains("tişört"))
    }

    @Test fun noHourlyDataMeansNoRecommendation() {
        assertNull(engine.recommend(TestClock.snapshot(emptyList(), now = now), now = now))
    }

    @Test fun tomorrowAndBriefingIntegration() {
        val s = MockWeatherData.snapshot(Scenario.SNOWY, now)
        val r = assertNotNull(engine.recommend(s, TestClock.date(12, day = 15), now))
        assertTrue(r.briefingSentence.startsWith("Yarın"))
        val today = assertNotNull(engine.recommend(s, now = now))
        val a = WeatherSummaryEngine().analyze(s, now)
        assertTrue(today.briefingSentence in CharacterPersonalityEngine().briefing(a, clothing = today).sentences)
    }
}

class TravelWeatherTest {
    private val engine = TravelWeatherSummaryEngine()
    private val now = TestClock.date(10)

    private fun snapshot(place: Place, scenario: Scenario, shift: Double = 0.0, at: Instant = now): WeatherSnapshot {
        val s = MockWeatherData.snapshot(scenario, at, place)
        return s.copy(
            source = WeatherDataSource.OPEN_METEO,
            daily = s.daily.map { it.copy(minTemperature = it.minTemperature + shift, maxTemperature = it.maxTemperature + shift) },
            hourly = s.hourly.map { it.copy(temperature = it.temperature + shift) },
        )
    }

    private val istanbul get() = TurkishProvinces.search("İstanbul")[0].place
    private val bursa get() = TurkishProvinces.search("Bursa")[0].place

    @Test fun destinationResolvedFromQuestion() {
        val parser = WeatherQueryParser()
        assertEquals(WeatherTopic.TRAVEL, parser.parse("Yarın Bursa'ya gideceğim. Hava nasıl?").topic)
        assertEquals("Bursa", parser.province("Yarın Bursa'ya gideceğim")?.name)
        assertEquals(DateReference.Weekend, parser.parse("Hafta sonu Antalya'ya gitmek istiyorum. Hava uygun mu?").date)
        assertEquals("Ankara", parser.province("İstanbul'dan Ankara'ya yolculuk yapacağım")?.name)
    }

    @Test fun destinationNotFound() {
        assertNull(WeatherQueryParser().province("Yarın Atlantis'e gideceğim"))
    }

    @Test fun comparisonWithOrigin() {
        val tomorrow = TravelWeatherSummaryEngine.travelDays(TestClock.date(12, day = 15), null, TestClock.istanbul)
        val summary = engine.summarize(snapshot(bursa, Scenario.RAINY_AFTERNOON, -3.0), snapshot(istanbul, Scenario.RAINY_AFTERNOON), tomorrow, now)
        val text = summary.briefing.fullText
        assertTrue(text.startsWith("Yarın Bursa'da hava İstanbul'dan biraz daha serin olacak"), text)
        assertTrue(text.contains("Öğleden sonra yağış bekleniyor."), text)
        assertTrue(text.contains("Yanına") && text.contains("şemsiye"), text)
        assertEquals(1, summary.days.size)
    }

    @Test fun multipleTravelDates() {
        val days = TravelWeatherSummaryEngine.travelDays(TestClock.date(9, day = 15), TestClock.date(9, day = 17), TestClock.istanbul)
        assertEquals(3, days.size)
        val summary = engine.summarize(snapshot(bursa, Scenario.SUNNY), null, days, now)
        assertEquals(3, summary.days.size)
        assertTrue(summary.briefing.sentences.any { it.startsWith("Pazartesi") }, summary.briefing.fullText)
        assertTrue(summary.briefing.fullText.contains("daha belirsiz"), "Day +3 is flagged as less certain")
    }

    @Test fun missingFutureForecast() {
        val days = TravelWeatherSummaryEngine.travelDays(TestClock.date(9, day = 25), null, TestClock.istanbul)
        val summary = engine.summarize(snapshot(bursa, Scenario.SUNNY), null, days, now)
        assertTrue(summary.days.isEmpty())
        assertEquals(1, summary.unavailableDays.size)
        assertTrue(summary.briefing.fullText.contains("henüz tahmin yok"))
        assertFalse(summary.briefing.fullText.contains("derece"))
    }

    @Test fun partlyCoveredTrip() {
        val days = TravelWeatherSummaryEngine.travelDays(TestClock.date(9, day = 17), TestClock.date(9, day = 20), TestClock.istanbul)
        val summary = engine.summarize(snapshot(bursa, Scenario.CLOUDY), null, days, now)
        assertEquals(2, summary.days.size) // mock covers 5 days: 14–18
        assertEquals(2, summary.unavailableDays.size)
        assertTrue(summary.briefing.fullText.contains("2 gün için tahmin henüz yayımlanmadı"))
    }

    @Test fun differentTimeZone() {
        val london = Place("Londra", coordinate = Coordinate(51.5, -0.12), timeZoneIdentifier = "Europe/London", source = LocationSource.MANUAL)
        // 01:00 in Istanbul is still the previous day in London.
        val early = TestClock.date(1, day = 15)
        val days = TravelWeatherSummaryEngine.travelDays(early, null, ZoneId.of("Europe/London"))
        assertEquals(14, days.first().dayOfMonth)
        val dest = MockWeatherData.snapshot(Scenario.CLOUDY, early, london).copy(source = WeatherDataSource.OPEN_METEO)
        val summary = engine.summarize(dest, snapshot(istanbul, Scenario.CLOUDY), days, early)
        assertTrue(summary.briefing.fullText.contains("Londra saati İstanbul saatinden 3 saat geride"), summary.briefing.fullText)
    }

    @Test fun ablative() {
        assertEquals("İstanbul'dan", TurkishGrammar.ablative("İstanbul"))
        assertEquals("İzmir'den", TurkishGrammar.ablative("İzmir"))
        assertEquals("Kars'tan", TurkishGrammar.ablative("Kars"))
    }
}
