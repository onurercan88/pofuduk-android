package com.pofuduk.core.engine

import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.util.capitalizedFirstTurkish
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.isSameDay
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.startOfHour
import java.time.Instant

/** Tunable clothing rules (feels-like bands in °C). Advice only; never a safety or medical guarantee. */
data class ClothingRules(
    val hot: Double = 26.0,
    val warm: Double = 20.0,
    val mild: Double = 15.0,
    val cool: Double = 10.0,
    val cold: Double = 3.0,
    /** Feels-like spread across the day that suggests layers. */
    val layeringSpread: Double = 8.0,
    /** Temperature minus feels-like worth explaining (wind chill). */
    val noticeableChill: Double = 3.0,
    /** Waking hours considered (local). */
    val activeHours: IntRange = 7..22,
) {
    companion object {
        val DEFAULT = ClothingRules()
    }
}

data class ClothingItem(
    val id: String,
    val name: String,
    /** Emoji used as the visual (always available, no asset dependency). */
    val symbol: String,
) {
    companion object {
        val thickCoat = ClothingItem("thickCoat", "Kalın mont", "🧥")
        val coat = ClothingItem("coat", "Mont", "🧥")
        val lightJacket = ClothingItem("lightJacket", "İnce mont veya ceket", "🧥")
        val sweatshirt = ClothingItem("sweatshirt", "Sweatshirt veya hırka", "🧶")
        val sweater = ClothingItem("sweater", "Kazak", "🧶")
        val longSleeve = ClothingItem("longSleeve", "Uzun kollu üst", "👔")
        val tshirt = ClothingItem("tshirt", "Tişört", "👕")
        val lightClothes = ClothingItem("lightClothes", "Hafif, açık renkli kıyafetler", "🩳")
        val scarf = ClothingItem("scarf", "Atkı", "🧣")
        val gloves = ClothingItem("gloves", "Eldiven", "🧤")
        val beanie = ClothingItem("beanie", "Bere", "🧢")
        val umbrella = ClothingItem("umbrella", "Şemsiye", "☂️")
        val waterproofShoes = ClothingItem("waterproofShoes", "Su geçirmeyen ayakkabı", "👟")
        val boots = ClothingItem("boots", "Su geçirmez bot", "🥾")
        val windbreaker = ClothingItem("windbreaker", "Rüzgâr geçirmeyen bir üst", "🧥")
        val sunglasses = ClothingItem("sunglasses", "Güneş gözlüğü", "🕶️")
        val hat = ClothingItem("hat", "Şapka", "👒")
        val sunscreen = ClothingItem("sunscreen", "Güneş kremi", "🧴")
    }
}

data class ClothingRecommendation(
    /** Spoken/subtitled summary (1–3 sentences), derived only from forecast data. */
    val sentences: List<String>,
    val items: List<ClothingItem>,
    /** "Why" explanations, each tied to a data point. */
    val reasons: List<String>,
    val suggestsLayers: Boolean,
    /** True when the advice is worth adding to the main briefing. */
    val isNoteworthy: Boolean,
    val feelsLikeRange: ClosedFloatingPointRange<Double>,
    /** Single sentence suitable for the main briefing. */
    val briefingSentence: String,
) {
    val summary: String get() = sentences.joinToString(" ")
}

