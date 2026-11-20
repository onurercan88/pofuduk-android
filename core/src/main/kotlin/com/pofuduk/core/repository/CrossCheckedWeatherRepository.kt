package com.pofuduk.core.repository

import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.provider.CurrentConditionsObservation
import com.pofuduk.core.provider.CurrentConditionsProvider
import com.pofuduk.core.provider.CurrentConditionsReconciler
import com.pofuduk.core.provider.WeatherRepository
import com.pofuduk.core.util.secondsSince
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Wraps the forecast repository and cross-checks "now" against a second source (port of the iOS repository).
 * Best effort: failures, timeouts, demo and stale data leave the snapshot unchanged, and it is skipped when the
 * forecast already comes from the checking source. Observations are reused for [observationTtlSeconds] per ~1 km cell.
 */
class CrossCheckedWeatherRepository(
    private val base: WeatherRepository,
    private val checker: CurrentConditionsProvider,
    private val timeoutMillis: Long = 5_000,
    private val observationTtlSeconds: Double = 10.0 * 60,
    private val now: () -> Instant = { Instant.now() },
) : WeatherRepository {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<CurrentConditionsObservation, Instant>>()

    override suspend fun snapshot(place: Place, forceRefresh: Boolean): WeatherSnapshot {
        val snapshot = base.snapshot(place, forceRefresh)
        val current = now()
        if (snapshot.source == WeatherDataSource.DEMO || snapshot.source == checker.source || snapshot.isStale(current)) return snapshot
        val observation = observation(place, current) ?: return snapshot
        return snapshot.copy(
            current = CurrentConditionsReconciler.reconcile(snapshot.current, observation, current),
            crossCheckSource = checker.source,
        )
    }

    private suspend fun observation(place: Place, at: Instant): CurrentConditionsObservation? {
        val key = CachedWeatherRepository.key(place)
        mutex.withLock { cache[key] }?.let { (obs, storedAt) -> if (at.secondsSince(storedAt) < observationTtlSeconds) return obs }
        val fetched = try {
            withTimeoutOrNull(timeoutMillis) { checker.currentConditions(place) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fetched != null) mutex.withLock { cache[key] = fetched to at }
        return fetched
    }
}
