package com.pofuduk.core.speech

/**
 * Text sent to the speech synthesizer, derived from a subtitle sentence. Subtitles stay exactly as written; only
 * the spoken form changes (clock times, abbreviations). [subtitleRange] maps the synthesizer's word ranges back
 * so the highlighted subtitle word stays right. Ranges are UTF-16 offsets, like TextToSpeech `onRangeStart`.
 */
class SpokenText internal constructor(
    val subtitle: String,
    val text: String,
    /** Replaced pieces, in order: range in the subtitle and range in the spoken text. */
    private val replacements: List<Pair<TextRange, TextRange>>,
) {
    /**
     * A word inside a replacement maps to the whole replaced subtitle piece; text between replacements is
     * shifted by the length difference so far.
     */
    fun subtitleRange(spoken: TextRange): TextRange {
        var delta = 0
        for ((sub, spk) in replacements) {
            val spokenEnd = spk.location + spk.length
            if (spoken.location < spk.location) break
            if (spoken.location < spokenEnd) return sub
            delta = (sub.location + sub.length) - spokenEnd
        }
        val location = maxOf(0, spoken.location + delta)
        val maxLength = maxOf(0, subtitle.length - location)
        return TextRange(location, minOf(spoken.length, maxLength))
    }

    override fun equals(other: Any?): Boolean = other is SpokenText && other.subtitle == subtitle && other.text == text
    override fun hashCode(): Int = 31 * subtitle.hashCode() + text.hashCode()
    override fun toString(): String = "SpokenText($text)"
}

/** Turkish speech fixes for words the TTS voice reads badly. Only touches forms the app itself writes. */
object SpeechTextNormalizer {
    private class Rule(val regex: Regex, val replace: (MatchResult) -> String)

    private val rules = listOf(
        // Clock times written as "14.00" / "09.30" (TurkishFormatting.clockTime) were read like decimals.
        // "14.00" → "14", "09.30" → "9 30", keeping a suffix such as "'te" attached.
        Rule(Regex("""\b([01]?\d|2[0-3])\.([0-5]\d)\b""")) { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (minute == 0) "$hour" else "$hour $minute"
        },
        Rule(Regex("""\bUV\b""")) { "ultraviyole" },
        Rule(Regex("""\bMGM\b""")) { "Meteoroloji" },
    )

    fun spoken(sentence: String): SpokenText {
        val matches = mutableListOf<Pair<IntRange, String>>()
        for (rule in rules) {
            for (m in rule.regex.findAll(sentence)) {
                // Rules never overlap in practice; skip any overlap rather than produce garbled text.
                if (matches.any { (r, _) -> r.first <= m.range.last && m.range.first <= r.last }) continue
                matches += m.range to rule.replace(m)
            }
        }
        matches.sortBy { it.first.first }

        val text = StringBuilder()
        val replacements = mutableListOf<Pair<TextRange, TextRange>>()
        var cursor = 0
        for ((range, replacement) in matches) {
            text.append(sentence, cursor, range.first)
            replacements += TextRange(range.first, range.last + 1 - range.first) to TextRange(text.length, replacement.length)
            text.append(replacement)
            cursor = range.last + 1
        }
        text.append(sentence, cursor, sentence.length)
        return SpokenText(sentence, text.toString(), replacements)
    }
}
