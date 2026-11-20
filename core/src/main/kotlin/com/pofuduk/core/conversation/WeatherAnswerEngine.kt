package com.pofuduk.core.conversation

import com.pofuduk.core.datetime.ForecastDateResolver
import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.datetime.TurkishGrammar
import com.pofuduk.core.engine.ClothingRecommendationEngine
import com.pofuduk.core.engine.PrecipitationIntensity
import com.pofuduk.core.engine.PrecipitationKind
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.engine.WeatherThresholds
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.HourlyForecast
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.isSameDay
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.startOfHour
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Answers a resolved question from a snapshot. Every number comes from the snapshot; if the requested
 * day or hours are not covered, it says so instead of guessing.
 */
class WeatherAnswerEngine(val thresholds: WeatherThresholds = WeatherThresholds.DEFAULT) {
    private val summary = WeatherSummaryEngine(thresholds)
    private val dates = ForecastDateResolver()

    fun answer(query: ResolvedQuery, snapshot: WeatherSnapshot, now: Instant): Briefing {
        val zone = snapshot.place.timeZone
        val requested = dates.days(query.date, now, zone)
        val covered = dates.covered(requested, snapshot)
        val theme = WeatherSummaryEngine().analyze(snapshot, now).theme

        if (covered.isEmpty()) {
            val maxDays = if (snapshot.daily.isNotEmpty()) snapshot.daily.size else 1
            return Briefing(
                listOf("Bu tarih için henüz bir tahminim yok.", "Şu an en fazla $maxDays günlük tahmin verebiliyorum."),
                CharacterMood.SAD, theme,
            )
        }

        val sentences = mutableListOf<String>()
        for (day in covered) {
            val ctx = DayContext(day, snapshot, now, query.dayPart, multiDay = covered.size > 1)
            sentences += answer(query.topic, ctx)
        }
        if (covered.size < requested.size) sentences += "Sonraki günler için tahmin henüz yayımlanmadı."
        if (snapshot.isStale(now)) sentences += "Bu bilgiler güncel olmayabilir."
        return Briefing(sentences, mood(theme), theme)
    }

    fun didNotUnderstand(): Briefing = Briefing(
        listOf("Bunu tam anlayamadım.", "Örneğin \"Yarın hava nasıl?\" ya da \"Akşam yağmur var mı?\" diye sorabilirsin."),
        CharacterMood.THINKING, WeatherTheme.CLOUDY,
    )

    // Context

    internal class DayContext(
        val day: Instant,
        val snapshot: WeatherSnapshot,
        val now: Instant,
        val dayPart: DayPart?,
        val multiDay: Boolean,
    ) {
        val zone get() = snapshot.place.timeZone
        val isToday: Boolean get() = day.isSameDay(now, zone)
        val daily: DailyForecast? get() = snapshot.daily.firstOrNull { it.date.isSameDay(day, zone) }

        /** Hourly entries for this day/day part; for today only from the current hour on. */
        val hours: List<HourlyForecast>
            get() {
                val hourStart = now.startOfHour(zone)
                return snapshot.hourly.filter { h ->
                    if (!h.time.isSameDay(day, zone)) return@filter false
                    if (isToday && h.time.isBefore(hourStart)) return@filter false
                    dayPart?.let { h.time.hour(zone) in it.hours } ?: true
                }
            }

        /** "Yarın akşam", "Bugün saat 17.00 civarında", "Cumartesi" */
        val label: String
            get() {
                val offset = ChronoUnit.DAYS.between(now.localDate(zone), day.localDate(zone)).toInt()
                val weekday = TurkishFormatting.weekdayName(day, zone)
                val base = when (offset) {
                    0 -> "Bugün"
                    1 -> "Yarın"
                    else -> weekday
                }
                if (offset == 0) {
                    // Natural Turkish: "Bu sabah / Bu akşam / Bu gece" rather than "Bugün sabah".
                    when (dayPart) {
                        DayPart.Morning -> return "Bu sabah"
                        DayPart.Evening -> return "Bu akşam"
                        DayPart.Night -> return "Bu gece"
                        else -> Unit
                    }
                }
                return when (dayPart) {
                    DayPart.Morning -> "$base sabah"
                    DayPart.Noon -> "$base öğle saatlerinde"
                    DayPart.Afternoon -> "$base öğleden sonra"
                    DayPart.Evening -> "$base akşam"
                    DayPart.Night -> "$base gece"
                    is DayPart.Hour -> "$base saat " + String.format(Locale.ROOT, "%02d.00", dayPart.hour) + " civarında"
                    null -> base
                }
            }

        val place: String get() = TurkishGrammar.locative(snapshot.place.name)
    }

