package com.pofuduk.core.conversation

import com.pofuduk.core.datetime.ForecastDateResolver
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.provider.WeatherRepository
import com.pofuduk.core.util.localDate
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Fetches the forecast for the place a question refers to and builds the spoken answer
 * (port of the iOS app's WeatherQueryService + TravelWeatherService, moved to core so it is testable).
 */
class WeatherQueryService(
    private val repository: WeatherRepository,
    private val now: () -> Instant = { Instant.now() },
) {
    private val answers = WeatherAnswerEngine()
    private val travel = TravelWeatherService(repository, now)
    private val personality = CharacterPersonalityEngine()

    suspend fun answer(query: ResolvedQuery, currentPlace: Place?): Briefing {
        if (query.topic == WeatherTopic.TRAVEL) return travelAnswer(query, currentPlace)
        val place = query.province?.place ?: currentPlace ?: return Briefing(
            listOf("Önce bir konum seçmen gerekiyor.", "İstersen sorunda bir il adı da söyleyebilirsin."),
            CharacterMood.IDLE, WeatherTheme.CLOUDY,
        )
        return try {
            val snapshot = repository.snapshot(place, forceRefresh = false)
            withDemoNote(answers.answer(query, snapshot, now()), snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable(e)
        }
    }

    /** "Yarın Bursa'ya gideceğim. Hava nasıl?" → destination forecast compared with the current place. */
    private suspend fun travelAnswer(query: ResolvedQuery, currentPlace: Place?): Briefing {
        val destination = query.province?.place ?: return Briefing(
            listOf(
                "Nereye gideceğini anlayamadım.",
                "Örneğin \"Yarın Bursa'ya gideceğim\" diyebilirsin.",
            ),
            CharacterMood.THINKING, WeatherTheme.CLOUDY,
        )
        val zone = destination.timeZone
        val days = ForecastDateResolver().days(query.date, now(), zone).map { it.localDate(zone) }
        return when (val r = travel.summary(destination, days, currentPlace)) {
            is TravelWeatherService.Result.Success -> r.summary.briefing
            is TravelWeatherService.Result.Failure -> personality.unavailableBriefing(r.error.turkishExplanation)
        }
    }

    private fun withDemoNote(b: Briefing, snapshot: WeatherSnapshot): Briefing =
        if (snapshot.source == WeatherDataSource.DEMO) b.copy(sentences = b.sentences + "Bu cevap örnek verilere dayanıyor.") else b

    private fun unavailable(e: Exception): Briefing =
        personality.unavailableBriefing(((e as? WeatherException)?.error ?: WeatherError.NETWORK).turkishExplanation)
}
