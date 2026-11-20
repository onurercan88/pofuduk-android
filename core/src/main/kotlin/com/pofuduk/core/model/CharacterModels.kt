package com.pofuduk.core.model

import com.pofuduk.core.speech.SpokenWordCue
import kotlinx.serialization.Serializable

/** Behavioural state of the character. Independent from rendering technology. */
enum class CharacterMood { IDLE, SPEAKING, LISTENING, THINKING, HAPPY, SURPRISED, WARNING, SAD, SLEEPING, CELEBRATING, ERROR }

/** Weather-driven look (accessories + ambient effects). */
enum class WeatherTheme {
    SUNNY, RAINY, STORMY, SNOWY, CLOUDY, WINDY, NIGHT;

    val accessibilityDescription: String
        get() = when (this) {
            SUNNY -> "Güneş gözlüklü, mutlu Pofuduk"
            RAINY -> "Küçük şemsiyesiyle Pofuduk"
            STORMY -> "Başında minik şemsiyesiyle, biraz ürkek Pofuduk"
            SNOWY -> "Atkı takmış Pofuduk"
            CLOUDY -> "Sakin Pofuduk"
            WINDY -> "Rüzgârda uçurtma uçuran Pofuduk"
            NIGHT -> "Gece modunda Pofuduk"
        }
}

/** Everything a renderer needs. Rendering consumes only this value. */
data class CharacterAppearance(
    val mood: CharacterMood,
    val theme: WeatherTheme,
    val isMorningGreeting: Boolean = false,
    /** Word being spoken right now (drives the mouth while speaking; null = generic talking motion). */
    val speechCue: SpokenWordCue? = null,
    /** Fun reaction to a tap (glasses toss, umbrella play, dance + giggle). */
    val tapReaction: TapReactionPlayback? = null,
) {
    val wearsSunglasses: Boolean get() = wearsSunglasses(theme, mood)

    companion object {
        /** Sunny look wears sunglasses, except while asleep or showing an error. */
        fun wearsSunglasses(theme: WeatherTheme, mood: CharacterMood): Boolean =
            theme == WeatherTheme.SUNNY && mood != CharacterMood.SLEEPING && mood != CharacterMood.ERROR
    }
}

@Serializable
enum class PersonalityStyle {
    FRIENDLY, PLAYFUL, CALM;

    val turkishName: String
        get() = when (this) {
            FRIENDLY -> "Samimi"
            PLAYFUL -> "Şakacı"
            CALM -> "Sakin"
        }
}

@Serializable
enum class BriefingLength {
    SHORT, DETAILED;

    val turkishName: String get() = if (this == SHORT) "Kısa" else "Ayrıntılı"
}

/** A spoken message. `sentences` are exactly what is spoken and exactly what subtitles show. */
data class Briefing(
    val sentences: List<String>,
    /** Mood the character should show when not actively speaking. */
    val restingMood: CharacterMood,
    val theme: WeatherTheme,
    /** Short advice bullets for the advice section (all derived from data). */
    val advice: List<String> = emptyList(),
) {
    val fullText: String get() = sentences.joinToString(" ")
}
