package com.pofuduk.core

import com.pofuduk.core.conversation.ConversationManager
import com.pofuduk.core.conversation.WeatherAnswerEngine
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.LaterPrecipitationRelation
import com.pofuduk.core.engine.WeatherAnalysis
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.notification.MorningBriefingEngine
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.isSameDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Seen on a device: raining at 07.00, briefing said "Saat 09.00 civarında yağmur başlaması bekleniyor." */
class LaterPrecipitationTest {
    private val summary = WeatherSummaryEngine()
    private val at = TestClock.date(7)

    /** Sunny mock, made wet (probability 80 %, [mm] per hour) from [wetFrom] to 12.00; `now` = current condition. */
    private fun snapshot(
        now: WeatherCondition, wetFrom: Int, mm: Double = 1.0, wetCondition: WeatherCondition = WeatherCondition.RAIN,
    ): WeatherSnapshot {
        val snap = MockWeatherData.snapshot(MockWeatherData.Scenario.SUNNY, at)
        val zone = snap.place.timeZone
        return snap.copy(
            current = snap.current.copy(condition = now),
            hourly = snap.hourly.map { h ->
                val wet = h.time.isSameDay(at, zone) && h.time.hour(zone) in wetFrom until 12
                h.copy(
                    precipitationProbability = if (wet) 80 else 0,
                    precipitationMm = if (wet) mm else 0.0,
                    condition = if (wet) wetCondition else WeatherCondition.PARTLY_CLOUDY,
                )
            },
        )
    }

    private fun analysis(snap: WeatherSnapshot): WeatherAnalysis = summary.analyze(snap, at)
    private fun text(snap: WeatherSnapshot): String = CharacterPersonalityEngine().briefing(analysis(snap)).fullText

    @Test fun sameRainWithoutRealBreakIsToldAsContinuing() {
        // Raining now (forecast missed it); 08.00 dry, wet from 09.00: one dry hour is not a break.
        val snap = snapshot(WeatherCondition.RAIN, wetFrom = 9)
        assertEquals(LaterPrecipitationRelation.CONTINUES, analysis(snap).laterPrecipitation)
        val t = text(snap)
        assertTrue(t.contains("Şu sıralar yağmur var ve yaklaşık 5 saat daha sürebilir."), t)
        assertFalse(t.contains("başlaması bekleniyor"), t)
    }

    @Test fun twoDryHoursMeanItMayStopAndStartAgain() {
        val snap = snapshot(WeatherCondition.RAIN, wetFrom = 10)
        assertEquals(LaterPrecipitationRelation.RESUMES, analysis(snap).laterPrecipitation)
        val t = text(snap)
        assertTrue(t.contains("Şu anki yağmur ara verebilir; saat 10.00 civarında yeniden başlaması bekleniyor"), t)
    }

    @Test fun heavierLaterIsToldAsIntensifying() {
        val heavy = snapshot(WeatherCondition.DRIZZLE, wetFrom = 9, mm = 9.0)
        assertEquals(LaterPrecipitationRelation.INTENSIFIES, analysis(heavy).laterPrecipitation)
        assertTrue(text(heavy).contains("Şu sıralar çisenti var; saat 09.00 civarında kuvvetlenmesi bekleniyor"), text(heavy))

        val storm = snapshot(WeatherCondition.RAIN, wetFrom = 9, wetCondition = WeatherCondition.THUNDERSTORM)
        assertTrue(text(storm).contains("saat 09.00 civarında gök gürültülü sağanağa dönmesi bekleniyor"), text(storm))
    }

    @Test fun dryNowKeepsTheStartTime() {
        val snap = snapshot(WeatherCondition.CLOUDY, wetFrom = 9)
        assertNull(analysis(snap).laterPrecipitation)
        assertTrue(text(snap).contains("Saat 09.00 civarında yağmur başlaması bekleniyor"), text(snap))
    }

    @Test fun rainInTheCurrentHourIsUnchanged() {
        // The forecast has rain now too: the existing "Şu sıralar … var" wording applies.
        val snap = snapshot(WeatherCondition.RAIN, wetFrom = 7)
        assertNull(analysis(snap).laterPrecipitation)
        assertTrue(text(snap).contains("Şu sıralar yağmur var"), text(snap))
    }

    @Test fun answerToRainQuestionWhileRaining() {
        val answer = WeatherAnswerEngine().answer(
            ConversationManager().interpret("Bugün yağmur var mı?", at), snapshot(WeatherCondition.RAIN, wetFrom = 10), at,
        ).fullText
        assertTrue(answer.contains("Şu an yağmur var; ara verip saat 10.00 civarında yeniden başlaması bekleniyor."), answer)
    }

    @Test fun morningBriefingWhileRaining() {
        val snap = snapshot(WeatherCondition.RAIN, wetFrom = 9)
        val b = MorningBriefingEngine().briefing(snap, analysis(snap), clothing = null, now = at).fullText
        assertTrue(b.contains("Şu sıralar yağmur var ve bir süre daha sürmesi bekleniyor."), b)
        assertFalse(b.contains("Ancak sabah saatlerinde yağmur bekleniyor"), b)
    }
}
