package com.pofuduk.core.notification

import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.datetime.TurkishGrammar
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.ClothingRecommendation
import com.pofuduk.core.engine.PrecipitationIntensity
import com.pofuduk.core.engine.PrecipitationKind
import com.pofuduk.core.engine.WeatherAnalysis
import com.pofuduk.core.engine.WeatherThresholds
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.store.JsonStore
import com.pofuduk.core.store.KeyValueStore
import com.pofuduk.core.util.capitalizedFirstTurkish
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.isSameDay
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.secondsSince
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** "Pofuduk Günaydın" settings, stored on device. */
@Serializable
data class MorningBriefingSettings(
    val isEnabled: Boolean = true,
    /** Local minute of day (default 07:30). */
    val minuteOfDay: Int = 7 * 60 + 30,
    /** Speak automatically when the app is opened in the morning (otherwise text only). */
    val speaksAutomatically: Boolean = true,
    /** Also send a text notification at the chosen time (needs notification permission). */
    val sendsNotification: Boolean = false,
    /** "yyyy-MM-dd" of the last morning greeting shown, so it happens once per day. */
    val lastGreetedDay: String? = null,
) {
    companion object {
        val presets = listOf(7 * 60, 7 * 60 + 30, 8 * 60, 8 * 60 + 30)
    }
}

class MorningBriefingStore(store: KeyValueStore) {
    private val json = JsonStore(store, "pofuduk.morningBriefing", MorningBriefingSettings.serializer())
    fun load(): MorningBriefingSettings = json.load() ?: MorningBriefingSettings()
    fun save(s: MorningBriefingSettings) = json.save(s)
}