/** Deterministic clothing advice from current + upcoming hours of one local day. */
class ClothingRecommendationEngine(
    val rules: ClothingRules = ClothingRules.DEFAULT,
    val thresholds: WeatherThresholds = WeatherThresholds.DEFAULT,
) {
    private val summary = WeatherSummaryEngine(thresholds)

    /**
     * [day]: any time on the wanted local day (default: today). Returns null when the forecast lacks hourly data
     * for that day — never guesses.
     */
    fun recommend(snapshot: WeatherSnapshot, day: Instant? = null, now: Instant): ClothingRecommendation? {
        val zone = snapshot.place.timeZone
        val target = day ?: now
        val isToday = target.isSameDay(now, zone)
        val hourStart = now.startOfHour(zone)

        val dayHours = snapshot.hourly.filter { it.time.isSameDay(target, zone) && (!isToday || !it.time.isBefore(hourStart)) }
        var hours = dayHours.filter { it.time.hour(zone) in rules.activeHours }
        if (hours.isEmpty()) hours = dayHours // late evening: use what's left
        if (hours.isEmpty()) return null

        val feels = hours.map { it.feelsLike ?: it.temperature }
        val temps = hours.map { it.temperature }
        val coldFL = feels.min()
        val warmFL = feels.max()
        val label = when {
            isToday -> "Bugün"
            target.localDate(zone) == now.localDate(zone).plusDays(1) -> "Yarın"
            else -> "O gün"
        }

        val items = mutableListOf<ClothingItem>()
        val reasons = mutableListOf<String>()

        // Outer layer from the coldest felt temperature.
        when {
            coldFL < rules.cold -> items += listOf(ClothingItem.thickCoat, ClothingItem.scarf, ClothingItem.gloves, ClothingItem.beanie)
            coldFL < rules.cool -> items += listOf(ClothingItem.coat, ClothingItem.scarf)
            coldFL < rules.mild -> items += ClothingItem.lightJacket
            coldFL < rules.warm -> items += ClothingItem.sweatshirt
        }
        // Base layer from the warmest felt temperature.
        items += when {
            warmFL >= rules.hot -> ClothingItem.lightClothes
            warmFL >= rules.warm -> ClothingItem.tshirt
            warmFL >= rules.cool -> ClothingItem.longSleeve
            else -> ClothingItem.sweater
        }
        reasons += "$label hissedilen sıcaklık ${coldFL.roundedInt()} ile ${warmFL.roundedInt()} derece arasında."

        val layers = warmFL - coldFL >= rules.layeringSpread
        if (layers) {
            val coldHour = hours[feels.indexOf(coldFL)].time
            val warmHour = hours[feels.indexOf(warmFL)].time
            reasons += "Saat ${TurkishFormatting.clockTime(coldHour, zone)} civarında ${coldFL.roundedInt()}, " +
                "saat ${TurkishFormatting.clockTime(warmHour, zone)} civarında ${warmFL.roundedInt()} derece hissedilecek; " +
                "gün içinde fark büyük."
        }

        // Wind chill: actual vs felt at the coldest-felt hour.
        val chill = temps[feels.indexOf(coldFL)] - coldFL
        val maxWind = hours.mapNotNull { it.windSpeedKmh }.maxOrNull() ?: 0.0
        val maxGust = hours.mapNotNull { it.windGustKmh }.maxOrNull() ?: 0.0
        val windy = maxWind >= thresholds.strongWindKmh || maxGust >= thresholds.strongGustKmh
        if (windy) {
            items += ClothingItem.windbreaker
            reasons += "Rüzgâr saatte ${maxWind.roundedInt()} kilometreye kadar çıkabilir."
        }
        val chillNotable = chill >= rules.noticeableChill

        // Precipitation (same rules as the briefing: heavy only from amounts).
        val precipitation = summary.precipitationOutlook(dayHours)
        if (precipitation != null) {
            val start = TurkishFormatting.clockTime(precipitation.start, zone)
            when {
                precipitation.kind == PrecipitationKind.SNOW -> {
                    items += ClothingItem.boots
                    reasons += "Saat $start civarında kar bekleniyor."
                }
                precipitation.isLikely -> {
                    items += listOf(ClothingItem.umbrella, ClothingItem.waterproofShoes)
                    reasons += "Saat $start civarında yağış bekleniyor."
                }
                else -> {
                    items += ClothingItem.umbrella
                    val prob = precipitation.maxProbability?.let { "yüzde $it" } ?: "düşük"
                    reasons += "Yağış ihtimali $prob civarında; kesin değil."
                }
            }
        }

        // UV during the remaining daylight.
        val uv = hours.mapNotNull { it.uvIndex }.maxOrNull() ?: 0.0
        if (uv >= thresholds.highUVIndex) {
            items += listOf(ClothingItem.sunglasses, ClothingItem.hat, ClothingItem.sunscreen)
            reasons += "UV indeksi ${uv.roundedInt()} seviyesine çıkıyor."
        }

        // Spoken summary.
        val sentences = mutableListOf<String>()
        val tempText = TurkishFormatting.temperature(temps.max())
        if (chillNotable) {
            sentences += "$label hava $tempText civarında ama rüzgâr nedeniyle daha serin, " +
                "${TurkishFormatting.temperature(coldFL)} gibi hissedilebilir."
        } else if (layers) {
            sentences += "$label sabahla gün ortası arasında belirgin bir sıcaklık farkı var."
        }
        sentences += mainAdvice(coldFL, layers, label, hasLeadIn = sentences.isNotEmpty())
        if (precipitation != null) {
            sentences += when {
                precipitation.kind == PrecipitationKind.SNOW -> "Kar beklendiği için su geçirmez bir bot iyi olur."
                precipitation.isLikely -> "Yağış beklendiği için şemsiye ve su geçirmeyen ayakkabı iyi fikir olabilir."
                else -> "Yağış ihtimali olduğu için yanına bir şemsiye alabilirsin."
            }
        }
        if (uv >= thresholds.highUVIndex) sentences += "UV değeri yüksek; güneşten korunmayı da unutma."

        val noteworthy = layers || chillNotable || windy || (precipitation?.isLikely ?: false) ||
            coldFL < rules.cool || warmFL >= rules.hot
        return ClothingRecommendation(
            sentences = sentences,
            items = items.distinctBy { it.id },
            reasons = reasons,
            suggestsLayers = layers,
            isNoteworthy = noteworthy,
            feelsLikeRange = coldFL..warmFL,
            briefingSentence = briefingSentence(coldFL, layers, label),
        )
    }

    private fun mainAdvice(coldFL: Double, layers: Boolean, label: String, hasLeadIn: Boolean): String {
        val core = coreAdvice(coldFL)
        val text = when {
            layers -> "Katmanlı giyinmek iyi olur; $core."
            hasLeadIn -> "$core."
            else -> "$label $core."
        }
        return text.capitalizedFirstTurkish()
    }

    /** One self-contained sentence for the main briefing (no umbrella/UV repetition). */
    internal fun briefingSentence(coldFL: Double, layers: Boolean, label: String): String =
        if (layers) "$label katmanlı giyinmek iyi olur; ${coreAdvice(coldFL)}." else "$label ${coreAdvice(coldFL)}."

    private fun coreAdvice(coldFL: Double): String = when {
        coldFL < rules.cold -> "kalın bir mont, atkı, eldiven ve bere iyi olur"
        coldFL < rules.cool -> "bir mont ve atkı almanı öneririm"
        coldFL < rules.mild -> "ince bir mont veya ceket tercih edebilirsin"
        coldFL < rules.warm -> "ince bir mont veya sweatshirt tercih edebilirsin"
        coldFL < rules.hot -> "tişört gibi hafif bir üst yeterli olur"
        else -> "hafif ve açık renkli kıyafetler tercih edebilirsin"
    }
}
