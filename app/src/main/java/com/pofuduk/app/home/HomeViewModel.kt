package com.pofuduk.app.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.pofuduk.app.speech.SpeechListener
import com.pofuduk.app.speech.SpeechService
import com.pofuduk.core.conversation.ConversationManager
import com.pofuduk.core.conversation.WeatherAnswerEngine
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.ClothingRecommendation
import com.pofuduk.core.engine.ClothingRecommendationEngine
import com.pofuduk.core.engine.WeatherAnalysis
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.Briefing
import com.pofuduk.core.model.CharacterAppearance
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.TapReaction
import com.pofuduk.core.model.TapReactionPlayback
import com.pofuduk.core.model.UserPreferences
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.speech.SpokenWordCue
import com.pofuduk.core.speech.SubtitleTrack
import com.pofuduk.core.speech.TextRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

data class HomeUiState(
    val scenario: MockWeatherData.Scenario,
    val snapshot: WeatherSnapshot,
    val analysis: WeatherAnalysis,
    val clothing: ClothingRecommendation?,
    /** Advice bullets of the briefing (kept while answers are spoken). */
    val advice: List<String>,
    /** What is being (or was last) said: the briefing or an answer. */
    val message: Briefing,
    val track: SubtitleTrack,
    val restingMood: CharacterMood,
    val isSpeaking: Boolean = false,
    val speechCue: SpokenWordCue? = null,
    val tapReaction: TapReactionPlayback? = null,
    val soundOn: Boolean = true,
    /** null while TextToSpeech is starting. */
    val speechAvailable: Boolean? = null,
    val lastQuestion: String? = null,
) {
    val appearance: CharacterAppearance
        get() = CharacterAppearance(
            mood = if (isSpeaking) CharacterMood.SPEAKING else restingMood,
            theme = analysis.theme,
            speechCue = speechCue,
            tapReaction = tapReaction,
        )
}

/**
 * Home screen state. Phase 1: built-in, clearly labelled demo data (no network, no location).
 * All wording comes from the core engines; this class only wires them to speech and UI.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application), SpeechListener {
    private val preferences = UserPreferences()
    private val summary = WeatherSummaryEngine()
    private val clothingEngine = ClothingRecommendationEngine()
    private val answers = WeatherAnswerEngine()
    private val conversation = ConversationManager()
    private var tapCount = 0
    private var autoSpoken = false

    private val _state = MutableStateFlow(build(MockWeatherData.Scenario.SUNNY))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val speech = SpeechService(application, this)

    private fun build(scenario: MockWeatherData.Scenario, previous: HomeUiState? = null): HomeUiState {
        val now = Instant.now()
        val snapshot = MockWeatherData.snapshot(scenario, now)
        val analysis = summary.analyze(snapshot, now)
        val clothing = clothingEngine.recommend(snapshot, now = now)
        val briefing = CharacterPersonalityEngine(preferences.style, preferences.briefingLength).briefing(analysis, clothing = clothing)
        return HomeUiState(
            scenario = scenario, snapshot = snapshot, analysis = analysis, clothing = clothing, advice = briefing.advice,
            message = briefing, track = SubtitleTrack.load(briefing.sentences).showAllWithoutAudio(),
            restingMood = briefing.restingMood,
            soundOn = previous?.soundOn ?: true, speechAvailable = previous?.speechAvailable,
        )
    }

    // User actions

    fun selectScenario(scenario: MockWeatherData.Scenario) {
        speech.stop()
        conversation.reset()
        _state.value = build(scenario, _state.value)
    }

    /** Re-analyses the (demo) forecast at the current time and speaks the briefing. */
    fun playBriefing() {
        val fresh = build(_state.value.scenario, _state.value)
        _state.value = fresh
        say(fresh.message, question = null)
    }

    fun stop() = speech.stop()

    fun toggleSound() {
        val on = !_state.value.soundOn
        if (!on) speech.stop()
        _state.update { it.copy(soundOn = on) }
    }

    /** Typed question (voice input arrives in a later phase). */
    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty()) return
        val now = Instant.now()
        val query = conversation.interpret(question, now)
        val current = _state.value
        // Another province: demo data for that place (still labelled "Örnek veri" in the UI).
        val snapshot = query.province?.let { MockWeatherData.snapshot(current.scenario, now, it.place.copy(source = LocationSource.DEMO)) }
            ?: current.snapshot
        say(answers.answer(query, snapshot, now), question)
    }

    fun onCharacterTap() {
        val s = _state.value
        if (s.isSpeaking) return
        val reaction = TapReaction.choose(s.analysis.theme, s.restingMood)
        val laugh = TapReaction.laugh(tapCount++)
        _state.update { it.copy(tapReaction = TapReactionPlayback(reaction, laugh, Instant.now())) }
        if (s.soundOn) speech.speakAside(laugh, preferences.clampedSpeechRate)
    }

    private fun say(message: Briefing, question: String?) {
        val s = _state.value
        val track = SubtitleTrack.load(message.sentences)
        _state.update {
            it.copy(message = message, track = track, restingMood = message.restingMood, lastQuestion = question, speechCue = null, isSpeaking = false)
        }
        val spoken = s.soundOn && speech.speak(message.sentences, preferences.clampedSpeechRate)
        if (!spoken) _state.update { it.copy(track = it.track.showAllWithoutAudio()) }
    }

    // SpeechListener (binder thread → atomic StateFlow updates)

    override fun onReady(available: Boolean) {
        _state.update { it.copy(speechAvailable = available) }
        if (available && !autoSpoken) {
            autoSpoken = true
            playBriefing()
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
            val finished = track.status == com.pofuduk.core.speech.PlaybackStatus.FINISHED
            it.copy(track = track, isSpeaking = !finished && it.isSpeaking, speechCue = if (finished) null else it.speechCue)
        }
    }

    override fun onStopped() {
        _state.update { it.copy(track = it.track.didStop(), isSpeaking = false, speechCue = null) }
    }

    override fun onCleared() {
        speech.shutdown()
    }
}
