package com.pofuduk.app.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
class SpeechService(context: Context, private val listener: SpeechListener) {
    private val session = AtomicInteger(0)
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
        bestTurkishVoice()?.let { tts.voice = it }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                parse(utteranceId)?.let { listener.onSentenceStart(it) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                parse(utteranceId)?.let { listener.onWord(it, start, end) }
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

    /** Prefers an installed, offline, high-quality Turkish voice (no network voice → nothing leaves the device). */
    private fun bestTurkishVoice(): Voice? = runCatching {
        tts.voices
            ?.filter { it.locale.language == "tr" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            ?.maxByOrNull { it.quality }
    }.getOrNull()

    /** Sentence index for utterances of the current session; null for stale or foreign utterances. */
    private fun parse(utteranceId: String): Int? {
        val parts = utteranceId.split(':')
        if (parts.size != 2 || parts[0].toIntOrNull() != session.get()) return null
        return parts[1].toIntOrNull()
    }

    /** Speaks [sentences] in order. [rate] 1.0 = normal. Returns false when speech is unavailable. */
    fun speak(sentences: List<String>, rate: Double): Boolean {
        if (!ready || !isTurkishAvailable) return false
        val id = session.incrementAndGet()
        tts.stop()
        tts.setSpeechRate(rate.toFloat())
        sentences.forEachIndexed { i, s ->
            tts.speak(s, TextToSpeech.QUEUE_ADD, Bundle(), "$id:$i")
        }
        return true
    }

    /** A short sound that is not part of a briefing (e.g. a giggle). Never interrupts a briefing. */
    fun speakAside(text: String, rate: Double) {
        if (!ready || !isTurkishAvailable || tts.isSpeaking) return
        tts.setSpeechRate(rate.toFloat())
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
