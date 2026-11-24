package com.pofuduk.app.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.pofuduk.core.model.VoiceTone
import com.pofuduk.core.speech.SpeechTextNormalizer
import com.pofuduk.core.speech.SpokenText
import com.pofuduk.core.speech.TextRange
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Callbacks from the synthesizer. Called on a TextToSpeech binder thread — receivers must be thread-safe. */
interface SpeechListener {
    fun onReady(available: Boolean)
    fun onSentenceStart(index: Int)
    /** Character range [start, end) of the word now being spoken, inside sentence [index]. */
    fun onWord(index: Int, start: Int, end: Int)
    fun onSentenceDone(index: Int)
    fun onStopped()
}

/**
 * TextToSpeech (tr-TR) adapter. One utterance per sentence: `onStart` gives exact sentence sync,
 * `onRangeStart` (API 26) gives the word being spoken → subtitles and lip sync use the very strings spoken.
 * No timing estimates.
 */
/** An installed, offline Turkish voice the user can pick. */
data class VoiceOption(val name: String, val label: String)

class SpeechService(
    context: Context,
    private val listener: SpeechListener,
    preferredVoice: String? = null,
    @Volatile private var pitch: Float = VoiceTone.POFUDUK.pitchMultiplier,
) {
    private val session = AtomicInteger(0)
    @Volatile private var preferredVoice: String? = preferredVoice
    /** Spoken forms of the current session's sentences (subtitle ↔ spoken word ranges). */
    @Volatile private var spoken: List<SpokenText> = emptyList()
    @Volatile private var ready = false
    @Volatile var isTurkishAvailable = false
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) configure()
        listener.onReady(isTurkishAvailable)
    }

    private fun configure() {
        val turkish = Locale.forLanguageTag("tr-TR")
        val result = tts.setLanguage(turkish)
        isTurkishAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        applyVoice()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                parse(utteranceId)?.let { listener.onSentenceStart(it) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val index = parse(utteranceId) ?: return
                // The engine reports ranges in the spoken text; map them back to the subtitle sentence.
                val r = spoken.getOrNull(index)?.subtitleRange(TextRange(start, end - start)) ?: TextRange(start, end - start)
                listener.onWord(index, r.location, r.location + r.length)
            }

            override fun onDone(utteranceId: String) {
                parse(utteranceId)?.let { listener.onSentenceDone(it) }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (parse(utteranceId) != null) listener.onStopped()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                if (parse(utteranceId) != null) listener.onStopped()
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                if (parse(utteranceId) != null) listener.onStopped()
            }
        })
    }

    /** Installed, offline Turkish voices only (no network voice → nothing leaves the device). */
    private fun turkishVoices(): List<Voice> = runCatching {
        tts.voices.orEmpty().filter {
            it.locale.language == "tr" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }.sortedByDescending { it.quality }
    }.getOrDefault(emptyList())

    /** Voices for the settings picker ("Ses 1 · Çok iyi kalite"…; engine names are not user-friendly). */
    fun availableVoices(): List<VoiceOption> {
        if (!ready || !isTurkishAvailable) return emptyList()
        return turkishVoices().mapIndexed { i, v ->
            val quality = when {
                v.quality >= Voice.QUALITY_VERY_HIGH -> "çok yüksek kalite"
                v.quality >= Voice.QUALITY_HIGH -> "yüksek kalite"
                else -> "normal kalite"
            }
            VoiceOption(v.name, "Ses ${i + 1} · $quality")
        }
    }

    /** null = best installed Turkish voice. */
    fun setVoice(name: String?) {
        preferredVoice = name
        if (ready) applyVoice()
    }

    private fun applyVoice() {
        val voices = turkishVoices()
        (voices.firstOrNull { it.name == preferredVoice } ?: voices.firstOrNull())?.let { tts.voice = it }
    }

    /** Briefing/answer pitch ([com.pofuduk.core.model.VoiceTone.pitchMultiplier]). */
    fun setPitch(pitch: Float) {
        this.pitch = pitch
    }

    /**
     * True when the voice in use is an installed Turkish voice below "high" quality: Settings then explains how to
     * get a more natural one from the TTS engine's voice data.
     */
    fun canImproveVoice(): Boolean {
        if (!ready || !isTurkishAvailable) return false
        val active = runCatching { tts.voice }.getOrNull() ?: return false
        val installed = turkishVoices().firstOrNull { it.name == active.name } ?: return false
        return installed.quality < Voice.QUALITY_HIGH
    }

    /** Sentence index for utterances of the current session; null for stale or foreign utterances. */
    private fun parse(utteranceId: String): Int? {
        val parts = utteranceId.split(':')
        if (parts.size != 2 || parts[0].toIntOrNull() != session.get()) return null
        return parts[1].toIntOrNull()
    }

    /** Speaks [sentences] in order. [rate] 1.0 = normal. Returns false when speech is unavailable. */
    fun speak(sentences: List<String>, rate: Double): Boolean {
        if (!ready || !isTurkishAvailable) return false
        // Subtitles keep the written form; the voice gets the speech-friendly one ("14.00" → "14").
        val texts = sentences.map(SpeechTextNormalizer::spoken)
        spoken = texts
        val id = session.incrementAndGet()
        tts.stop()
        tts.setSpeechRate(rate.toFloat())
        tts.setPitch(pitch)
        texts.forEachIndexed { i, s ->
            tts.speak(s.text, TextToSpeech.QUEUE_ADD, Bundle(), "$id:$i")
        }
        return true
    }

    /** A short sound that is not part of a briefing (e.g. a giggle). Never interrupts a briefing. */
    fun speakAside(text: String, rate: Double) {
        if (!ready || !isTurkishAvailable || tts.isSpeaking) return
        tts.setSpeechRate(rate.toFloat())
        tts.setPitch(pitch)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "aside")
    }

    fun stop() {
        session.incrementAndGet()
        if (ready) tts.stop()
        listener.onStopped()
    }

    fun shutdown() {
        session.incrementAndGet()
        tts.stop()
        tts.shutdown()
    }
}
