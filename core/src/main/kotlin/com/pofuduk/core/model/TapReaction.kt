package com.pofuduk.core.model

import com.pofuduk.core.util.epochSecondsDouble
import java.time.Instant

/**
 * Just-for-fun reaction when Pofuduk is tapped while not speaking.
 * Depends on what Pofuduk is wearing: glasses get tossed, an umbrella gets played with, otherwise a dance.
 */
enum class TapReaction {
    TOSS_GLASSES, TWIRL_UMBRELLA, SPIN_UMBRELLA_HAT, DANCE;

    companion object {
        fun choose(theme: WeatherTheme, mood: CharacterMood): TapReaction {
            if (CharacterAppearance.wearsSunglasses(theme, mood)) return TOSS_GLASSES
            return when (theme) {
                WeatherTheme.RAINY -> TWIRL_UMBRELLA
                WeatherTheme.STORMY -> SPIN_UMBRELLA_HAT
                else -> DANCE
            }
        }

        /** Giggle shown in a little bubble (and spoken when sound is on); varies with each tap. */
        val laughs = listOf("Hihi!", "Ha ha ha!", "Gıdıklanıyorum!", "Hi hi hi, bir daha!", "Vay, ne eğlenceli!")

        fun laugh(forTap: Int): String = laughs[((forTap % laughs.size) + laughs.size) % laughs.size]

        /** Seconds. */
        const val DURATION: Double = 1.8
    }
}

/** A reaction in progress. `startedAt` is wall-clock time, matched against the character's animation clock. */
data class TapReactionPlayback(val reaction: TapReaction, val laugh: String, val startedAt: Instant) {
    /** 0...1 while playing, null before/after. [epochSeconds] is wall-clock time in seconds since 1970. */
    fun progress(epochSeconds: Double): Double? {
        val p = (epochSeconds - startedAt.epochSecondsDouble) / TapReaction.DURATION
        return if (p in 0.0..1.0) p else null
    }
}
