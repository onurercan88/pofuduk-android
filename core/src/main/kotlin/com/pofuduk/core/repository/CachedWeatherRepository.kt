package com.pofuduk.core.repository

import com.pofuduk.core.engine.WeatherProviderFallbackEngine
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.provider.WeatherRepository
import com.pofuduk.core.store.CoreJson
import com.pofuduk.core.util.secondsSince
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale

/** Persists the last successful snapshot per location. */
interface WeatherCacheStore {
    suspend fun load(key: String): WeatherSnapshot?
    suspend fun save(snapshot: WeatherSnapshot, key: String)
    suspend fun removeAll()
}

class InMemoryWeatherCacheStore : WeatherCacheStore {
    private val mutex = Mutex()
    private val storage = mutableMapOf<String, WeatherSnapshot>()
    override suspend fun load(key: String) = mutex.withLock { storage[key] }
    override suspend fun save(snapshot: WeatherSnapshot, key: String) = mutex.withLock { storage[key] = snapshot }
    override suspend fun removeAll() = mutex.withLock { storage.clear() }
}

/** JSON files in a cache directory. Contains only forecast data + the place the user selected. */
class FileWeatherCacheStore(private val directory: File) : WeatherCacheStore {
    private val mutex = Mutex()

    private fun file(key: String) = File(directory, "forecast-$key.json")

    override suspend fun load(key: String): WeatherSnapshot? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { CoreJson.decodeFromString(WeatherSnapshot.serializer(), file(key).readText()) }.getOrNull()
        }
    }

    override suspend fun save(snapshot: WeatherSnapshot, key: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val tmp = File(directory, "forecast-$key.json.tmp")
                tmp.writeText(CoreJson.encodeToString(WeatherSnapshot.serializer(), snapshot))
                if (!tmp.renameTo(file(key))) {
                    file(key).delete()
                    tmp.renameTo(file(key))
                }
            }
            Unit
        }
    }

    override suspend fun removeAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            directory.listFiles()?.filter { it.name.startsWith("forecast-") }?.forEach { it.delete() }
            Unit
        }
    }
}

/**
 * Fresh cache → returned without network. Otherwise fetch via fallback providers.
 * If every provider fails, a cached snapshot younger than [maxStaleAgeSeconds] is returned — it stays marked
 * stale by its `expiresAt`, so the UI and briefing disclose it. Never presents old data as live.
 */
class CachedWeatherRepository(
    private val engine: WeatherProviderFallbackEngine,
    private val cache: WeatherCacheStore,
    private val maxStaleAgeSeconds: Double = 6.0 * 3600,
    private val now: () -> Instant = { Instant.now() },
) : WeatherRepository {

    override suspend fun snapshot(place: Place, forceRefresh: Boolean): WeatherSnapshot {
        val key = key(place)
        val cached = cache.load(key)
        val current = now()
        // A cache entry "from the future" (device clock moved back) is never treated as fresh.
        val fromFuture = cached != null && cached.retrievedAt.isAfter(current.plusSeconds(CLOCK_SKEW_SECONDS))
        if (!forceRefresh && cached != null && !fromFuture && !cached.isStale(current)) return cached.copy(place = place)
        return try {
            val outcome = engine.fetch(place)
            cache.save(outcome.snapshot, key)
            outcome.snapshot
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (cached != null && !fromFuture && current.secondsSince(cached.retrievedAt) < maxStaleAgeSeconds) {
                cached.copy(place = place) // keeps provenance, shows the currently selected place name
            } else {
                throw e
            }
        }
    }

    companion object {
        /** Tolerated difference between the clock that stored an entry and the current clock. */
        const val CLOCK_SKEW_SECONDS = 5L * 60

        /** ~1 km grid so nearby requests share a cache entry. */
        fun key(place: Place): String =
            String.format(Locale.ROOT, "%.2f_%.2f", place.coordinate.latitude, place.coordinate.longitude)
    }
}
