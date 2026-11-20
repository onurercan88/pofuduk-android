package com.pofuduk.app.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Turkish speech-to-text, one utterance at a time (main thread only). The microphone is used only between
 * [start] and the final result/[stop]. On-device recognition is used whenever the device offers it;
 * otherwise offline is preferred and the UI discloses that audio may be processed by the recognition service.
 */
class SpeechRecognitionService(context: Context) {
    private val context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context) || isOnDeviceSupported

    val isOnDeviceSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * [onResult] (text, isFinal) — the final call comes exactly once per session (empty text on error/silence).
     * [onError] gets a Turkish message for failures the user can act on. [onNotice] gets the disclosure when
     * recognition falls back from on-device to the regular service (e.g. no Turkish on-device language pack).
     */
    fun start(onResult: (String, Boolean) -> Unit, onError: (String) -> Unit, onNotice: (String) -> Unit = {}) {
        start(onDevice = isOnDeviceSupported, onResult, onError, onNotice)
    }

    private fun start(onDevice: Boolean, onResult: (String, Boolean) -> Unit, onError: (String) -> Unit, onNotice: (String) -> Unit) {
        stop()
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        var finished = false
        fun finish(text: String) {
            if (finished) return
            finished = true
            onResult(text, true)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { if (!finished) onResult(it, false) }
            }

            override fun onResults(results: Bundle?) = finish(best(results).orEmpty())

            override fun onError(error: Int) {
                val languageMissing = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (onDevice && languageMissing && SpeechRecognizer.isRecognitionAvailable(context)) {
                    finished = true // this session is replaced, not finished
                    onNotice(SERVER_NOTICE)
                    start(onDevice = false, onResult, onError, onNotice)
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        onError("Sesli soru için mikrofon izni gerekiyor. Yazarak da sorabilirsin.")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        onError("Bu cihazda Türkçe konuşma tanıma paketi yüklü değil. Telefonunun dil ayarlarından indirebilir ya da yazarak sorabilirsin.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        onError("Konuşma tanıma şu an internete ulaşamadı. Yazarak sorabilirsin.")
                    else -> onError("Şu an konuşma tanıma kullanılamıyor. Yazarak sorabilirsin.")
                }
                finish("")
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    /** Ends listening; a final result may still arrive for what was already said. */
    fun finishListening() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        const val SERVER_NOTICE =
            "Bu cihazda konuşman, tanınmak üzere cihazın konuşma tanıma servisine (genellikle Google) gönderilebilir."
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
