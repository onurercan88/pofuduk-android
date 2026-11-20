package com.pofuduk.core.provider

import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WarningAvailability
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import java.time.Instant

/** Spec: IWeatherProvider. Adapter for one external forecast service. */
interface WeatherProvider {
    val source: WeatherDataSource
    /** Maximum number of forecast days this provider/plan returns. */
    val maxForecastDays: Int
    suspend fun fetchSnapshot(place: Place): WeatherSnapshot
}

/** Spec: IWeatherRepository. Combines providers, cache and fallback. */
interface WeatherRepository {
    suspend fun snapshot(place: Place, forceRefresh: Boolean): WeatherSnapshot
}

/** Spec: ILocationProvider. */
interface LocationProvider {
    suspend fun currentPlace(): Place
}

/** Spec: IMGMWarningProvider. Only authorized official sources may implement this. */
interface MGMWarningProvider {
    suspend fun warnings(): WarningAvailability
}

/** Spec: IWeatherAlertRepository. */
interface WeatherAlertRepository {
    suspend fun warnings(place: Place, at: Instant): WarningAvailability
}

enum class WeatherError {
    NETWORK, INVALID_RESPONSE, RATE_LIMITED, MISSING_API_KEY, OUT_OF_FORECAST_RANGE, NO_DATA;

    /** Short Turkish explanation for the user. Never claims data that wasn't retrieved. */
    val turkishExplanation: String
        get() = when (this) {
            NETWORK -> "İnternet bağlantını kontrol edebilirsin."
            RATE_LIMITED -> "Hava durumu servisi şu an çok yoğun, biraz sonra tekrar deneyeceğim."
            MISSING_API_KEY -> "Hava durumu servisine bağlanmak için gerekli ayar eksik."
            INVALID_RESPONSE -> "Hava durumu servisinden anlaşılır bir yanıt alamadım."
            OUT_OF_FORECAST_RANGE -> "İstediğin tarih için tahmin henüz yayımlanmadı."
            NO_DATA -> "Bu konum için hava durumu bilgisi bulunamadı."
        }
}

class WeatherException(val error: WeatherError) : Exception(error.name)
