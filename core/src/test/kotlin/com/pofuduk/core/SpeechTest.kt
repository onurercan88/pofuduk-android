package com.pofuduk.core

import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.TapReaction
import com.pofuduk.core.model.TapReactionPlayback
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.speech.LipSync
import com.pofuduk.core.speech.MouthPose
import com.pofuduk.core.speech.PlaybackStatus
import com.pofuduk.core.speech.SentenceSegmenter
import com.pofuduk.core.speech.SpokenWordCue
import com.pofuduk.core.speech.SubtitleTrack
import com.pofuduk.core.speech.TextRange
import com.pofuduk.core.util.plusSeconds
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LipSyncTest {
    private val start = Instant.ofEpochSecond(1000)

    private fun pose(word: String, atLetter: Double, rate: Double = 1.0): MouthPose {
        val cue = SpokenWordCue.of(word, start, rate)
        return LipSync.pose(cue, start.plusSeconds(atLetter * cue.secondsPerLetter))
    }

    @Test fun vowelsOpenAndLipConsonantsClose() {
        assertEquals(0.0, pose("mama", 0.1).open)
        assertEquals(1.0, pose("mama", 1.1).open)
        assertEquals(0.0, pose("mama", 2.1).open)
    }

    @Test fun roundVowels() {
        assertEquals(1.0, pose("o", 0.1).round)
        assertEquals(0.0, pose("i", 0.1).round)
        // Turkish lowercasing: "I" is "ı", "İ" is "i" — both spread.
        assertEquals(1.0, pose("UZUN", 0.1).round)
        assertEquals(LipSync.pose('i'), pose("İZMİR", 0.1))
    }

    @Test fun mouthClosesAfterTheWord() {
        assertEquals(MouthPose.REST, pose("a", 5.0))
        assertTrue(pose("a", 1.5).open > MouthPose.REST.open)
    }

    @Test fun blendsTowardsNextLetter() {
        val mid = pose("am", 0.75).open
        assertTrue(mid < 1 && mid > 0)
    }

    @Test fun fasterSpeechMovesFaster() {
        assertTrue(SpokenWordCue.of("merhaba", start, 1.25).secondsPerLetter < SpokenWordCue.of("merhaba", start, 1.0).secondsPerLetter)
    }

    @Test fun digitsAndPunctuationAreIgnored() {
        assertEquals(MouthPose.REST, pose("12,", 0.0))
        assertEquals(1.0, pose("a!", 0.1).open)
    }
}

class SubtitleTrackTest {
    @Test fun segmenterKeepsClockTimesAndDecimals() {
        assertEquals(
            listOf("Saat 14.00 civarında yağmur var.", "2.5 mm bekleniyor!", "Hazır mısın?"),
            SentenceSegmenter.split("Saat 14.00 civarında yağmur var. 2.5 mm bekleniyor! Hazır mısın?"),
        )
    }

    @Test fun progressionFollowsCallbacks() {
        var t = SubtitleTrack(listOf("Merhaba.", "Bugün güneşli."))
        assertEquals(PlaybackStatus.IDLE, t.status)
        t = t.didStart(0)
        assertEquals(PlaybackStatus.SPEAKING, t.status)
        assertEquals("Merhaba.", t.currentSentence)
        t = t.willSpeak(TextRange(0, 7), 0)
        assertEquals(TextRange(0, 7), t.wordRange)
        t = t.didFinish(0)
        assertEquals(PlaybackStatus.SPEAKING, t.status)
        t = t.didStart(1)
        assertEquals(2, t.spokenSentences.size)
        t = t.didFinish(1)
        assertEquals(PlaybackStatus.FINISHED, t.status)
        assertEquals("Bugün güneşli.", t.currentSentence, "Last sentence stays visible")
    }

    @Test fun pauseResumeStop() {
        var t = SubtitleTrack(listOf("Bir.", "İki.")).didStart(0)
        t = t.didPause()
        assertEquals(PlaybackStatus.PAUSED, t.status)
        t = t.didResume()
        assertEquals(PlaybackStatus.SPEAKING, t.status)
        t = t.didStop()
        assertEquals(PlaybackStatus.STOPPED, t.status)
        assertEquals("Bir.", t.currentSentence)
        assertEquals("Bir.\nİki.", t.transcript)
    }

    @Test fun invalidRangeIsIgnored() {
        val t = SubtitleTrack(listOf("Kısa.")).didStart(0).willSpeak(TextRange(3, 50), 0)
        assertNull(t.wordRange)
    }

    @Test fun soundDisabledShowsEverything() {
        val t = SubtitleTrack(listOf("Bir.", "İki.")).showAllWithoutAudio()
        assertEquals(PlaybackStatus.FINISHED, t.status)
        assertEquals(listOf("Bir.", "İki."), t.spokenSentences)
    }
}

class TapReactionTest {
    @Test fun reactionFollowsWhatPofudukWears() {
        assertEquals(TapReaction.TOSS_GLASSES, TapReaction.choose(WeatherTheme.SUNNY, CharacterMood.IDLE))
        assertEquals(TapReaction.TWIRL_UMBRELLA, TapReaction.choose(WeatherTheme.RAINY, CharacterMood.IDLE))
        assertEquals(TapReaction.SPIN_UMBRELLA_HAT, TapReaction.choose(WeatherTheme.STORMY, CharacterMood.WARNING))
        for (theme in listOf(WeatherTheme.CLOUDY, WeatherTheme.WINDY, WeatherTheme.SNOWY, WeatherTheme.NIGHT)) {
            assertEquals(TapReaction.DANCE, TapReaction.choose(theme, CharacterMood.IDLE), "$theme")
        }
    }

    @Test fun noGlassesToTossWhileAsleepOrInError() {
        assertEquals(TapReaction.DANCE, TapReaction.choose(WeatherTheme.SUNNY, CharacterMood.SLEEPING))
        assertEquals(TapReaction.DANCE, TapReaction.choose(WeatherTheme.SUNNY, CharacterMood.ERROR))
    }

    @Test fun laughVariesPerTapAndWraps() {
        val n = TapReaction.laughs.size
        assertNotEquals(TapReaction.laugh(0), TapReaction.laugh(1))
        assertEquals(TapReaction.laugh(0), TapReaction.laugh(n))
        assertEquals(TapReaction.laughs[n - 1], TapReaction.laugh(-1))
    }

    @Test fun progressOnlyWhilePlaying() {
        val playback = TapReactionPlayback(TapReaction.DANCE, "Hihi!", Instant.ofEpochSecond(1000))
        assertNull(playback.progress(999.0))
        assertEquals(0.0, playback.progress(1000.0))
        assertEquals(0.5, assertNotNull(playback.progress(1000 + TapReaction.DURATION / 2)), 1e-9)
        assertNull(playback.progress(1000 + TapReaction.DURATION + 0.01))
    }
}
