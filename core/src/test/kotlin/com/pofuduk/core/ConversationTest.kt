package com.pofuduk.core

import com.pofuduk.core.conversation.ConversationManager
import com.pofuduk.core.conversation.DateReference
import com.pofuduk.core.conversation.DayPart
import com.pofuduk.core.conversation.ResolvedQuery
import com.pofuduk.core.conversation.WeatherAnswerEngine
import com.pofuduk.core.conversation.WeatherIntent
import com.pofuduk.core.conversation.WeatherQueryParser
import com.pofuduk.core.conversation.WeatherTopic
import com.pofuduk.core.datetime.ForecastDateResolver
import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.mock.MockWeatherData.Scenario
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.util.localInstant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherQueryParserTest {
    private val parser = WeatherQueryParser()

    @Test fun exampleQuestions() {
        assertEquals(WeatherIntent(WeatherTopic.OVERVIEW, DateReference.Tomorrow), parser.parse("Yarın hava nasıl?"))
        assertEquals(WeatherTopic.RAIN, parser.parse("Bugün yağmur yağacak mı?").topic)
        assertEquals(WeatherTopic.RAIN_TIMING, parser.parse("Yağmur saat kaçta başlayacak?").topic)
        assertEquals(WeatherTopic.GOING_OUT, parser.parse("Akşam dışarı çıkabilir miyim?").topic)
        assertEquals(DayPart.Evening, parser.parse("Akşam dışarı çıkabilir miyim?").dayPart)
        assertEquals(WeatherTopic.UMBRELLA, parser.parse("Şemsiye almam gerekiyor mu?").topic)
        assertEquals(WeatherTopic.CLOTHING, parser.parse("Bugün ne giysem?").topic)
        assertEquals(WeatherTopic.CLOTHING, parser.parse("İnce mont yeterli mi?").topic)
        assertEquals(DateReference.Weekend, parser.parse("Hafta sonu hava nasıl?").date)
        assertEquals(WeatherTopic.UV, parser.parse("Bugün güneş kremi kullanmalı mıyım?").topic)
        assertEquals(WeatherTopic.TEMPERATURE, parser.parse("Sabah hava kaç derece?").topic)
        assertEquals(DayPart.Morning, parser.parse("Sabah hava kaç derece?").dayPart)
        assertEquals(DayPart.Hour(17), parser.parse("Saat 17.00'de hava nasıl olacak?").dayPart)
        assertEquals(WeatherTopic.WIND, parser.parse("Rüzgar çok mu esecek?").topic)
    }

    @Test fun cityWithTurkishSuffixes() {
        val q = parser.parse("Yarın Bursa'da hava nasıl?")
        assertEquals("Bursa", q.province?.name)
        assertEquals(DateReference.Tomorrow, q.date)
        assertEquals("Antalya", parser.parse("Hafta sonu Antalya'ya gitmek istiyorum").province?.name)
        assertEquals("İzmir", parser.parse("İzmirde yağmur var mı").province?.name)
        assertEquals("Ankara", parser.parse("İstanbul'dan Ankara'ya yolculuk yapacağım").province?.name, "Destination wins")
    }

    @Test fun hourHeuristicsAndWeekdays() {
        assertEquals(DayPart.Hour(17), parser.parse("saat 5'te yağmur var mı").dayPart)
        assertEquals(DayPart.Hour(6), parser.parse("sabah saat 6 da hava").dayPart)
        assertEquals(DateReference.Weekday(2), parser.parse("Pazartesi hava nasıl").date)
        assertEquals(DateReference.Weekday(7), parser.parse("Cumartesi yağmur var mı").date)
        assertEquals(DateReference.Weekday(6), parser.parse("Cuma günü hava").date)
        assertEquals(DateReference.InDays(2), parser.parse("yarından sonra hava").date)
    }

    @Test fun followUpDetection() {
        assertTrue(parser.parse("Peki yağmur var mı?").isFollowUp)
        assertNull(parser.parse("Peki yağmur var mı?").date)
        assertFalse(parser.parse("Yarın İzmir'de hava").isFollowUp)
    }
}

class ConversationFlowTest {
    private val now = TestClock.date(9)
    private val answers = WeatherAnswerEngine()

    private fun snapshot(scenario: Scenario = Scenario.RAINY_AFTERNOON) =
        MockWeatherData.snapshot(scenario, now).copy(source = WeatherDataSource.OPEN_METEO)

    @Test fun tomorrowsWeather() {
        val q = ConversationManager().interpret("Yarın hava nasıl?", now)
        val text = answers.answer(q, snapshot(), now).fullText
        assertTrue(text.startsWith("Yarın İstanbul'da"), text)
        assertTrue(text.contains("derece"))
    }

    @Test fun rainTime() {
        val q = ConversationManager().interpret("Yağmur saat kaçta başlayacak?", now)
        val text = answers.answer(q, snapshot(), now).fullText
        assertTrue(text.contains("saat 14.00 civarında yağmur bekleniyor"), text)
    }

    @Test fun followUpKeepsTomorrow() {
        val m = ConversationManager()
        m.interpret("Yarın hava nasıl?", now)
        val q = m.interpret("Peki yağmur var mı?", now.plusSeconds(30))
        assertEquals(DateReference.Tomorrow, q.date)
        assertEquals(WeatherTopic.RAIN, q.topic)
        assertTrue(answers.answer(q, snapshot(), now).fullText.startsWith("Yarın"))
    }

    @Test fun followUpWithDayPart() {
        val m = ConversationManager()
        m.interpret("Yarın Bursa'da hava nasıl?", now)
        val q = m.interpret("Peki akşam?", now)
        assertEquals(DateReference.Tomorrow, q.date)
        assertEquals(DayPart.Evening, q.dayPart)
        assertEquals("Bursa", q.province?.name)
    }

