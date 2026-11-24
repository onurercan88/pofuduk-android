package com.pofuduk.core

import com.pofuduk.core.model.BriefingLength
import com.pofuduk.core.model.PersonalityStyle
import com.pofuduk.core.model.SubtitleSize
import com.pofuduk.core.model.UserPreferences
import com.pofuduk.core.model.UserPreferencesStore
import com.pofuduk.core.model.VoiceTone
import com.pofuduk.core.speech.SpeechTextNormalizer
import com.pofuduk.core.speech.TextRange
import com.pofuduk.core.store.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextNormalizerTest {
    private fun range(word: String, text: String) = TextRange(text.indexOf(word), word.length)

    @Test fun clockTimesAreSpokenAsHours() {
        assertEquals(
            "Saat 14 civarında yağmur başlaması bekleniyor.",
            SpeechTextNormalizer.spoken("Saat 14.00 civarında yağmur başlaması bekleniyor.").text,
        )
        assertEquals("Saat 9 30 sonrasında kar ihtimali.", SpeechTextNormalizer.spoken("Saat 09.30 sonrasında kar ihtimali.").text)
        assertEquals("Yarın saat 7'ye kadar", SpeechTextNormalizer.spoken("Yarın saat 07.00'ye kadar").text)
    }

    @Test fun abbreviations() {
        assertEquals("ultraviyole indeksi 7 seviyesine çıkıyor.", SpeechTextNormalizer.spoken("UV indeksi 7 seviyesine çıkıyor.").text)
        assertEquals(
            "Bölgen için Meteoroloji tarafından uyarı yayımlanmış.",
            SpeechTextNormalizer.spoken("Bölgen için MGM tarafından uyarı yayımlanmış.").text,
        )
        // Only whole words.
        assertEquals("UVA ve MGMX", SpeechTextNormalizer.spoken("UVA ve MGMX").text)
    }

    @Test fun textWithoutRulesIsUnchanged() {
        val s = "İstanbul'da şu an 15 derece, hava parçalı bulutlu."
        val spoken = SpeechTextNormalizer.spoken(s)
        assertEquals(s, spoken.text)
        assertEquals(range("derece", s), spoken.subtitleRange(range("derece", s)))
    }

    @Test fun spokenWordRangesMapBackToSubtitle() {
        val subtitle = "Saat 14.00 civarında UV indeksi yükseliyor."
        val spoken = SpeechTextNormalizer.spoken(subtitle)
        assertEquals("Saat 14 civarında ultraviyole indeksi yükseliyor.", spoken.text)
        // Words before, inside and after replacements land on the right subtitle words.
        assertEquals(range("Saat", subtitle), spoken.subtitleRange(range("Saat", spoken.text)))
        assertEquals(range("14.00", subtitle), spoken.subtitleRange(range("14", spoken.text)))
        assertEquals(range("civarında", subtitle), spoken.subtitleRange(range("civarında", spoken.text)))
        assertEquals(range("UV", subtitle), spoken.subtitleRange(range("ultraviyole", spoken.text)))
        assertEquals(range("yükseliyor", subtitle), spoken.subtitleRange(range("yükseliyor", spoken.text)))
    }

    @Test fun preferencesFromOlderVersionKeepTheirValues() {
        // Saved before `voiceTone` existed.
        val kv = InMemoryKeyValueStore()
        kv.putString(
            "pofuduk.userPreferences",
            """{"style":"PLAYFUL","briefingLength":"SHORT","speechRateMultiplier":1.1,"subtitleSize":"LARGE"}""",
        )
        val store = UserPreferencesStore(kv)
        val p = store.load()
        assertEquals(PersonalityStyle.PLAYFUL, p.style)
        assertEquals(BriefingLength.SHORT, p.briefingLength)
        assertEquals(1.1, p.speechRateMultiplier)
        assertEquals(SubtitleSize.LARGE, p.subtitleSize)
        assertEquals(VoiceTone.POFUDUK, p.voiceTone)

        val changed = p.copy(voiceTone = VoiceTone.NATURAL)
        store.save(changed)
        assertEquals(changed, store.load())
        assertEquals(1.0f, VoiceTone.NATURAL.pitchMultiplier)
    }
}
