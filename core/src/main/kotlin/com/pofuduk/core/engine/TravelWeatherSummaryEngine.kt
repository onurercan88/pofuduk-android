package com.pofuduk.core.engine

import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.datetime.TurkishGrammar
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.notification.MorningBriefingEngine
import com.pofuduk.core.util.capitalizedFirstTurkish
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.isSameDay
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.startInstant
import com.pofuduk.core.util.startOfHour
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class TravelDayForecast(
    /** Local midnight in the destination's time zone. */
    val date: Instant,
    val minTemperature: Double,
    val maxTemperature: Double,
    val condition: WeatherCondition,
    val precipitationProbability: Int?,
    /** Same calendar day at the origin, when available. */
    val originMaxTemperature: Double?,
)

data class TravelSummary(
    val destination: Place,
    val origin: Place?,
    val days: List<TravelDayForecast>,
    /** Requested days the provider doesn't cover yet. */
    val unavailableDays: List<LocalDate>,
    val packing: List<ClothingItem>,
    val briefing: Briefing,
)

/**
 * "Seyahat Hava Durumu": destination forecast for the travel days, compared with the origin.
 * Uses forecast data only; never claims road or route conditions.
 */
class TravelWeatherSummaryEngine(
    val thresholds: WeatherThresholds = WeatherThresholds.DEFAULT,
    /** Days ahead from which forecasts are flagged as less certain. */
    val uncertainFromDayOffset: Int = 3,
) {
    fun summarize(destination: WeatherSnapshot, origin: WeatherSnapshot?, days: List<LocalDate>, now: Instant): TravelSummary {
        val dest = destination.place
        val zone = dest.timeZone
        val originZone = origin?.place?.timeZone ?: zone

        val rows = mutableListOf<TravelDayForecast>()
        val missing = mutableListOf<LocalDate>()
        for (c in days) {
            val date = c.startInstant(zone)
            val daily = destination.daily.firstOrNull { it.date.isSameDay(date, zone) }
            if (daily == null) {
                missing += c; continue
            }
            val originMax = origin?.daily?.firstOrNull { it.date.localDate(originZone) == c }?.maxTemperature
            rows += TravelDayForecast(
                date, daily.minTemperature, daily.maxTemperature, daily.condition, daily.precipitationProbability,
                originMaxTemperature = if (dest == origin?.place) null else originMax,
            )
        }

        val s = mutableListOf<String>()
        val packing = mutableListOf<ClothingItem>()
        val destLoc = TurkishGrammar.locative(dest.name)

        val first = rows.firstOrNull()
        if (first == null) {
            val maxDays = maxOf(destination.daily.size, 1)
            s += "Bu tarihler için $destLoc henüz tahmin yok."
            s += "Şu an en fazla $maxDays gün ileriyi görebiliyorum; yolculuğa yaklaştıkça tekrar sor."
            return TravelSummary(dest, origin?.place, emptyList(), missing, emptyList(), Briefing(s, CharacterMood.SAD, WeatherTheme.CLOUDY))
        }

        // First day: conditions + comparison with origin.
        val label = dayLabel(first.date, now, zone)
        val range = "${first.minTemperature.roundedInt()} ile ${TurkishFormatting.temperature(first.maxTemperature)}"
        val o = origin?.place
        val originMax = first.originMaxTemperature
        if (o != null && originMax != null) {
            val diff = first.maxTemperature - originMax
            val comparison = when {
                diff < -5 -> "${TurkishGrammar.ablative(o.name)} belirgin şekilde daha serin"
                diff < -1.5 -> "${TurkishGrammar.ablative(o.name)} biraz daha serin"
                diff >= 1.5 && diff < 5 -> "${TurkishGrammar.ablative(o.name)} biraz daha sıcak"
                diff >= 5 -> "${TurkishGrammar.ablative(o.name)} belirgin şekilde daha sıcak"
                else -> "${o.name} ile benzer"
            }
            s += "$label $destLoc hava $comparison olacak; sıcaklık $range arasında, hava ${TurkishFormatting.conditionName(first.condition)}."
        } else {
            s += "$label $destLoc hava ${TurkishFormatting.conditionName(first.condition)}; sıcaklık $range arasında olacak."
        }

        // Precipitation on the first day (hourly if available, else daily probability).
        val hourStart = now.startOfHour(zone)
        val firstHours = destination.hourly.filter { it.time.isSameDay(first.date, zone) && !it.time.isBefore(hourStart) }
        val p = WeatherSummaryEngine(thresholds).precipitationOutlook(firstHours)
        val firstProb = first.precipitationProbability
        when {
            p != null -> {
                val part = MorningBriefingEngine.dayPartPhrase(p.start.hour(zone)).capitalizedFirstTurkish()
                val noun = when (p.kind) {
                    PrecipitationKind.SNOW -> "kar"
                    PrecipitationKind.THUNDERSTORM -> "gök gürültülü sağanak"
                    else -> "yağış"
                }
                s += if (p.isLikely) "$part $noun bekleniyor." else "$part $noun ihtimali var."
            }
            firstHours.isNotEmpty() -> s += "Yağış beklenmiyor."
            firstProb != null && firstProb >= thresholds.possiblePrecipitationProbability -> s += "Yağış ihtimali yüzde $firstProb."
        }
        val wind = firstHours.mapNotNull { it.windSpeedKmh }.maxOrNull()
        if (wind != null && wind >= thresholds.strongWindKmh) s += "Rüzgâr saatte ${wind.roundedInt()} kilometreye kadar çıkabilir."

        // Further days, one short sentence each.
        for (row in rows.drop(1)) {
            var t = "${dayLabel(row.date, now, zone)} ${row.minTemperature.roundedInt()} ile " +
                "${TurkishFormatting.temperature(row.maxTemperature)} arası, ${TurkishFormatting.conditionName(row.condition)}"
            val rp = row.precipitationProbability
            if (rp != null && rp >= thresholds.possiblePrecipitationProbability) t += ", yağış ihtimali yüzde $rp"
            s += "$t."
        }

        // Packing advice from the clothing engine across all travel days.
        val clothing = ClothingRecommendationEngine(thresholds = thresholds)
        for (row in rows) {
            val rec = clothing.recommend(destination, row.date.plusSeconds(12 * 3600), now)
            if (rec != null) {
                packing += rec.items
            } else if (row.maxTemperature < 15) {
                packing += if (row.minTemperature < 10) ClothingItem.coat else ClothingItem.lightJacket
            }
            if ((row.precipitationProbability ?: 0) >= thresholds.likelyPrecipitationProbability) packing += ClothingItem.umbrella
        }
        val uniquePacking = packing.distinctBy { it.id }
        packingSentence(uniquePacking)?.let { s += it }

        if (missing.isNotEmpty()) s += "${missing.size} gün için tahmin henüz yayımlanmadı."
        val today = now.localDate(zone)
        val maxOffset = rows.maxOf { ChronoUnit.DAYS.between(today, it.date.localDate(zone)) }
        if (maxOffset >= uncertainFromDayOffset) {
            s += "Birkaç gün sonrası için tahminler daha belirsiz; yola çıkmadan önce tekrar bakmanı öneririm."
        }
        if (o != null) {
            val hours = (zone.rules.getOffset(now).totalSeconds - o.timeZone.rules.getOffset(now).totalSeconds) / 3600
            if (hours != 0) {
                s += "${dest.name} saati ${o.name} saatinden ${kotlin.math.abs(hours)} saat ${if (hours > 0) "ileride" else "geride"}; saatler yerel saate göre."
            }
        }

        val theme = WeatherSummaryEngine.theme(
            CurrentWeather(first.date, first.maxTemperature, first.maxTemperature, first.condition, humidity = 0, windSpeedKmh = 0.0),
            precipitation = null, wind = null, now = first.date,
        )
        return TravelSummary(dest, o, rows, missing, uniquePacking, Briefing(s, CharacterMood.HAPPY, theme))
    }

    private fun dayLabel(date: Instant, now: Instant, zone: ZoneId): String =
        when (ChronoUnit.DAYS.between(now.localDate(zone), date.localDate(zone))) {
            0L -> "Bugün"
            1L -> "Yarın"
            else -> TurkishFormatting.weekdayName(date, zone)
        }

    companion object {
        const val MAX_TRAVEL_DAYS = 7

        /** Calendar days from departure through return, in [zone], capped at [MAX_TRAVEL_DAYS]. */
        fun travelDays(departure: Instant, returnDate: Instant?, zone: ZoneId): List<LocalDate> {
            val start = departure.localDate(zone)
            val end = maxOf(returnDate ?: departure, departure).localDate(zone)
            val days = mutableListOf<LocalDate>()
            var d = start
            while (!d.isAfter(end) && days.size < MAX_TRAVEL_DAYS) {
                days += d
                d = d.plusDays(1)
            }
            return days
        }

        internal fun packingSentence(items: List<ClothingItem>): String? {
            val outer = items.firstOrNull { it.id in listOf("thickCoat", "coat", "lightJacket", "sweatshirt", "windbreaker") }
            val rain = items.firstOrNull { it.id in listOf("umbrella", "boots") }
            val sun = items.firstOrNull { it.id == "sunscreen" }
            val spoken = mapOf(
                "thickCoat" to "kalın bir mont", "coat" to "bir mont", "lightJacket" to "ince bir mont",
                "sweatshirt" to "bir sweatshirt", "windbreaker" to "rüzgâr geçirmeyen bir üst",
                "umbrella" to "şemsiye", "boots" to "su geçirmez bot", "sunscreen" to "güneş kremi",
            )
            val names = listOfNotNull(outer, rain, sun).mapNotNull { spoken[it.id] }
            if (names.isEmpty()) return null
            val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " ve " + names.last()
            return "Yanına $list almanı öneririm."
        }
    }
}
