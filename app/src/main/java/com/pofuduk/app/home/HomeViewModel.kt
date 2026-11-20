package com.pofuduk.app.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pofuduk.app.graph
import com.pofuduk.core.notification.MorningBriefingEngine
import com.pofuduk.app.speech.SpeechListener
import com.pofuduk.app.speech.SpeechRecognitionService
import com.pofuduk.app.speech.SpeechService
import com.pofuduk.core.conversation.ConversationManager
import com.pofuduk.core.conversation.WeatherQueryService
import com.pofuduk.core.mock.DemoWeatherRepository
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.ClothingRecommendation
import com.pofuduk.core.engine.ClothingRecommendationEngine
import com.pofuduk.core.engine.WeatherAnalysis
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.CharacterAppearance
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.LocationIssue
import com.pofuduk.core.model.LocationPermission
import com.pofuduk.core.model.LocationResolution
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.TapReaction
import com.pofuduk.core.model.TapReactionPlayback
import com.pofuduk.core.model.UserPreferences
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.provider.WeatherError
import com.pofuduk.core.provider.WeatherException
import com.pofuduk.core.speech.PlaybackStatus
import com.pofuduk.core.speech.SpokenWordCue
import com.pofuduk.core.speech.SubtitleTrack
import com.pofuduk.core.speech.TextRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface LocationPrompt {
    data object Explanation : LocationPrompt
    data class Issue(val issue: LocationIssue) : LocationPrompt
}

sealed interface LoadState {
    data object Loading : LoadState
    data object Loaded : LoadState
    data class Failed(val reason: String) : LoadState
    data class NeedsLocation(val prompt: LocationPrompt) : LoadState
}

data class HomeUiState(
    val loadState: LoadState = LoadState.Loading,
    /** Non-null = labelled demo mode with built-in sample data. */
    val demoScenario: MockWeatherData.Scenario? = null,
    val snapshot: WeatherSnapshot? = null,
    val analysis: WeatherAnalysis? = null,
    val clothing: ClothingRecommendation? = null,
    /** Advice bullets of the briefing (kept while answers are spoken). */
    val advice: List<String> = emptyList(),
    /** What is being (or was last) said: the briefing, an answer or a status message. */
    val message: Briefing = Briefing(emptyList(), CharacterMood.THINKING, WeatherTheme.CLOUDY),
    val track: SubtitleTrack = SubtitleTrack(),
    val restingMood: CharacterMood = CharacterMood.THINKING,
    val isSpeaking: Boolean = false,
    val speechCue: SpokenWordCue? = null,
    val tapReaction: TapReactionPlayback? = null,
    val soundOn: Boolean = true,
    /** null while TextToSpeech is starting. */
    val speechAvailable: Boolean? = null,
    val lastQuestion: String? = null,
    val isRefreshing: Boolean = false,
    val showLocationPicker: Boolean = false,
    val showSettings: Boolean = false,
    val searchResults: List<Place> = emptyList(),
    /** Question being typed or dictated (partial speech results appear here). */
    val inputText: String = "",
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    /** Microphone status/disclosure shown above the ask bar. */
    val microphoneMessage: String? = null,
) {
    val theme: WeatherTheme get() = analysis?.theme ?: WeatherTheme.CLOUDY

    val appearance: CharacterAppearance
        get() {
            val mood = when {
                isSpeaking -> CharacterMood.SPEAKING
                isListening -> CharacterMood.LISTENING
                isThinking -> CharacterMood.THINKING
                loadState is LoadState.Loading -> CharacterMood.THINKING
                loadState is LoadState.Failed -> CharacterMood.ERROR
                loadState is LoadState.NeedsLocation -> CharacterMood.LISTENING
                else -> restingMood
            }
            return CharacterAppearance(mood, theme, speechCue = speechCue, tapReaction = tapReaction)
        }

    val isStale: Boolean get() = snapshot?.isStale(Instant.now()) == true
}