    @Test fun contextExpires() {
        val m = ConversationManager()
        m.interpret("Yarın hava nasıl?", now)
        assertEquals(DateReference.Today, m.interpret("Peki yağmur var mı?", now.plusSeconds(3600)).date)
    }

    @Test fun ambiguousDateDefaultsToToday() {
        val q = ConversationManager().interpret("Hava nasıl olacak?", now)
        assertEquals(DateReference.Today, q.date)
        assertTrue(answers.answer(q, snapshot(), now).fullText.contains("şu an"))
    }

    @Test fun unknownPlaceFallsBackToCurrentPlaceAndNamesIt() {
        val q = ConversationManager().interpret("Yarın Kaş'ta hava nasıl?", now) // district not in province list
        assertNull(q.province)
        assertTrue(answers.answer(q, snapshot(), now).fullText.contains("İstanbul'da"), "Answer must name which place it describes")
    }

    @Test fun clothingAnswerUsesData() {
        val q = ConversationManager().interpret("Bugün ne giysem?", now)
        val text = answers.answer(q, snapshot(Scenario.SNOWY), now).fullText
        assertTrue(text.contains("Kalın bir mont"), text)
    }

    @Test fun outOfRangeIsHonest() {
        val text = answers.answer(ResolvedQuery(WeatherTopic.OVERVIEW, DateReference.InDays(10)), snapshot(), now).fullText
        assertTrue(text.contains("henüz bir tahminim yok"))
        assertFalse(text.contains("derece"))
    }

    @Test fun specificHourTemperature() {
        val snap = snapshot()
        val text = answers.answer(ResolvedQuery(WeatherTopic.TEMPERATURE, DateReference.Today, DayPart.Hour(17)), snap, now).fullText
        assertTrue(text.contains("saat 17.00 civarında"), text)
        assertTrue(text.contains(TurkishFormatting.temperature(snap.hourly[17].temperature)), text)
    }

    @Test fun naturalTodayLabelsAndNoRepeatedDay() {
        val m = ConversationManager()
        val evening = answers.answer(m.interpret("Akşam dışarı çıkabilir miyim?", now), snapshot(), now).fullText
        assertTrue(evening.startsWith("Bu akşam"), evening)
        val tomorrow = answers.answer(m.interpret("Yarın hava nasıl?", now), snapshot(), now)
        assertEquals(1, tomorrow.sentences.count { it.startsWith("Yarın") }, tomorrow.fullText)
    }

    @Test fun rainingNowButDryLaterIsNotToldNoPrecipitation() {
        val sunny = snapshot(Scenario.SUNNY)
        var s = sunny.copy(current = sunny.current.copy(condition = WeatherCondition.THUNDERSTORM))
        val today = answers.answer(ConversationManager().interpret("Bugün yağmur var mı?", now), s, now).fullText
        assertTrue(today.contains("Şu an yağış var ama sonraki saatlerde başka yağış beklenmiyor."), today)
        val tomorrow = answers.answer(ConversationManager().interpret("Yarın yağmur var mı?", now), s, now).fullText
        assertTrue(tomorrow.contains("Yarın yağış beklenmiyor."), tomorrow)
        s = s.copy(current = s.current.copy(condition = WeatherCondition.CLOUDY))
        val dry = answers.answer(ConversationManager().interpret("Bugün yağmur var mı?", now), s, now).fullText
        assertFalse(dry.contains("Şu an yağış"), dry)
        assertTrue(dry.contains("yağış beklenmiyor"), dry)
    }

    @Test fun noRainSaysSo() {
        val q = ResolvedQuery(WeatherTopic.RAIN, DateReference.Tomorrow)
        assertEquals(listOf("Yarın yağış beklenmiyor."), answers.answer(q, snapshot(Scenario.SUNNY), now).sentences)
    }
}

class ForecastDateResolverTest {
    private val r = ForecastDateResolver()
    private val tz = TestClock.istanbul

    @Test fun weekendFromSaturdayAndWeekday() {
        // 2026-11-14 is a Saturday.
        assertEquals(listOf(TestClock.date(0), TestClock.date(0, day = 15)), r.days(DateReference.Weekend, TestClock.date(9), tz))
        assertEquals(
            listOf(TestClock.date(0, day = 14), TestClock.date(0, day = 15)),
            r.days(DateReference.Weekend, TestClock.date(9, day = 11), tz),
        )
    }

    @Test fun weekdayTodayAndNext() {
        val sat = TestClock.date(9)
        assertEquals(listOf(TestClock.date(0)), r.days(DateReference.Weekday(7), sat, tz))
        assertEquals(listOf(TestClock.date(0, day = 16)), r.days(DateReference.Weekday(2), sat, tz))
    }

    @Test fun midnightTransitionUsesPlaceTimeZone() {
        assertEquals(listOf(TestClock.date(0, day = 15)), r.days(DateReference.Tomorrow, TestClock.date(23, 30), tz))
    }

    @Test fun differentTimeZoneDestination() {
        // 01:00 Istanbul (UTC+3) is still the previous day in London (UTC+0).
        val london = ZoneId.of("Europe/London")
        assertEquals(13, r.days(DateReference.Today, TestClock.date(1), london)[0].atZone(london).dayOfMonth)
    }

    @Test fun dstDayHasCorrectStart() {
        // Europe/Berlin switches to summer time on 2026-03-29.
        val berlin = ZoneId.of("Europe/Berlin")
        val tomorrow = r.days(DateReference.Tomorrow, localInstant(2026, 3, 28, 20, 0, berlin), berlin)[0].atZone(berlin)
        assertEquals(29, tomorrow.dayOfMonth)
        assertEquals(0, tomorrow.hour)
    }
}
