package com.pofuduk.core.engine

import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.provider.WeatherProvider
import kotlinx.coroutines.CancellationException

/** Tries providers in priority order and returns the first successful snapshot. */
class WeatherProviderFallbackEngine(val providers: List<WeatherProvider>) {

    data class Outcome(
        val snapshot: WeatherSnapshot,
        /** Providers that failed before the successful one (for diagnostics; contains no secrets). */
        val failures: List<Pair<WeatherDataSource, WeatherError>>,
    )

    suspend fun fetch(place: Place): Outcome {
        val failures = mutableListOf<Pair<WeatherDataSource, WeatherError>>()
        for (provider in providers) {
            try {
                return Outcome(provider.fetchSnapshot(place), failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += provider.source to ((e as? WeatherException)?.error ?: WeatherError.NETWORK)
            }
        }
        // Report the most informative error: a configuration/rate problem beats a generic network failure.
        val errors = failures.map { it.second }
        val error = errors.firstOrNull { it != WeatherError.NETWORK && it != WeatherError.MISSING_API_KEY }
            ?: errors.lastOrNull() ?: WeatherError.NO_DATA
        throw WeatherException(error)
    }
}