    // Topics

    private fun answer(topic: WeatherTopic, c: DayContext): List<String> = when (topic) {
        WeatherTopic.OVERVIEW, WeatherTopic.TRAVEL -> overview(c)
        WeatherTopic.TEMPERATURE -> listOf(temperature(c) ?: noData(c))
        WeatherTopic.RAIN, WeatherTopic.RAIN_TIMING -> rain(c, timing = topic == WeatherTopic.RAIN_TIMING)
        WeatherTopic.UMBRELLA -> umbrella(c)
        WeatherTopic.WIND -> listOf(wind(c) ?: noData(c))
        WeatherTopic.UV -> listOf(uv(c) ?: noData(c))
        WeatherTopic.CLOTHING -> clothing(c)
        WeatherTopic.GOING_OUT -> goingOut(c)
    }

    private fun noData(c: DayContext) = "${c.label} için bu bilgiye ait tahmin verisi yok."

    private fun overview(c: DayContext): List<String> {
        val out = goingOut(c)
        return out.ifEmpty { listOf(noData(c)) }
    }

    private fun temperature(c: DayContext): String? {
        val hours = c.hours
        if (c.dayPart is DayPart.Hour) {
            hours.firstOrNull()?.let { h ->
                return "${c.label} ${c.place} sıcaklık ${TurkishFormatting.temperature(h.temperature)}, " +
                    "hava ${TurkishFormatting.conditionName(h.condition)} olacak."
            }
        }
        if (c.dayPart != null && hours.isNotEmpty()) {
            return range("${c.label} ${c.place} sıcaklık", hours.minOf { it.temperature }, hours.maxOf { it.temperature })
        }
        if (c.isToday && c.dayPart == null && !c.multiDay) {
            val now = "${c.place} şu an ${TurkishFormatting.temperature(c.snapshot.current.temperature)}"
            val d = c.daily ?: return "$now."
            return "$now; gün içinde en düşük ${TurkishFormatting.temperature(d.minTemperature)}, " +
                "en yüksek ${TurkishFormatting.temperature(d.maxTemperature)}."
        }
        c.daily?.let { d ->
            return "${c.label} ${c.place} hava ${TurkishFormatting.conditionName(d.condition)}; " +
                "sıcaklık ${TurkishFormatting.temperature(d.minTemperature).replace(" derece", "")} ile " +
                "${TurkishFormatting.temperature(d.maxTemperature)} arasında olacak."
        }
        if (hours.isNotEmpty()) {
            return range("${c.label} ${c.place} sıcaklık", hours.minOf { it.temperature }, hours.maxOf { it.temperature })
        }
        return null
    }

    private fun range(prefix: String, lo: Double, hi: Double): String {
        if (lo.roundedInt() == hi.roundedInt()) return "$prefix ${TurkishFormatting.temperature(hi)} civarında olacak."
        return "$prefix ${TurkishFormatting.temperature(lo).replace(" derece", "")} ile ${TurkishFormatting.temperature(hi)} arasında olacak."
    }

