package com.pofuduk.core.speech

/** Splits free text into sentences without breaking clock times ("14.00") or decimals ("2.5"). */
object SentenceSegmenter {
    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        for ((i, ch) in text.withIndex()) {
            current.append(ch)
            if (ch != '.' && ch != '!' && ch != '?') continue
            val prev = if (i > 0) text[i - 1] else ' '
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            if (ch == '.' && prev.isDigit() && next.isDigit()) continue
            if (next.isWhitespace() || i + 1 == text.length) {
                val trimmed = current.toString().trim()
                if (trimmed.isNotEmpty()) sentences += trimmed
                current.clear()
            }
        }
        val rest = current.toString().trim()
        if (rest.isNotEmpty()) sentences += rest
        return sentences
    }
}

/** UTF-16 range, matching TextToSpeech `onRangeStart(start, end)` offsets. */
data class TextRange(val location: Int, val length: Int)

enum class PlaybackStatus { IDLE, SPEAKING, PAUSED, FINISHED, STOPPED }

/**
 * Subtitle state driven only by real speech callbacks (no timing estimates).
 * Sentences are exactly the utterance strings, so subtitles always match speech. Immutable: every event
 * returns a new track (safe to hold in Compose state).
 */
data class SubtitleTrack(
    val sentences: List<String> = emptyList(),
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    /** Sentence currently (or most recently) spoken. Stays visible after finishing so text never vanishes abruptly. */
    val currentIndex: Int? = null,
    /** Word being spoken inside the current sentence. */
    val wordRange: TextRange? = null,
) {
    val currentSentence: String? get() = currentIndex?.let { sentences[it] }
    val transcript: String get() = sentences.joinToString("\n")
    val isActive: Boolean get() = status == PlaybackStatus.SPEAKING || status == PlaybackStatus.PAUSED

    /** Sentences already spoken, including the current one. */
    val spokenSentences: List<String> get() = currentIndex?.let { sentences.subList(0, it + 1) } ?: emptyList()

    fun didStart(index: Int): SubtitleTrack {
        if (index !in sentences.indices) return this
        return copy(currentIndex = index, wordRange = null, status = PlaybackStatus.SPEAKING)
    }

    fun willSpeak(range: TextRange, index: Int): SubtitleTrack {
        if (index !in sentences.indices) return this
        val length = sentences[index].length
        if (range.location < 0 || range.location + range.length > length) return this
        return copy(currentIndex = index, wordRange = range)
    }

    fun didFinish(index: Int): SubtitleTrack {
        if (index != currentIndex) return this
        return copy(wordRange = null, status = if (index == sentences.size - 1) PlaybackStatus.FINISHED else status)
    }

    fun didPause(): SubtitleTrack = if (status == PlaybackStatus.SPEAKING) copy(status = PlaybackStatus.PAUSED) else this
    fun didResume(): SubtitleTrack = if (status == PlaybackStatus.PAUSED) copy(status = PlaybackStatus.SPEAKING) else this

    /** Stopping keeps the last sentence visible; the full transcript stays available. */
    fun didStop(): SubtitleTrack =
        copy(wordRange = null, status = if (status != PlaybackStatus.FINISHED) PlaybackStatus.STOPPED else status)

    /** Without sound, the whole message is shown at once. */
    fun showAllWithoutAudio(): SubtitleTrack {
        if (sentences.isEmpty()) return this
        return copy(currentIndex = sentences.size - 1, wordRange = null, status = PlaybackStatus.FINISHED)
    }

    companion object {
        fun load(sentences: List<String>) = SubtitleTrack(sentences)
    }
}
