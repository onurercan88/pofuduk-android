package com.pofuduk.core.alert

import com.pofuduk.core.data.TurkishProvinces
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WarningAvailability
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.provider.HttpClient
import com.pofuduk.core.provider.MGMWarningProvider
import com.pofuduk.core.provider.WeatherAlertRepository
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Selects the official warnings that apply to a place right now.
 * Green means "no warning" and is never shown as one.
 */
class WeatherAlertMatcher {
    fun matching(warnings: List<OfficialWarning>, place: Place, at: Instant): List<OfficialWarning> {
        val province = TurkishProvinces.normalize(place.province ?: place.name)
        val district = place.district?.let(TurkishProvinces::normalize)
        return warnings
            .filter { it.isActive(at) && it.level >= WarningLevel.YELLOW }
            .filter { w ->
                if (province !in w.provinces.map(TurkishProvinces::normalize)) return@filter false
                // A district-specific warning only matches when we know the user's district and it's listed.
                if (w.districts.isEmpty() || district == null) return@filter true
                district in w.districts.map(TurkishProvinces::normalize)
            }
            .sortedByDescending { it.level }
    }
}

/** Official MGM page shown whenever automated warnings are unavailable (verified 2026-11-14). */
object MGMOfficialPage {
    const val WARNINGS = "https://www.mgm.gov.tr/meteouyari/turkiye.aspx"
}

/**
 * MGM provider. MGM publishes no documented public warning API/feed (checked 2026-11-14) and its
 * legal notice forbids reuse without prior permission. So this provider stays *unavailable* unless a
 * feed URL obtained through written authorization is configured. Never scrapes pages.
 */
class ConfiguredMGMWarningProvider(private val feedUrl: String?, private val http: HttpClient) : MGMWarningProvider {
    override suspend fun warnings(): WarningAvailability {
        val url = feedUrl ?: return WarningAvailability.Unavailable(UNAVAILABLE_REASON, MGMOfficialPage.WARNINGS)
        val response = try {
            http.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val warnings = response?.takeIf { it.statusCode == 200 }?.let { CAPWarningParser.parse(it.body) }
            ?: return WarningAvailability.Unavailable("MGM uyarılarına şu an ulaşılamadı.", MGMOfficialPage.WARNINGS)
        return WarningAvailability.Available(warnings)
    }

    companion object {
        const val UNAVAILABLE_REASON = "MGM resmi uyarıları için yetkili bir veri bağlantısı henüz yok."
    }
}

/** Spec: IWeatherAlertRepository — provider + matching for a place. */
class MGMAlertRepository(private val provider: MGMWarningProvider) : WeatherAlertRepository {
    private val matcher = WeatherAlertMatcher()

    override suspend fun warnings(place: Place, at: Instant): WarningAvailability = when (val r = provider.warnings()) {
        is WarningAvailability.Available -> WarningAvailability.Available(matcher.matching(r.warnings, place, at))
        is WarningAvailability.Unavailable -> r
    }
}
