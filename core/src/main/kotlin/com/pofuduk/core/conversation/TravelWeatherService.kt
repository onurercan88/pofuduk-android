package com.pofuduk.core.conversation

import com.pofuduk.core.engine.TravelSummary
import com.pofuduk.core.engine.TravelWeatherSummaryEngine
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.provider.WeatherRepository
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate

/**
 * Fetches destination (and origin) forecasts and builds the travel summary (port of the iOS app's TravelWeatherService).
 * The origin is optional context: its failure never blocks the destination forecast.
 */
class TravelWeatherService(
    private val repository: WeatherRepository,
    private val now: () -> Instant = { Instant.now() },
) {
    private val engine = TravelWeatherSummaryEngine()

    sealed interface Result {
        data class Success(val summary: TravelSummary) : Result
        data class Failure(val error: WeatherError) : Result
    }

    /** Departure → return (inclusive, capped at 7 days) in the destination's time zone. */
    suspend fun summary(destination: Place, departure: Instant, returnDate: Instant?, origin: Place?): Result =
        summary(destination, TravelWeatherSummaryEngine.travelDays(departure, returnDate, destination.timeZone), origin)

    suspend fun summary(destination: Place, days: List<LocalDate>, origin: Place?): Result {
        val destinationSnapshot = try {
            repository.snapshot(destination, forceRefresh = false).copy(place = destination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Failure((e as? WeatherException)?.error ?: WeatherError.NETWORK)
        }
        val originSnapshot = origin?.takeIf { it.coordinate != destination.coordinate }?.let {
            try {
                repository.snapshot(it, forceRefresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        var summary = engine.summarize(destinationSnapshot, originSnapshot, days, now())
        if (destinationSnapshot.source == WeatherDataSource.DEMO) {
            summary = summary.copy(briefing = summary.briefing.copy(sentences = summary.briefing.sentences + "Bu cevap örnek verilere dayanıyor."))
        }
        return Result.Success(summary)
    }
}
