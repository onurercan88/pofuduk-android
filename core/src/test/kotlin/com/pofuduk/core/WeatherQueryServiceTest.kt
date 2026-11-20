package com.pofuduk.core

import com.pofuduk.core.conversation.ConversationManager
import com.pofuduk.core.conversation.WeatherQueryService
import com.pofuduk.core.data.TurkishProvinces
import com.pofuduk.core.mock.DemoWeatherRepository
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.provider.WeatherRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live-like repository: mock data labelled as Open-Meteo; records which places were asked for. */
private class RecordingRepository(private val failFor: String? = null) : WeatherRepository {
    val requested = mutableListOf<String>()
    override suspend fun snapshot(place: Place, forceRefresh: Boolean): WeatherSnapshot {
        requested += place.name
        if (place.name == failFor) throw WeatherException(WeatherError.NETWORK)
        val shift = if (place.name == "Bursa") -3.0 else 0.0
        val s = MockWeatherData.snapshot(MockWeatherData.Scenario.RAINY_AFTERNOON, TestClock.date(10), place)
        return s.copy(
            source = WeatherDataSource.OPEN_METEO,
            daily = s.daily.map { it.copy(minTemperature = it.minTemperature + shift, maxTemperature = it.maxTemperature + shift) },
        )
    }
}

class WeatherQueryServiceTest {
    private val now = TestClock.date(10)
    private val istanbul = TurkishProvinces.search("İstanbul")[0].place

    private suspend fun ask(service: WeatherQueryService, text: String, current: Place? = istanbul) =
        service.answer(ConversationManager().interpret(text, now), current)

    @Test fun questionAboutAnotherProvinceFetchesThatPlace() = runTest {
        val repo = RecordingRepository()
        val text = ask(WeatherQueryService(repo) { now }, "Yarın İzmir'de hava nasıl?").fullText
        assertEquals(listOf("İzmir"), repo.requested)
        assertTrue(text.startsWith("Yarın İzmir'de"), text)
    }

    @Test fun travelComparesDestinationWithCurrentPlace() = runTest {
        val repo = RecordingRepository()
        val text = ask(WeatherQueryService(repo) { now }, "Yarın Bursa'ya gideceğim. Hava nasıl?").fullText
        assertEquals(listOf("Bursa", "İstanbul"), repo.requested)
        assertTrue(text.startsWith("Yarın Bursa'da hava İstanbul'dan biraz daha serin olacak"), text)
    }

    @Test fun originFailureDoesNotBlockTravelAnswer() = runTest {
        val text = ask(WeatherQueryService(RecordingRepository(failFor = "İstanbul")) { now }, "Yarın Bursa'ya gideceğim").fullText
        assertTrue(text.startsWith("Yarın Bursa'da hava"), text)
        assertFalse(text.contains("İstanbul'dan"), text)
    }

    @Test fun travelWithoutDestinationAsksWhere() = runTest {
        val b = ask(WeatherQueryService(RecordingRepository()) { now }, "Yarın seyahate çıkacağım")
        assertTrue(b.fullText.startsWith("Nereye gideceğini anlayamadım."), b.fullText)
        assertEquals(CharacterMood.THINKING, b.restingMood)
    }

    @Test fun noPlaceKnownAsksForOne() = runTest {
        val b = ask(WeatherQueryService(RecordingRepository()) { now }, "Yarın hava nasıl?", current = null)
        assertTrue(b.fullText.startsWith("Önce bir konum seçmen gerekiyor."), b.fullText)
    }

    @Test fun networkErrorIsHonest() = runTest {
        val b = ask(WeatherQueryService(RecordingRepository(failFor = "İzmir")) { now }, "İzmir'de yağmur var mı?")
        assertEquals(CharacterMood.ERROR, b.restingMood)
        assertTrue(b.fullText.contains("İnternet bağlantını"), b.fullText)
    }

    @Test fun demoAnswersAreLabelled() = runTest {
        val b = ask(WeatherQueryService(DemoWeatherRepository(MockWeatherData.Scenario.SUNNY) { now }) { now }, "Yarın hava nasıl?")
        assertEquals("Bu cevap örnek verilere dayanıyor.", b.sentences.last())
    }
}