/** Builds the morning greeting/briefing and its optional notification. All facts come from the snapshot. */
class MorningBriefingEngine(
    val thresholds: WeatherThresholds = WeatherThresholds.DEFAULT,
    /** Morning greeting window ends at this local hour. */
    val morningEndsHour: Int = 12,
    /** A briefing notification is only scheduled if the forecast is at most this old at delivery time (seconds). */
    val maxForecastAgeAtDeliverySeconds: Double = 18.0 * 3600,
) {
    /** True between the chosen time and noon, once per day. */
    fun shouldGreet(now: Instant, settings: MorningBriefingSettings, zone: ZoneId): Boolean {
        if (!settings.isEnabled || settings.lastGreetedDay == dayKey(now, zone)) return false
        val z = now.atZone(zone)
        val minute = z.hour * 60 + z.minute
        return minute >= settings.minuteOfDay && minute < morningEndsHour * 60
    }

    // Spoken briefing

    fun briefing(
        snapshot: WeatherSnapshot,
        analysis: WeatherAnalysis,
        warnings: List<OfficialWarning> = emptyList(),
        clothing: ClothingRecommendation?,
        now: Instant,
    ): Briefing {
        val zone = snapshot.place.timeZone
        val active = warnings.filter { it.isActive(now) && it.level > WarningLevel.GREEN }.sortedByDescending { it.level }
        val serious = (active.firstOrNull()?.level ?: WarningLevel.GREEN) >= WarningLevel.ORANGE ||
            analysis.outlookTheme == WeatherTheme.STORMY
        val p = analysis.precipitation
        val s = mutableListOf<String>()

        s += if (serious) "Günaydın." else "Günaydın!"
        if (!serious && p == null && (analysis.outlookTheme == WeatherTheme.SUNNY || analysis.outlookTheme == WeatherTheme.CLOUDY) &&
            analysis.wind?.isStrong != true
        ) {
            s += "Bugün senin için güzel bir gün olabilir."
        }
        if (snapshot.source == WeatherDataSource.DEMO) s += "Şu an örnek verilerle konuşuyorum, bu gerçek bir tahmin değil."
        if (analysis.isStale) {
            s += "Bu bilgiler saat ${TurkishFormatting.clockTime(analysis.retrievedAt, zone)} itibarıyla alındı, güncel olmayabilir."
        }
        for (w in active.take(2)) {
            s += "Dikkat! Bulunduğun bölge için MGM tarafından ${w.level.turkishName} seviyede meteorolojik uyarı yayımlanmış."
            s += "${w.hazard} bekleniyor. Lütfen resmi uyarının detaylarını incelemeyi unutma."
        }

        // Temperature arc: morning start → afternoon peak.
        val today = snapshot.hourly.filter { it.time.isSameDay(now, zone) }
        val nowHour = now.hour(zone)
        val morning = today.filter { it.time.hour(zone) >= maxOf(nowHour, 7) && it.time.hour(zone) <= 9 }.map { it.temperature }
        val afternoon = today.filter { it.time.hour(zone) in 12..17 }.map { it.temperature }
        val place = TurkishGrammar.locative(snapshot.place.name)
        if (nowHour < 10 && morning.isNotEmpty()) {
            s += "$place sabah ${TurkishFormatting.temperature(morning.first())}yle başlıyoruz."
        } else {
            s += "$place şu an ${TurkishFormatting.temperature(analysis.current.temperature)}."
        }
        val peak = afternoon.maxOrNull()
        if (peak != null) {
            val start = morning.firstOrNull() ?: analysis.current.temperature
            s += if (peak - start >= 2) "Öğleden sonra sıcaklık ${TurkishFormatting.temperature(peak)}ye çıkıyor."
            else "Gün boyunca sıcaklık ${TurkishFormatting.temperature(peak)} civarında."
        }

        // Rain.
        if (p != null) {
            val part = dayPartPhrase(p.start.hour(zone))
            val noun = when (p.kind) {
                PrecipitationKind.SNOW -> "kar"
                PrecipitationKind.THUNDERSTORM -> "gök gürültülü sağanak"
                else -> "yağmur"
            }
            if (p.isLikely) {
                s += "Ancak $part $noun bekleniyor."
                if (p.intensity == PrecipitationIntensity.HEAVY) s += "Yağışın yer yer kuvvetli olması bekleniyor."
            } else {
                s += "${part.capitalizedFirstTurkish()} $noun ihtimali var, ama kesin değil."
            }
        } else if (analysis.hasHourlyCoverage) {
            s += "Gün boyu yağış beklenmiyor."
        }

        analysis.wind?.takeIf { it.isStrong }?.let { s += CharacterPersonalityEngine().windSentence(it) }
        if (analysis.isHighUV && analysis.uvMax != null) {
            s += "Öğle saatlerinde UV indeksi ${analysis.uvMax.roundedInt()} seviyesine çıkıyor, güneş kremini unutma."
        }
        if (clothing != null && clothing.isNoteworthy) s += clothing.briefingSentence

        s += when {
            serious -> "Lütfen dikkatli ol ve gereksiz yere dışarı çıkma."
            p?.isLikely == true -> if (p.kind == PrecipitationKind.SNOW) "Dışarı çıkacaksan sıkı giyin!" else "Dışarı çıkacaksan şemsiyeni hazırla!"
            else -> "Güzel bir gün geçirmeni dilerim!"
        }

        return Briefing(s, if (serious) CharacterMood.WARNING else CharacterMood.HAPPY, analysis.theme)
    }

    // Notification

    /** Next morning-briefing notification (today if the time hasn't passed, else tomorrow), from daily data. */
    fun notification(snapshot: WeatherSnapshot, settings: MorningBriefingSettings, now: Instant): PlannedNotification? {
        if (!settings.isEnabled || !settings.sendsNotification) return null
        val zone = snapshot.place.timeZone
        var fire = now.localDate(zone).atStartOfDay(zone).plusMinutes(settings.minuteOfDay.toLong())
        if (!fire.toInstant().isAfter(now)) fire = fire.plusDays(1)
        val fireInstant = fire.toInstant()
        // Too old by delivery time → don't promise anything.
        if (fireInstant.secondsSince(snapshot.retrievedAt) > maxForecastAgeAtDeliverySeconds) return null
        val day = snapshot.daily.firstOrNull { it.date.isSameDay(fireInstant, zone) } ?: return null

        var body = "Bugün ${TurkishFormatting.temperature(day.minTemperature).replace(" derece", "")} ile " +
            "${TurkishFormatting.temperature(day.maxTemperature)} arası, ${TurkishFormatting.conditionName(day.condition)}."
        val prob = day.precipitationProbability
        if (prob != null && prob >= thresholds.possiblePrecipitationProbability) body += " Yağış ihtimali %$prob."
        body += " Ayrıntılar için Pofuduk'u aç. Kaynak: ${snapshot.source.attribution} tahmini, " +
            "${TurkishFormatting.clockTime(snapshot.retrievedAt, zone)} itibarıyla."
        return PlannedNotification(
            "morningBriefing-${dayKey(fireInstant, zone)}", NotificationCategory.MORNING_BRIEFING,
            fireInstant, "Günaydın! ☀️", body, false,
        )
    }

    companion object {
        fun dayKey(instant: Instant, zone: ZoneId): String {
            val d = instant.localDate(zone)
            return String.format(Locale.ROOT, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
        }

        fun dayPartPhrase(hour: Int): String = when (hour) {
            in 5..10 -> "sabah saatlerinde"
            in 11..13 -> "öğle saatlerinde"
            in 14..17 -> "öğleden sonra"
            in 18..21 -> "akşam"
            else -> "gece"
        }
    }
}
