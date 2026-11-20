package com.pofuduk.app

import android.app.Application
import android.content.Context
import com.pofuduk.app.background.RefreshWorker
import com.pofuduk.app.data.SharedPreferencesStore
import com.pofuduk.app.location.DeviceLocationProvider
import com.pofuduk.app.location.LocationService
import com.pofuduk.app.notifications.NotificationChannels
import com.pofuduk.app.notifications.NotificationCoordinator
import com.pofuduk.core.engine.WeatherProviderFallbackEngine
import com.pofuduk.core.model.UserPreferencesStore
import com.pofuduk.core.provider.OpenMeteoProvider
import com.pofuduk.core.provider.UrlConnectionHttpClient
import com.pofuduk.core.provider.WeatherKitProxyProvider
import com.pofuduk.core.provider.WeatherRepository
import com.pofuduk.core.repository.CachedWeatherRepository
import com.pofuduk.core.repository.CrossCheckedWeatherRepository
import com.pofuduk.core.repository.FileWeatherCacheStore
import java.io.File

/** Shared dependencies for the UI and background work (one instance per process). */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val keyValue = SharedPreferencesStore(app)
    val location = LocationService(DeviceLocationProvider(app), keyValue)
    val cache = FileWeatherCacheStore(File(app.cacheDir, "forecasts"))

    /** Apple Weather through Pofuduk's proxy when configured (see app/build.gradle.kts), Open-Meteo as fallback. */
    val appleWeatherEnabled = BuildConfig.WEATHERKIT_PROXY_URL.isNotBlank()

    val repository: WeatherRepository = run {
        fun appleWeather(timeoutMillis: Int) = WeatherKitProxyProvider(
            UrlConnectionHttpClient(timeoutMillis, retry = false), BuildConfig.WEATHERKIT_PROXY_URL, BuildConfig.WEATHERKIT_APP_KEY,
        )
        val providers = listOfNotNull(
            if (appleWeatherEnabled) appleWeather(6_000) else null,
            OpenMeteoProvider(UrlConnectionHttpClient()),
        )
        val cached = CachedWeatherRepository(WeatherProviderFallbackEngine(providers), cache)
        // When the forecast fell back to Open-Meteo, "now" is still cross-checked against Apple Weather (as on iOS).
        if (appleWeatherEnabled) CrossCheckedWeatherRepository(cached, appleWeather(4_000)) else cached
    }
    val userPreferences = UserPreferencesStore(keyValue)
    val notifications = NotificationCoordinator(app, keyValue)
}

class PofudukApplication : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        RefreshWorker.schedule(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as PofudukApplication).graph
