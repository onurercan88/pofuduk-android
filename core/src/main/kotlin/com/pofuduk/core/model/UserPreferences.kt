package com.pofuduk.core.model

import com.pofuduk.core.store.JsonStore
import com.pofuduk.core.store.KeyValueStore
import kotlinx.serialization.Serializable

@Serializable
enum class SubtitleSize {
    STANDARD, LARGE, EXTRA_LARGE, HUGE;

    val turkishName: String
        get() = when (this) {
            STANDARD -> "Normal"
            LARGE -> "Büyük"
            EXTRA_LARGE -> "Çok büyük"
            HUGE -> "En büyük"
        }
}

/** Personalization stored on device only (no sync, no inferred personal attributes). */
@Serializable
data class UserPreferences(
    val style: PersonalityStyle = PersonalityStyle.FRIENDLY,
    val briefingLength: BriefingLength = BriefingLength.DETAILED,
    /** Multiplier on the natural speech rate (0.8 = slower, 1.25 = faster). */
    val speechRateMultiplier: Double = 1.0,
    /** TextToSpeech voice name; null = best installed Turkish voice. */
    val voiceName: String? = null,
    val subtitleSize: SubtitleSize = SubtitleSize.STANDARD,
) {
    val clampedSpeechRate: Double get() = speechRateMultiplier.coerceIn(SPEECH_RATE_MIN, SPEECH_RATE_MAX)

    companion object {
        const val SPEECH_RATE_MIN = 0.8
        const val SPEECH_RATE_MAX = 1.25
    }
}

class UserPreferencesStore(store: KeyValueStore) {
    private val json = JsonStore(store, "pofuduk.userPreferences", UserPreferences.serializer())
    fun load(): UserPreferences = json.load() ?: UserPreferences()
    fun save(p: UserPreferences) = json.save(p)
    fun clear() = json.clear()
}
