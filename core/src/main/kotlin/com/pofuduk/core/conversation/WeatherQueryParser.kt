package com.pofuduk.core.conversation

import com.pofuduk.core.data.TurkishProvinces

/**
 * Deterministic Turkish weather-question parser (no LLM). Keyword based on normalized text
 * ("ı→i, ş→s, ğ→g, ü→u, ö→o, ç→c", apostrophes removed).
 */
class WeatherQueryParser {
    fun parse(text: String): WeatherIntent {
        val normalized = TurkishProvinces.normalize(text).replace("'", "").replace("’", "")
        val tokens = normalized.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val joined = " " + tokens.joinToString(" ") + " "
        fun has(phrases: Array<String>): Boolean = phrases.any { joined.contains(" $it") }

        val date = parseDate(tokens, ::has)
        val dayPart = parseDayPart(tokens, ::has)
        val province = parseProvince(tokens)
        val topic = parseTopic(::has)
        val followUpMarker = tokens.firstOrNull()?.let { it in listOf("peki", "ya", "o", "bir", "ayrica") } ?: false
        val isFollowUp = followUpMarker || (date == null && province == null)
        return WeatherIntent(topic, date, dayPart, province, isFollowUp)
    }

    /** DestinationResolver (offline part): the destination province named in free text, if any. */
    fun province(text: String): TurkishProvinces.Province? = parse(text).province

    // Date

    private fun parseDate(tokens: List<String>, has: (Array<String>) -> Boolean): DateReference? {
        if (has(arrayOf("yarindan sonra", "obur gun", "ertesi gun"))) return DateReference.InDays(2)
        if (has(arrayOf("hafta sonu", "haftasonu", "hafta sonunda", "haftasonunda"))) return DateReference.Weekend
        if (has(arrayOf("yarin"))) return DateReference.Tomorrow
        if (has(arrayOf("bugun", "bu aksam", "bu sabah", "bu gece", "simdi", "su an", "bu ogle"))) return DateReference.Today
        // Longest match first so "pazartesi" isn't read as "pazar", "cumartesi" not as "cuma".
        val weekdays = listOf(
            "pazar" to 1, "pazartesi" to 2, "sali" to 3, "carsamba" to 4, "persembe" to 5, "cuma" to 6, "cumartesi" to 7,
        ).sortedByDescending { it.first.length }
        for (token in tokens) {
            for ((name, day) in weekdays) if (token.startsWith(name)) return DateReference.Weekday(day)
        }
        val i = tokens.indexOfFirst { it == "gun" || it.startsWith("gun") }
        if (i > 0) {
            val n = tokens[i - 1].toIntOrNull()
            if (n != null && n in 1..14 && tokens.any { it.startsWith("sonra") }) return DateReference.InDays(n)
        }
        return null
    }

    // Time of day

    private fun parseDayPart(tokens: List<String>, has: (Array<String>) -> Boolean): DayPart? {
        parseHour(tokens, morning = has(arrayOf("sabah")), night = has(arrayOf("gece")))?.let { return DayPart.Hour(it) }
        if (has(arrayOf("ogleden sonra"))) return DayPart.Afternoon
        if (has(arrayOf("ogle", "oglen"))) return DayPart.Noon
        if (has(arrayOf("sabah"))) return DayPart.Morning
        if (has(arrayOf("aksam"))) return DayPart.Evening
        if (has(arrayOf("gece"))) return DayPart.Night
        return null
    }

    /** "saat 17", "17.00", "17:30", "saat 5te" (1–6 without "sabah"/"gece" means afternoon). */
    private fun parseHour(tokens: List<String>, morning: Boolean, night: Boolean): Int? {
        for ((i, token) in tokens.withIndex()) {
            val digits = token.takeWhile { it.isDigit() }
            val value = digits.toIntOrNull() ?: continue
            if (value !in 0..24) continue
            val afterSaat = i > 0 && tokens[i - 1] == "saat"
            val clockStyle = i + 1 < tokens.size && tokens[i + 1] in listOf("00", "30", "15", "45")
            if (!afterSaat && !clockStyle) continue
            if (value in 1..6 && !morning && !night) return value + 12
            return value % 24
        }
        return null
    }

    // Location

    private fun parseProvince(tokens: List<String>): TurkishProvinces.Province? {
        val matches = mutableListOf<Pair<TurkishProvinces.Province, String>>()
        for (token in tokens) {
            for (province in TurkishProvinces.all) {
                val name = TurkishProvinces.normalize(province.name)
                if (!token.startsWith(name)) continue
                val suffix = token.substring(name.length)
                if (suffix in caseSuffixes) matches += province to suffix
            }
        }
        // "İstanbul'dan Ankara'ya" → the destination (dative) wins; otherwise the last mentioned.
        return matches.lastOrNull { it.second in dativeSuffixes }?.first ?: matches.lastOrNull()?.first
    }

    // Topic

    private fun parseTopic(has: (Array<String>) -> Boolean): WeatherTopic {
        val rainWords = has(arrayOf("yagmur", "yagis", "yagacak", "yagar", "islan", "saganak", "kar ", "kar yag"))
        if (rainWords && has(arrayOf("kacta", "ne zaman", "saat kac", "ne vakit", "baslayacak", "baslar"))) return WeatherTopic.RAIN_TIMING
        if (has(arrayOf("semsiye"))) return WeatherTopic.UMBRELLA
        if (has(arrayOf("giy", "kiyafet", "mont", "ceket", "kazak", "sweatshirt", "hirka", "mont yeter"))) return WeatherTopic.CLOTHING
        if (has(arrayOf("gunes kremi", "krem", "uv"))) return WeatherTopic.UV
        if (has(arrayOf("disari", "cikabilir", "yuruyus", "piknik"))) return WeatherTopic.GOING_OUT
        if (rainWords) return WeatherTopic.RAIN
        if (has(arrayOf("ruzgar", "firtina", "esinti"))) return WeatherTopic.WIND
        if (has(arrayOf("derece", "sicaklik", "soguk", "sicak", "serin", "isi"))) return WeatherTopic.TEMPERATURE
        if (has(arrayOf("gidece", "gidiyor", "gitmek", "gitcem", "yolculuk", "seyahat", "tatil", "ziyaret"))) return WeatherTopic.TRAVEL
        return WeatherTopic.OVERVIEW
    }

    companion object {
        val caseSuffixes = setOf(
            "", "da", "de", "ta", "te", "a", "e", "ya", "ye", "dan", "den", "tan", "ten", "nin", "in", "un", "nun", "daki", "deki",
        )
        val dativeSuffixes = setOf("a", "e", "ya", "ye")
    }
}
