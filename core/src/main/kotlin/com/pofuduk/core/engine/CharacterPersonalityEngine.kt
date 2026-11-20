package com.pofuduk.core.engine

import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.datetime.TurkishGrammar
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.BriefingLength
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.PersonalityStyle
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.roundedInt
import java.time.Instant
import java.time.ZoneId

/**
 * Turns factual analysis into Pofuduk's voice. Every number and event comes from [WeatherAnalysis]
 * or verified [OfficialWarning]s; style only changes greeting/closing tone, never facts.
 */
class CharacterPersonalityEngine(
    val style: PersonalityStyle = PersonalityStyle.FRIENDLY,
    val length: BriefingLength = BriefingLength.DETAILED,
) {
    fun briefing(
        analysis: WeatherAnalysis,
        warnings: List<OfficialWarning> = emptyList(),
        clothing: ClothingRecommendation? = null,
    ): Briefing {
        val zone = analysis.place.timeZone
        val activeWarnings = warnings.filter { it.isActive(analysis.analyzedAt) }.sortedByDescending { it.level }
        // A likely thunderstorm later today makes the message serious even before it shows on the character.
        val serious = (activeWarnings.firstOrNull()?.level ?: WarningLevel.GREEN) >= WarningLevel.ORANGE ||
            analysis.outlookTheme == WeatherTheme.STORMY
        val s = mutableListOf<String>()

        s += greeting(analysis.analyzedAt, zone, serious)

        if (analysis.source == WeatherDataSource.DEMO) {
            s += "Şu an örnek verilerle konuşuyorum, bu gerçek bir tahmin değil."
        }
        if (analysis.isStale) {
            val t = TurkishFormatting.clockTime(analysis.retrievedAt, zone)
            s += "Bu bilgiler saat $t itibarıyla alındı, güncel olmayabilir."
        }

        // Official warnings always come first and are never softened.
        for (warning in activeWarnings.take(2).filter { it.level > WarningLevel.GREEN }) {
            s += "Dikkat! Bulunduğun bölge için MGM tarafından ${warning.level.turkishName} seviyede meteorolojik uyarı yayımlanmış."
            s += "${warning.hazard} bekleniyor. Lütfen resmi uyarının detaylarını incelemeyi unutma."
        }

        s += currentSentence(analysis)

        if (length == BriefingLength.DETAILED && analysis.feelsLikeIsNotable) {
            s += "Hissedilen sıcaklık ise ${TurkishFormatting.temperature(analysis.current.feelsLike)}."
        }
        rangeSentence(analysis)?.let { s += it }
        s += precipitationSentences(analysis)
        if (analysis.briefingTime.looksAhead) tomorrowSentence(analysis)?.let { s += it }

        analysis.wind?.takeIf { it.isStrong }?.let { s += windSentence(it) }
        if (length == BriefingLength.DETAILED && analysis.isHighUV && analysis.uvMax != null) {
            s += "UV indeksi ${analysis.uvMax.roundedInt()} seviyesine kadar çıkıyor, güneşten korunmayı unutma."
        }

        if (length == BriefingLength.DETAILED && clothing != null && clothing.isNoteworthy) {
            s += clothing.briefingSentence
        }

        s += closing(analysis.outlookTheme, serious, analysis.precipitation, analysis.briefingTime)

        val mood = if (serious) CharacterMood.WARNING else restingMood(analysis.theme)
        return Briefing(s, mood, analysis.theme, advice(analysis))
    }

    /** Shown while no location is available. Contains no weather claims. */
    fun locationNeededBriefing(): Briefing = Briefing(
        listOf("Merhaba! Ben Pofuduk.", "Havayı anlatabilmem için önce nerede olduğunu bilmem gerekiyor."),
        CharacterMood.IDLE, WeatherTheme.CLOUDY,
    )

    /** Spoken when no data could be obtained. Never invents values. */
    fun unavailableBriefing(reason: String): Briefing = Briefing(
        listOf("Üzgünüm, şu an hava durumu bilgisine ulaşamadım.", reason, "Biraz sonra tekrar deneyelim mi?"),
        CharacterMood.ERROR, WeatherTheme.CLOUDY,
    )

    // Pieces

    internal fun greeting(at: Instant, zone: ZoneId, serious: Boolean): String {
        val hour = at.hour(zone)
        val base = when (hour) {
            in 5..10 -> "Günaydın"
            in 11..16 -> "İyi günler"
            in 17..21 -> "İyi akşamlar"
            else -> "Merhaba"
        }
        if (serious) return "$base."
        return when (style) {
            PersonalityStyle.FRIENDLY -> "$base! Ben Pofuduk, havayı senin için kontrol ettim."
            PersonalityStyle.PLAYFUL -> "$base! Pofuduk bildiriyor, bulutlarla konuştum bile."
            PersonalityStyle.CALM -> if (hour >= 17) "$base. Bu akşamın ve yarının havasına birlikte bakalım."
            else "$base. Bugünün havasına birlikte bakalım."
        }
    }

    internal fun currentSentence(a: WeatherAnalysis): String {
        val place = TurkishGrammar.locative(a.place.name)
        val temp = TurkishFormatting.temperature(a.current.temperature)
        val condition = TurkishFormatting.conditionName(a.current.condition, a.current.isDay)
        return "$place şu an $temp, hava $condition."
    }

    /**
     * Only the hours still ahead are worth telling: the whole day in the morning,
     * the rest of the day in the afternoon, tonight's low in the evening.
     */
    internal fun rangeSentence(a: WeatherAnalysis): String? {
        val now = a.current.temperature
        return when (a.briefingTime) {
            BriefingTime.MORNING -> fullDayRangeSentence(a)
            BriefingTime.AFTERNOON -> {
                val lo = a.restOfDayLow ?: return null
                val hi = a.restOfDayHigh ?: return null
                when {
                    hi >= now + 2 -> "Günün geri kalanında sıcaklık ${TurkishFormatting.temperature(hi)} kadar çıkabilir."
                    lo <= now - 2 -> "Akşama doğru sıcaklık ${TurkishFormatting.temperature(lo)} civarına düşecek."
                    else -> "Günün geri kalanında sıcaklık pek değişmeyecek."
                }
            }
            BriefingTime.EVENING, BriefingTime.NIGHT -> {
                val lo = a.tonightLow ?: return null
                val whenText = if (a.briefingTime == BriefingTime.EVENING) "Bu gece" else "Sabaha karşı"
                if (lo <= now - 2) "$whenText sıcaklık ${TurkishFormatting.temperature(lo)} civarına düşecek."
                else "$whenText sıcaklık ${TurkishFormatting.temperature(lo)} civarında olacak."
            }
        }
    }

    internal fun tomorrowSentence(a: WeatherAnalysis): String? {
        val t = a.tomorrow ?: return null
        val lo = TurkishFormatting.temperature(t.low).replace(" derece", "")
        var sentence = if (t.low.roundedInt() == t.high.roundedInt()) {
            "Yarın sıcaklık ${TurkishFormatting.temperature(t.high)} civarında"
        } else {
            "Yarın sıcaklık $lo ile ${TurkishFormatting.temperature(t.high)} arasında"
        }
        val noun = when (t.condition) {
            WeatherCondition.THUNDERSTORM -> "gök gürültülü sağanak"
            WeatherCondition.SNOW -> "kar"
            WeatherCondition.SLEET -> "karla karışık yağmur"
            else -> "yağış"
        }
        val percent = t.precipitationProbability?.let { ", ihtimali yüzde $it" } ?: ""
        sentence += when {
            t.precipitationLikely -> "; $noun bekleniyor$percent."
            t.precipitationPossible -> "; $noun ihtimali var ama kesin değil$percent."
            else -> ", yağış beklenmiyor."
        }
        return sentence
    }

    internal fun fullDayRangeSentence(a: WeatherAnalysis): String? {
        val minT = a.minTemperature ?: return null
        val maxT = a.maxTemperature ?: return null
        if (minT.roundedInt() == maxT.roundedInt()) {
            return "Gün boyunca sıcaklık ${TurkishFormatting.temperature(maxT)} civarında."
        }
        var sentence = "Bugün sıcaklık ${TurkishFormatting.temperature(minT).replace(" derece", "")} ile " +
            "${TurkishFormatting.temperature(maxT)} arasında olacak."
        if (a.temperatureSwingIsNotable) sentence += " Sabahla akşam arasındaki farka dikkat."
        return sentence
    }

    internal fun precipitationSentences(a: WeatherAnalysis): List<String> {
        val zone = a.place.timeZone
        val p = a.precipitation
        if (p == null) {
            if (!a.hasHourlyCoverage) return emptyList()
            return listOf(
                when (a.briefingTime) {
                    BriefingTime.MORNING -> "Bugün için yağış beklenmiyor."
                    BriefingTime.AFTERNOON -> "Günün geri kalanında yağış beklenmiyor."
                    BriefingTime.EVENING -> "Bu akşam için yağış beklenmiyor."
                    BriefingTime.NIGHT -> "Bu gece için yağış beklenmiyor."
                },
            )
        }
        val start = TurkishFormatting.clockTime(p.start, zone)
        val startsNow = !p.start.isAfter(a.analyzedAt)
        val noun = when (p.kind) {
            PrecipitationKind.RAIN -> "yağmur"
            PrecipitationKind.SNOW -> "kar"
            PrecipitationKind.SLEET -> "karla karışık yağmur"
            PrecipitationKind.THUNDERSTORM -> "gök gürültülü sağanak"
        }

        if (!p.isLikely) {
            val probabilityText = p.maxProbability?.let { " yüzde $it civarında" } ?: ""
            val whenText = if (startsNow) "Önümüzdeki saatlerde" else "Saat $start sonrasında"
            return listOf("$whenText $noun ihtimali$probabilityText. Kesin değil ama hazırlıklı olmakta fayda var.")
        }

        val result = mutableListOf<String>()
        val duration = p.durationHours
        val durationText = when {
            p.lastsUntilEndOfDay -> " ve gün sonuna kadar sürebilir"
            duration >= 2 -> " ve yaklaşık $duration saat sürebilir"
            else -> ""
        }
        result += if (startsNow) "Şu sıralar $noun var$durationText."
        else "Saat $start civarında $noun başlaması bekleniyor$durationText."

        when {
            p.kind == PrecipitationKind.THUNDERSTORM -> result += "Gök gürültüsü sırasında açık alanlarda bulunmamaya özen göster."
            p.intensity == PrecipitationIntensity.HEAVY -> result += "Yağışın yer yer kuvvetli olması bekleniyor."
            p.intensity == PrecipitationIntensity.MODERATE -> result += "Orta şiddette bir yağış bekleniyor."
            p.intensity == PrecipitationIntensity.LIGHT -> result += "Hafif bir yağış bekleniyor."
            else -> Unit
        }
        return result
    }

    fun windSentence(wind: WindOutlook): String {
        val speed = wind.maxSpeedKmh.roundedInt()
        var sentence = "Rüzgâr saatte $speed kilometreye kadar çıkabilir"
        val gust = wind.maxGustKmh
        if (gust != null && gust > wind.maxSpeedKmh + 5) {
            sentence += ", ani esintiler ${gust.roundedInt()} kilometreyi bulabilir"
        }
        return "$sentence. Açık alanlarda dikkatli ol."
    }

    internal fun closing(
        theme: WeatherTheme,
        serious: Boolean,
        precipitation: PrecipitationOutlook?,
        briefingTime: BriefingTime = BriefingTime.MORNING,
    ): String {
        if (serious) return "Şakayı bir kenara bırakalım; lütfen dikkatli ol ve gereksiz yere dışarı çıkma."
        // "Great day to go out" makes no sense once the day is over.
        if (briefingTime.looksAhead && (theme == WeatherTheme.SUNNY || theme == WeatherTheme.CLOUDY)) {
            return when (style) {
                PersonalityStyle.PLAYFUL -> "Akşamın tadını çıkar, yarın yine bulutlarla konuşurum!"
                PersonalityStyle.CALM -> "Keyifli bir akşam geçirmeni dilerim."
                PersonalityStyle.FRIENDLY -> "İyi akşamlar dilerim, yarın yine buradayım."
            }
        }
        return when (theme) {
            WeatherTheme.RAINY -> when (style) {
                PersonalityStyle.PLAYFUL -> "Şemsiyeni unutma, sonra ıslanıp beni suçlama!"
                PersonalityStyle.CALM -> "Dışarı çıkacaksan yanına bir şemsiye almanı öneririm."
                PersonalityStyle.FRIENDLY -> "Şemsiyeni unutma! Islanmanı hiç istemem."
            }
            WeatherTheme.SNOWY -> if (style == PersonalityStyle.CALM) "Atkını ve montunu hazırlamanı öneririm."
            else "Ortalık beyaza bürünebilir. Atkını ve montunu hazırlamayı unutma!"
            WeatherTheme.WINDY -> when (style) {
                PersonalityStyle.PLAYFUL -> "Saçlarımız biraz dağılabilir, rüzgâr bugün oldukça hareketli!"
                PersonalityStyle.CALM -> "Dışarıdayken rüzgârı hesaba katmanı öneririm."
                PersonalityStyle.FRIENDLY -> "Bugün biraz rüzgârımız var, sıkı giyinmeyi unutma!"
            }
            WeatherTheme.SUNNY -> when (style) {
                PersonalityStyle.PLAYFUL -> "Güneş bizimle! Bence güzel bir yürüyüş için harika bir gün."
                PersonalityStyle.CALM -> "Dışarıda vakit geçirmek için uygun bir gün."
                PersonalityStyle.FRIENDLY -> "Harika bir gün! Dışarı çıkmak için güzel bir fırsat."
            }
            WeatherTheme.NIGHT -> "İyi dinlenmeler, yarın yine buradayım."
            WeatherTheme.CLOUDY -> when {
                style == PersonalityStyle.PLAYFUL -> "Bulutlar bugün bana benziyor, ama güzel bir gün geçirmek senin elinde!"
                precipitation == null -> "Sakin ve bulutlu bir gün. Güzel bir gün geçirmek yine de senin elinde!"
                else -> "Gün içinde gökyüzüne bir göz atmayı unutma."
            }
            WeatherTheme.STORMY -> "Lütfen dikkatli ol."
        }
    }

    internal fun restingMood(theme: WeatherTheme): CharacterMood = when (theme) {
        WeatherTheme.SUNNY -> CharacterMood.HAPPY
        WeatherTheme.SNOWY -> CharacterMood.CELEBRATING
        WeatherTheme.STORMY -> CharacterMood.WARNING
        WeatherTheme.NIGHT -> CharacterMood.SLEEPING
        WeatherTheme.RAINY, WeatherTheme.CLOUDY, WeatherTheme.WINDY -> CharacterMood.IDLE
    }

    internal fun advice(a: WeatherAnalysis): List<String> {
        val items = mutableListOf<String>()
        a.precipitation?.let { items += if (it.isLikely) "Şemsiye al" else "Şemsiye yanında olsun, yağış ihtimali var" }
        if (a.wind?.isStrong == true) items += "Rüzgâra karşı dikkatli ol"
        if (a.isHighUV) items += "Güneş kremi ve şapka iyi fikir"
        if (a.temperatureSwingIsNotable && a.briefingTime == BriefingTime.MORNING) {
            items += "Katmanlı giyin, gün içinde sıcaklık çok değişiyor"
        }
        if (a.feelsLikeIsNotable && a.current.feelsLike < a.current.temperature) items += "Olduğundan serin hissedilebilir"
        return items
    }
}
