package com.pofuduk.core.speech

import com.pofuduk.core.util.TurkishLocale
import com.pofuduk.core.util.secondsSince
import java.time.Instant

/** The word the speech engine just started, as reported by the synthesizer's range callback. */
data class SpokenWordCue(
    val word: String,
    val startedAt: Instant,
    /** Estimated time per letter at the current speech rate (seconds). */
    val secondsPerLetter: Double,
) {
    companion object {
        fun of(word: String, startedAt: Instant, rateMultiplier: Double = 1.0) =
            SpokenWordCue(word, startedAt, DEFAULT_SECONDS_PER_LETTER / maxOf(0.5, rateMultiplier))

        /** At the default rate Turkish TTS speaks roughly 14 letters per second. */
        const val DEFAULT_SECONDS_PER_LETTER = 0.07
    }
}

/** Mouth pose for one moment. `open` 0 = closed … 1 = wide open; `round` 0 = spread … 1 = rounded (o/u). */
data class MouthPose(val open: Double, val round: Double) {
    companion object {
        val REST = MouthPose(open = 0.12, round = 0.0)
    }
}

/**
 * Approximate lip sync from letters (Turkish is written close to how it is spoken).
 * The synthesizer only reports word starts, so letters inside a word are timed evenly.
 */
object LipSync {
    fun pose(letter: Char): MouthPose = when (letter) {
        'a', 'â' -> MouthPose(1.0, 0.0)
        'e' -> MouthPose(0.75, 0.0)
        'ı', 'i', 'î' -> MouthPose(0.45, 0.0)
        'o' -> MouthPose(0.85, 1.0)
        'ö' -> MouthPose(0.7, 0.9)
        'u', 'û' -> MouthPose(0.5, 1.0)
        'ü' -> MouthPose(0.45, 1.0)
        'm', 'b', 'p' -> MouthPose(0.0, 0.0)
        'f', 'v' -> MouthPose(0.12, 0.0)
        'ş', 'ç', 'c', 'j' -> MouthPose(0.25, 0.5)
        else -> MouthPose(0.3, 0.0)
    }

    /**
     * Pose at [at]. Blends towards the next letter so the mouth moves smoothly;
     * after the word it closes to rest within a short tail.
     */
    fun pose(cue: SpokenWordCue, at: Instant): MouthPose {
        val letters = cue.word.lowercase(TurkishLocale).filter { it.isLetter() }
        if (letters.isEmpty() || cue.secondsPerLetter <= 0) return MouthPose.REST
        val elapsed = at.secondsSince(cue.startedAt)
        if (elapsed < 0) return MouthPose.REST
        val position = elapsed / cue.secondsPerLetter
        val index = position.toInt()
        if (index >= letters.length) {
            // Close over ~2 letters' time after the word ends.
            val tail = minOf(1.0, (position - letters.length) / 2)
            return blend(pose(letters.last()), MouthPose.REST, tail)
        }
        val current = pose(letters[index])
        val next = if (index + 1 < letters.length) pose(letters[index + 1]) else MouthPose.REST
        // Hold each letter for the first half, then move towards the next one.
        val fraction = maxOf(0.0, (position - index) - 0.5) * 2
        return blend(current, next, fraction)
    }

    internal fun blend(a: MouthPose, b: MouthPose, t: Double): MouthPose {
        val k = t.coerceIn(0.0, 1.0)
        return MouthPose(a.open + (b.open - a.open) * k, a.round + (b.round - a.round) * k)
    }
}
