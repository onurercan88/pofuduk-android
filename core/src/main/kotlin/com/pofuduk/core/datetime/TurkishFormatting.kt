package com.pofuduk.core.datetime

import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.util.TurkishLocale
import com.pofuduk.core.util.foundationWeekday
import com.pofuduk.core.util.roundedInt
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Locale-independent Turkish formatting (identical output on every device). */
object TurkishFormatting {
    /** "14.00" style clock time in the given time zone. */
    fun clockTime(instant: Instant, zone: ZoneId): String {
        val z = instant.atZone(zone)
        return String.format(Locale.ROOT, "%02d.%02d", z.hour, z.minute)
    }

    /** Rounded temperature as spoken text, e.g. "24 derece", "eksi 3 derece". */
    fun temperature(value: Double): String {
        val rounded = value.roundedInt()
        return if (rounded < 0) "eksi ${-rounded} derece" else "$rounded derece"
    }

    /** Compact temperature for UI, e.g. "24°". */
    fun shortTemperature(value: Double): String = "${value.roundedInt()}°"

    val weekdayNames = listOf("Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi")
    val monthNames = listOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık",
    )

    /** Weekday name for a Foundation-style weekday (1 = Sunday). */
    fun weekdayName(instant: Instant, zone: ZoneId): String = weekdayNames[instant.foundationWeekday(zone) - 1]

    /** "14 Kasım Cumartesi" */
    fun longDate(instant: Instant, zone: ZoneId): String {
        val d = instant.atZone(zone)
        return "${d.dayOfMonth} ${monthNames[d.monthValue - 1]} ${weekdayName(instant, zone)}"
    }

    fun conditionName(condition: WeatherCondition, isDay: Boolean = true): String = when (condition) {
        WeatherCondition.CLEAR -> if (isDay) "güneşli" else "açık"
        WeatherCondition.PARTLY_CLOUDY -> "parçalı bulutlu"
        WeatherCondition.CLOUDY -> "bulutlu"
        WeatherCondition.FOG -> "sisli"
        WeatherCondition.DRIZZLE -> "çisentili"
        WeatherCondition.RAIN -> "yağmurlu"
        WeatherCondition.HEAVY_RAIN -> "kuvvetli yağışlı"
        WeatherCondition.THUNDERSTORM -> "gök gürültülü sağanaklı"
        WeatherCondition.SNOW -> "karlı"
        WeatherCondition.SLEET -> "karla karışık yağmurlu"
        WeatherCondition.WINDY -> "rüzgârlı"
    }
}

/** Minimal Turkish suffix helper for proper nouns (vowel harmony + consonant hardening). */
object TurkishGrammar {
    private val backVowels = setOf('a', 'ı', 'o', 'u')
    private val frontVowels = setOf('e', 'i', 'ö', 'ü')
    private val hardConsonants = setOf('f', 's', 't', 'k', 'ç', 'ş', 'h', 'p')

    /** "İstanbul" → "İstanbul'da", "İzmir" → "İzmir'de", "Kars" → "Kars'ta", "Rize" → "Rize'de". */
    fun locative(word: String): String = "$word'${consonant(word)}${vowel(word)}"

    /** "İstanbul" → "İstanbul'dan", "İzmir" → "İzmir'den", "Kars" → "Kars'tan". */
    fun ablative(word: String): String = "$word'${consonant(word)}${vowel(word)}n"

    private fun vowel(word: String): String {
        val lower = word.lowercase(TurkishLocale)
        val last = lower.lastOrNull { it in backVowels || it in frontVowels }
        return if (last == null || last in backVowels) "a" else "e"
    }

    private fun consonant(word: String): String =
        if (word.lowercase(TurkishLocale).lastOrNull()?.let { it in hardConsonants } == true) "t" else "d"
}