/**
 * Home screen state machine: location (explanation before any system prompt) → forecast (Open-Meteo, cached)
 * → engines → speech. All wording comes from core; this class only wires it to speech and UI.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application), SpeechListener {
    private val preferences = UserPreferences()
    private val summary = WeatherSummaryEngine()
    private val clothingEngine = ClothingRecommendationEngine()
    private val conversation = ConversationManager()
    private val personality get() = CharacterPersonalityEngine(preferences.style, preferences.briefingLength)

    private val graph = application.graph
    private val location = graph.location
    private val repository = graph.repository
    val notifications = graph.notifications

    private var tapCount = 0
    private var pendingAutoSpeak = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var listenTimeout: Job? = null
    private val recognizer = SpeechRecognitionService(application)

    /** Questions use the same data as the screen: live repository, or the demo scenario in demo mode. */
    private fun queryService(): WeatherQueryService =
        WeatherQueryService(_state.value.demoScenario?.let { DemoWeatherRepository(it) } ?: repository)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val speech = SpeechService(application, this)

    init {
        load(autoSpeak = true)
    }

    // Loading

    private fun load(autoSpeak: Boolean, forceRefresh: Boolean = false) {
        loadJob?.cancel()
        speech.stop()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadState = LoadState.Loading, isSpeaking = false, speechCue = null, track = SubtitleTrack()) }
            val demo = _state.value.demoScenario
            val started = System.currentTimeMillis()
            val place: Place = if (demo != null) {
                MockWeatherData.istanbul
            } else {
                when (val r = location.resolve().also { Log.d(TAG, "location: ${System.currentTimeMillis() - started} ms") }) {
                    is LocationResolution.Resolved -> r.place
                    LocationResolution.NeedsPermissionExplanation -> return@launch enterNeedsLocation(LocationPrompt.Explanation)
                    is LocationResolution.NeedsUserChoice -> return@launch enterNeedsLocation(LocationPrompt.Issue(r.issue))
                }
            }
            val now = Instant.now()
            val snapshot = try {
                if (demo != null) MockWeatherData.snapshot(demo, now, place) else repository.snapshot(place, forceRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = ((e as? WeatherException)?.error ?: WeatherError.NETWORK).turkishExplanation
                val briefing = personality.unavailableBriefing(reason)
                _state.update {
                    it.copy(
                        loadState = LoadState.Failed(reason), snapshot = null, analysis = null, clothing = null, advice = emptyList(),
                        message = briefing, track = SubtitleTrack.load(briefing.sentences).showAllWithoutAudio(),
                        restingMood = briefing.restingMood, isRefreshing = false, lastQuestion = null,
                    )
                }
                return@launch
            }
            Log.d(TAG, "forecast ready: ${System.currentTimeMillis() - started} ms (${snapshot.source})")
            val analysis = summary.analyze(snapshot, now)
            val clothing = clothingEngine.recommend(snapshot, now = now)
            val zone = snapshot.place.timeZone
            // "Pofuduk Günaydın": once per day, from the chosen time until noon (real forecasts only).
            val morning = demo == null && notifications.shouldGreet(zone, now)
            val briefing = if (morning) {
                notifications.markGreeted(zone, now)
                MorningBriefingEngine().briefing(snapshot, analysis, clothing = clothing, now = now)
            } else {
                personality.briefing(analysis, clothing = clothing)
            }
            if (demo == null) notifications.refresh(snapshot) // notifications are planned only from real forecasts
            _state.update {
                it.copy(
                    loadState = LoadState.Loaded, snapshot = snapshot, analysis = analysis, clothing = clothing,
                    advice = briefing.advice, message = briefing,
                    track = SubtitleTrack.load(briefing.sentences).showAllWithoutAudio(),
                    restingMood = briefing.restingMood, isRefreshing = false, lastQuestion = null,
                )
            }
            val speaks = !morning || notifications.state.value.morning.speaksAutomatically
            if (autoSpeak && speaks) speakWhenReady(briefing)
        }
    }

    /** No weather is shown or spoken until the user decides how to share their location. */
    private fun enterNeedsLocation(prompt: LocationPrompt) {
        val briefing = personality.locationNeededBriefing()
        _state.update {
            it.copy(
                loadState = LoadState.NeedsLocation(prompt), snapshot = null, analysis = null, clothing = null, advice = emptyList(),
                message = briefing, track = SubtitleTrack.load(briefing.sentences).showAllWithoutAudio(),
                restingMood = briefing.restingMood, isRefreshing = false,
            )
        }
    }

    private fun speakWhenReady(briefing: Briefing) {
        when (_state.value.speechAvailable) {
            null -> pendingAutoSpeak = true
            true -> say(briefing, question = null)
            false -> Unit // text is already shown
        }
    }

    // User actions

    /** Pull to refresh / retry: an explicit request → Pofuduk tells the updated weather. */
    fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        load(autoSpeak = true, forceRefresh = true)
    }

    /** Called with the result of the system prompt, which is shown only after Pofuduk's explanation. */
    fun onPermissionResult() {
        location.permissionAnswered()
        _state.update { it.copy(showLocationPicker = false) }
        conversation.reset()
        load(autoSpeak = true)
    }

    fun selectPlace(place: Place) {
        location.selectManual(place)
        conversation.reset()
        _state.update { it.copy(showLocationPicker = false, searchResults = emptyList()) }
        load(autoSpeak = true, forceRefresh = true)
    }

    fun openSettings() = _state.update { it.copy(showSettings = true) }
    fun closeSettings() = _state.update { it.copy(showSettings = false) }

    fun openLocationPicker() = _state.update { it.copy(showLocationPicker = true, searchResults = emptyList()) }
    fun closeLocationPicker() = _state.update { it.copy(showLocationPicker = false, searchResults = emptyList()) }

    /** District/city search (debounced by the caller's typing; previous search is cancelled). */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            val results = location.search(query)
            _state.update { it.copy(searchResults = results) }
        }
    }

    /** Back from system settings (permissions may have changed) or reopened in the morning (daily greeting). */
    fun onResume() {
        notifications.refreshPermission()
        val current = _state.value
        val zone = current.snapshot?.place?.timeZone
        if (current.loadState == LoadState.Loaded && current.demoScenario == null && zone != null && notifications.shouldGreet(zone)) {
            load(autoSpeak = true)
            return
        }
        val s = current.loadState
        if (s is LoadState.NeedsLocation && s.prompt is LocationPrompt.Issue &&
            location.permission == LocationPermission.AUTHORIZED
        ) {
            load(autoSpeak = true)
        }
    }

    fun setDemo(scenario: MockWeatherData.Scenario?) {
        conversation.reset()
        _state.update { it.copy(demoScenario = scenario, lastQuestion = null) }
        load(autoSpeak = true)
    }

    fun playBriefing() {
        val s = _state.value
        if (s.loadState == LoadState.Loaded && s.analysis != null) {
            val briefing = personality.briefing(summary.analyze(s.snapshot!!, Instant.now()), clothing = s.clothing)
            say(briefing, question = null)
        } else {
            say(s.message.copy(), question = null)
        }
    }

    /** Stops speaking and listening (also called when the app goes to the background). */
    fun stop() {
        speech.stop()
        endListening(sendQuestion = false)
    }

    fun toggleSound() {
        val on = !_state.value.soundOn
        if (!on) speech.stop()
        _state.update { it.copy(soundOn = on) }
    }

    /** The field grows to 3 lines, so the Return key inserts "\n": treat it as "send" (like iOS). */
    fun onInputChange(text: String) {
        if (text.contains('\n')) {
            _state.update { it.copy(inputText = text.replace("\n", "")) }
            send()
        } else {
            _state.update { it.copy(inputText = text) }
        }
    }

    /** Sends the typed (or dictated) question. */
    fun send() {
        val text = _state.value.inputText
        _state.update { it.copy(inputText = "") }
        ask(text)
    }

    /** Typed or spoken question → answer for the place it names (or the current place), then spoken. */
    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.isThinking) return
        speech.stop()
        val query = conversation.interpret(question, Instant.now())
        _state.update { it.copy(isThinking = true, lastQuestion = question) }
        viewModelScope.launch {
            val answer = queryService().answer(query, currentPlace = _state.value.snapshot?.place)
            _state.update { it.copy(isThinking = false) }
            say(answer, question)
        }
    }

    // Microphone (permission is requested by the UI only when the user taps the mic)

    fun toggleListening(start: () -> Unit) {
        if (_state.value.isListening) finishListening() else start()
    }

    /** Called once RECORD_AUDIO is granted. Listens for one question, at most 10 s. */
    fun startListening() {
        if (!recognizer.isAvailable) {
            _state.update { it.copy(microphoneMessage = "Bu cihazda konuşma tanıma bulunamadı. Yazarak sorabilirsin.") }
            return
        }
        speech.stop()
        _state.update {
            it.copy(
                isListening = true, inputText = "",
                microphoneMessage = if (recognizer.isOnDeviceSupported) null else SpeechRecognitionService.SERVER_NOTICE,
            )
        }
        recognizer.start(
            onResult = { text, isFinal ->
                if (!_state.value.isListening) return@start
                if (text.isNotEmpty()) _state.update { it.copy(inputText = text) }
                if (isFinal) endListening(sendQuestion = true)
            },
            onError = { message -> _state.update { it.copy(microphoneMessage = message) } },
            onNotice = { message -> _state.update { it.copy(microphoneMessage = message) } },
        )
        listenTimeout?.cancel()
        listenTimeout = viewModelScope.launch {
            delay(10_000)
            finishListening()
            delay(3_000) // grace period for the final result of what was already said
            endListening(sendQuestion = true)
        }
    }

    fun microphoneDenied() = _state.update {
        it.copy(microphoneMessage = "Sesli soru için mikrofon izni gerekiyor. Yazarak da sorabilirsin.")
    }

    /** User tapped the mic again: stop recording; the final result is then sent. */
    private fun finishListening() {
        if (_state.value.isListening) recognizer.finishListening()
    }

    private fun endListening(sendQuestion: Boolean) {
        if (!_state.value.isListening) return
        listenTimeout?.cancel()
        recognizer.stop()
        _state.update { it.copy(isListening = false) }
        if (sendQuestion && _state.value.inputText.isNotBlank()) send()
    }

    fun onCharacterTap() {
        val s = _state.value
        if (s.isSpeaking || s.loadState != LoadState.Loaded) return
        val reaction = TapReaction.choose(s.theme, s.restingMood)
        val laugh = TapReaction.laugh(tapCount++)
        _state.update { it.copy(tapReaction = TapReactionPlayback(reaction, laugh, Instant.now())) }
        if (s.soundOn) speech.speakAside(laugh, preferences.clampedSpeechRate)
    }

    private fun say(message: Briefing, question: String?) {
        val s = _state.value
        _state.update {
            it.copy(
                message = message, track = SubtitleTrack.load(message.sentences),
                restingMood = if (it.loadState == LoadState.Loaded) message.restingMood else it.restingMood,
                lastQuestion = question, speechCue = null, isSpeaking = false,
            )
        }
        val spoken = s.soundOn && speech.speak(message.sentences, preferences.clampedSpeechRate)
        if (!spoken) _state.update { it.copy(track = it.track.showAllWithoutAudio()) }
    }

    // SpeechListener (binder thread → atomic StateFlow updates)

    override fun onReady(available: Boolean) {
        _state.update { it.copy(speechAvailable = available) }
        if (pendingAutoSpeak) {
            pendingAutoSpeak = false
            val s = _state.value
            if (available && s.loadState == LoadState.Loaded) say(s.message, question = null)
        }
    }

    override fun onSentenceStart(index: Int) {
        _state.update { it.copy(track = it.track.didStart(index), isSpeaking = true, speechCue = null) }
    }

    override fun onWord(index: Int, start: Int, end: Int) {
        _state.update {
            val sentence = it.track.sentences.getOrNull(index) ?: return@update it
            if (start < 0 || end > sentence.length || start >= end) return@update it
            it.copy(
                track = it.track.willSpeak(TextRange(start, end - start), index),
                speechCue = SpokenWordCue.of(sentence.substring(start, end), Instant.now(), preferences.clampedSpeechRate),
            )
        }
    }

    override fun onSentenceDone(index: Int) {
        _state.update {
            val track = it.track.didFinish(index)
            val finished = track.status == PlaybackStatus.FINISHED
            it.copy(track = track, isSpeaking = !finished && it.isSpeaking, speechCue = if (finished) null else it.speechCue)
        }
    }

    override fun onStopped() {
        _state.update { it.copy(track = it.track.didStop(), isSpeaking = false, speechCue = null) }
    }

    override fun onCleared() {
        recognizer.stop()
        speech.shutdown()
    }

    private companion object {
        /** Timing only — never coordinates or place names. */
        const val TAG = "Pofuduk"
    }
}