    /** `repeatLabel = false` is used after a sentence that already named the day (avoids "Yarın … Yarın …"). */
    private fun rain(c: DayContext, timing: Boolean, repeatLabel: Boolean = true): List<String> {
        val hours = c.hours
        val zone = c.zone
        if (hours.isNotEmpty()) {
            val p = summary.precipitationOutlook(hours)
                ?: return listOf(if (repeatLabel) "${c.label} yağış beklenmiyor." else "Yağış beklenmiyor.")
            val noun = when (p.kind) {
                PrecipitationKind.SNOW -> "kar"
                PrecipitationKind.THUNDERSTORM -> "gök gürültülü sağanak"
                else -> "yağmur"
            }
            val start = TurkishFormatting.clockTime(p.start, zone)
            if (!p.isLikely) {
                val prob = p.maxProbability?.let { "yüzde $it civarında" } ?: "düşük"
                return listOf("${c.label} yağış ihtimali $prob; kesin değil. En olası zaman saat $start civarı.")
            }
            val isHour = c.dayPart is DayPart.Hour
            val showStart = (timing || c.dayPart == null) && !isHour
            var s = when {
                repeatLabel && showStart -> "${c.label} saat $start civarında $noun bekleniyor."
                repeatLabel -> "${c.label} $noun bekleniyor."
                showStart -> "Saat $start civarında $noun bekleniyor."
                else -> "O saatlerde $noun bekleniyor."
            }
            if (p.durationHours >= 2 && !p.lastsUntilEndOfDay) {
                s = s.dropLast(1) + ", yaklaşık ${p.durationHours} saat sürebilir."
            }
            val out = mutableListOf(s)
            when (p.intensity) {
                PrecipitationIntensity.HEAVY -> out += "Yer yer kuvvetli olabilir."
                PrecipitationIntensity.MODERATE -> out += "Orta şiddette bir yağış görünüyor."
                PrecipitationIntensity.LIGHT -> out += "Hafif bir yağış görünüyor."
                PrecipitationIntensity.UNKNOWN -> Unit
            }
            return out
        }
        val d = c.daily
        val prob = d?.precipitationProbability
        if (prob != null) {
            if (prob < thresholds.possiblePrecipitationProbability) return listOf("${c.label} yağış beklenmiyor.")
            return listOf("${c.label} yağış ihtimali yüzde $prob. Saatlik ayrıntı henüz yok.")
        }
        return listOf(noData(c))
    }

    private fun umbrella(c: DayContext): List<String> {
        val rainInfo = rain(c, timing = true)
        val p = if (c.hours.isEmpty()) null else summary.precipitationOutlook(c.hours)
        if (p == null) {
            val prob = c.daily?.precipitationProbability
            if (prob != null && prob >= thresholds.possiblePrecipitationProbability) {
                return listOf("Yanına bir şemsiye almanda fayda var.") + rainInfo
            }
            return if (c.hours.isEmpty() && c.daily == null) listOf(noData(c))
            else listOf("Şemsiyeye gerek yok gibi görünüyor.") + rainInfo
        }
        return listOf(if (p.isLikely) "Evet, şemsiye almanı öneririm." else "Kesin değil ama yanına bir şemsiye alabilirsin.") + rainInfo
    }

    private fun maxWind(c: DayContext): Double? =
        (c.hours.mapNotNull { it.windSpeedKmh } + listOfNotNull(c.daily?.maxWindKmh)).maxOrNull()

    private fun wind(c: DayContext): String? {
        val speed = maxWind(c) ?: return null
        val gust = c.hours.mapNotNull { it.windGustKmh }.maxOrNull()
        var s = "${c.label} rüzgâr saatte ${speed.roundedInt()} kilometreye kadar çıkabilir"
        if (gust != null && gust > speed + 5) s += ", ani esintiler ${gust.roundedInt()} kilometreyi bulabilir"
        val strong = speed >= thresholds.strongWindKmh || (gust ?: 0.0) >= thresholds.strongGustKmh
        return s + if (strong) ". Açık alanlarda dikkatli ol." else "."
    }

    private fun uv(c: DayContext): String? {
        val uv = (c.hours.mapNotNull { it.uvIndex } + listOfNotNull(c.daily?.uvIndexMax)).maxOrNull() ?: return null
        val level = uv.roundedInt()
        return when {
            uv >= thresholds.highUVIndex -> "${c.label} UV indeksi $level seviyesine çıkıyor; güneş kremi kullanmanı öneririm."
            uv >= 3 -> "${c.label} UV indeksi $level, orta seviyede. Uzun süre güneşte kalacaksan güneş kremi iyi olur."
            else -> "${c.label} UV indeksi $level, düşük seviyede."
        }
    }

    private fun clothing(c: DayContext): List<String> {
        val rec = ClothingRecommendationEngine(thresholds = thresholds).recommend(c.snapshot, c.day, c.now)
            ?: return listOf(noData(c))
        return rec.sentences
    }

    private fun goingOut(c: DayContext): List<String> {
        val out = mutableListOf<String>()
        temperature(c)?.let { out += it }
        out += rain(c, timing = false, repeatLabel = out.isEmpty())
        val w = wind(c)
        if (w != null && (maxWind(c) ?: 0.0) >= thresholds.strongWindKmh) out += w
        return out
    }

    private fun mood(theme: WeatherTheme): CharacterMood = when (theme) {
        WeatherTheme.STORMY -> CharacterMood.WARNING
        WeatherTheme.SUNNY -> CharacterMood.HAPPY
        else -> CharacterMood.IDLE
    }
}
