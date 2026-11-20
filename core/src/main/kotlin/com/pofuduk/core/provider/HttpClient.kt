package com.pofuduk.core.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(val body: ByteArray, val statusCode: Int) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** Minimal HTTP abstraction so providers are testable with canned responses. */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/**
 * java.net based client (works on the JVM and on Android without extra dependencies).
 * No caching, no cookies. One retry after a short pause on transport failures (flaky mobile networks, cold DNS);
 * [retry] = false for sources that have a fallback, so a dead primary doesn't delay the fallback.
 * Never logs or rethrows the URL: it may carry an API key in future providers.
 */
class UrlConnectionHttpClient(private val timeoutMillis: Int = 15_000, private val retry: Boolean = true) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = try {
        attempt(url, headers)
    } catch (e: WeatherException) {
        if (!retry) throw e
        delay(1_000)
        attempt(url, headers)
    }

    private suspend fun attempt(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw WeatherException(WeatherError.NETWORK)
        }
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(stream?.use { it.readBytes() } ?: ByteArray(0), status)
        } catch (e: IOException) {
            throw WeatherException(WeatherError.NETWORK)
        } finally {
            connection.disconnect()
        }
    }
}
