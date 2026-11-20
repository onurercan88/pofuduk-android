package com.pofuduk.core.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Minimal on-device key/value storage (Android: SharedPreferences; tests: in memory). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

internal val CoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** One JSON value under one key. Corrupt or outdated data reads as null (callers fall back to defaults). */
class JsonStore<T>(private val store: KeyValueStore, private val key: String, private val serializer: KSerializer<T>) {
    fun load(): T? = store.getString(key)?.let { runCatching { CoreJson.decodeFromString(serializer, it) }.getOrNull() }
    fun save(value: T) = store.putString(key, CoreJson.encodeToString(serializer, value))
    fun clear() = store.remove(key)
}
