package com.pofuduk.core

import com.pofuduk.core.alert.CAPWarningParser
import com.pofuduk.core.alert.ConfiguredMGMWarningProvider
import com.pofuduk.core.alert.MGMAlertRepository
import com.pofuduk.core.alert.MGMOfficialPage
import com.pofuduk.core.alert.WeatherAlertMatcher
import com.pofuduk.core.data.TurkishProvinces
import com.pofuduk.core.engine.CharacterPersonalityEngine
import com.pofuduk.core.engine.LocationPreferenceStore
import com.pofuduk.core.engine.LocationResolver
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.mock.MockWeatherData
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.Coordinate
import com.pofuduk.core.model.LocationError
import com.pofuduk.core.model.LocationException
import com.pofuduk.core.model.LocationIssue
import com.pofuduk.core.model.LocationMode
import com.pofuduk.core.model.LocationPermission
import com.pofuduk.core.model.LocationPreference
import com.pofuduk.core.model.LocationResolution
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.PersonalityStyle
import com.pofuduk.core.model.WarningAvailability
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.store.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocationResolverTest {
    private val resolver = LocationResolver()
    private val gpsPlace = Place("Kadıköy", coordinate = Coordinate(40.99, 29.03), timeZoneIdentifier = "Europe/Istanbul", source = LocationSource.MANUAL)
    private val ipPlace = Place("Ankara", coordinate = Coordinate(39.93, 32.86), timeZoneIdentifier = "Europe/Istanbul", source = LocationSource.MANUAL)

    private class Counter { var gps = 0; var ip = 0 }

    private suspend fun resolve(
        mode: LocationMode, permission: LocationPermission, manual: Place? = null, gpsFails: Exception? = null,
        ipFails: Boolean = false, ipAvailable: Boolean = true, counter: Counter = Counter(),
    ): LocationResolution = resolver.resolve(
        LocationPreference(mode, manual), permission,
        gps = { counter.gps++; gpsFails?.let { throw it }; gpsPlace },
        ip = if (ipAvailable) ({ counter.ip++; if (ipFails) throw LocationException(LocationError.NOT_FOUND); ipPlace }) else null,
    )

    @Test fun permissionNotDeterminedAsksForExplanationFirst() = runTest {
        val counter = Counter()
        assertEquals(LocationResolution.NeedsPermissionExplanation, resolve(LocationMode.AUTOMATIC, LocationPermission.NOT_DETERMINED, counter = counter))
        assertEquals(0, counter.gps)
    }

    @Test fun permissionGrantedUsesGPS() = runTest {
        val place = assertIs<LocationResolution.Resolved>(resolve(LocationMode.AUTOMATIC, LocationPermission.AUTHORIZED)).place
        assertEquals("Kadıköy", place.name)
        assertEquals(LocationSource.GPS, place.source)
    }

    @Test fun permissionDeniedNeverFallsBackToIPSilently() = runTest {
        val counter = Counter()
        assertEquals(LocationResolution.NeedsUserChoice(LocationIssue.PERMISSION_DENIED), resolve(LocationMode.AUTOMATIC, LocationPermission.DENIED, counter = counter))
        assertEquals(0, counter.ip)
        assertEquals(0, counter.gps)
    }

    @Test fun restrictedAndServicesDisabled() = runTest {
        assertEquals(LocationResolution.NeedsUserChoice(LocationIssue.PERMISSION_DENIED), resolve(LocationMode.AUTOMATIC, LocationPermission.RESTRICTED))
        assertEquals(LocationResolution.NeedsUserChoice(LocationIssue.SERVICES_DISABLED), resolve(LocationMode.AUTOMATIC, LocationPermission.SERVICES_DISABLED))
    }

    @Test fun locationServiceUnavailable() = runTest {
        assertEquals(
            LocationResolution.NeedsUserChoice(LocationIssue.GPS_UNAVAILABLE),
            resolve(LocationMode.AUTOMATIC, LocationPermission.AUTHORIZED, gpsFails = LocationException(LocationError.UNAVAILABLE)),
        )
    }

    @Test fun ipSelectedIsMarkedApproximate() = runTest {
        val place = assertIs<LocationResolution.Resolved>(resolve(LocationMode.IP_APPROXIMATE, LocationPermission.DENIED)).place
        assertEquals(LocationSource.IP_APPROXIMATE, place.source)
    }

    @Test fun ipFailureOrUnavailable() = runTest {
        assertEquals(LocationResolution.NeedsUserChoice(LocationIssue.IP_UNAVAILABLE), resolve(LocationMode.IP_APPROXIMATE, LocationPermission.DENIED, ipFails = true))
        assertEquals(LocationResolution.NeedsUserChoice(LocationIssue.IP_UNAVAILABLE), resolve(LocationMode.IP_APPROXIMATE, LocationPermission.DENIED, ipAvailable = false))
    }

    @Test fun manualCitySelected() = runTest {
        val bursa = TurkishProvinces.search("bursa")[0].place
        val counter = Counter()
        assertEquals(LocationResolution.Resolved(bursa), resolve(LocationMode.MANUAL, LocationPermission.DENIED, manual = bursa, counter = counter))
        assertEquals(0, counter.gps + counter.ip)
    }

    @Test fun manualWithoutPlaceAsksUser() = runTest {
        assertEquals(LocationResolution.NeedsUserChoice(LocationIssue.NO_MANUAL_PLACE), resolve(LocationMode.MANUAL, LocationPermission.AUTHORIZED))
    }

    @Test fun preferenceStoreRoundTrip() {
        val store = LocationPreferenceStore(InMemoryKeyValueStore())
        assertEquals(LocationPreference(), store.load())
        val pref = LocationPreference(LocationMode.MANUAL, TurkishProvinces.all[33].place)
        store.save(pref)
        assertEquals(pref, store.load())
        store.clear()
        assertEquals(LocationMode.AUTOMATIC, store.load().mode)
    }

    @Test fun coarsenedToTwoDecimals() {
        val p = LocationPreference.coarsened(gpsPlace.copy(coordinate = Coordinate(40.98765, 29.03456)))
        assertEquals(Coordinate(40.99, 29.03), p.coordinate)
    }
}

class TurkishProvincesTest {
    @Test fun all81UniqueAndInsideTurkey() {
        assertEquals(81, TurkishProvinces.all.size)
        assertEquals((1..81).toSet(), TurkishProvinces.all.map { it.plate }.toSet())
        for (p in TurkishProvinces.all) {
            assertTrue(p.coordinate.latitude in 35.8..42.2, p.name)
            assertTrue(p.coordinate.longitude in 25.6..44.9, p.name)
        }
    }

    @Test fun turkishAwareSearch() {
        assertEquals("İstanbul", TurkishProvinces.search("istanbul").firstOrNull()?.name)
        assertEquals("Şanlıurfa", TurkishProvinces.search("sanliurfa").firstOrNull()?.name)
        assertEquals("Iğdır", TurkishProvinces.search("IĞDIR").firstOrNull()?.name)
        assertEquals("İzmir", TurkishProvinces.search("35").firstOrNull()?.name)
    }
}

class MGMWarningTest {
    private val matcher = WeatherAlertMatcher()
    private val now = TestClock.date(12)
    private val istanbul = TurkishProvinces.search("İstanbul")[0].place

    private fun warning(
        level: WarningLevel, provinces: List<String> = listOf("İstanbul"), districts: List<String> = emptyList(),
        from: Long = -3600, until: Long = 3600,
    ) = OfficialWarning(
        "$level-$provinces", level, "Kuvvetli yağış", "Sel ve su baskınlarına dikkat.", provinces, districts,
        now.plusSeconds(from), now.plusSeconds(until), MGMOfficialPage.WARNINGS,
    )

    @Test fun noWarning() {
        assertTrue(matcher.matching(emptyList(), istanbul, now).isEmpty())
        assertTrue(matcher.matching(listOf(warning(WarningLevel.GREEN)), istanbul, now).isEmpty(), "Green is not a warning")
    }

    @Test fun yellowOrangeRedAreMatchedAndSortedBySeverity() {
        val m = matcher.matching(listOf(warning(WarningLevel.YELLOW), warning(WarningLevel.RED), warning(WarningLevel.ORANGE)), istanbul, now)
        assertEquals(listOf(WarningLevel.RED, WarningLevel.ORANGE, WarningLevel.YELLOW), m.map { it.level })
    }

    @Test fun expiredWarningIgnored() {
        assertTrue(matcher.matching(listOf(warning(WarningLevel.ORANGE, from = -7200, until = -60)), istanbul, now).isEmpty())
        assertTrue(matcher.matching(listOf(warning(WarningLevel.ORANGE, from = 600, until = 3600)), istanbul, now).isEmpty(), "Not yet valid")
    }

    @Test fun warningForAnotherProvinceIgnored() {
        assertTrue(matcher.matching(listOf(warning(WarningLevel.RED, listOf("Ankara"))), istanbul, now).isEmpty())
    }

    @Test fun provinceMatchIsDiacriticInsensitiveAndDistrictAware() {
        val kadikoy = istanbul.copy(district = "Kadıköy")
        assertEquals(1, matcher.matching(listOf(warning(WarningLevel.YELLOW, listOf("ISTANBUL"))), kadikoy, now).size)
        assertEquals(1, matcher.matching(listOf(warning(WarningLevel.YELLOW, districts = listOf("Kadıköy"))), kadikoy, now).size)
        assertTrue(matcher.matching(listOf(warning(WarningLevel.YELLOW, districts = listOf("Şile"))), kadikoy, now).isEmpty())
    }

    @Test fun sourceUnavailableWithoutAuthorizedFeed() = runTest {
        val r = MGMAlertRepository(ConfiguredMGMWarningProvider(null, StubHttpClient())).warnings(istanbul, now)
        val u = assertIs<WarningAvailability.Unavailable>(r)
        assertEquals("https://www.mgm.gov.tr/meteouyari/turkiye.aspx", u.officialPage)
        assertTrue(u.reason.contains("yetkili"))
    }

    @Test fun feedFailureIsUnavailableNotEmpty() = runTest {
        val provider = ConfiguredMGMWarningProvider("https://example.invalid/cap", StubHttpClient(status = 503))
        assertIs<WarningAvailability.Unavailable>(provider.warnings(), "A failed feed must not look like 'no warnings'")
    }

    @Test fun capParsingAndRepositoryMatching() = runTest {
        val cap = """
        <?xml version="1.0" encoding="UTF-8"?>
        <alerts>
        <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
          <identifier>TR-2026-001</identifier><sent>2026-11-14T08:00:00+03:00</sent>
          <info><language>en-GB</language><event>Heavy rain</event><severity>Severe</severity>
            <expires>2026-11-14T23:00:00+03:00</expires><area><areaDesc>Istanbul</areaDesc></area></info>
          <info><language>tr-TR</language><event>Kuvvetli yağış</event><severity>Severe</severity>
            <onset>2026-11-14T09:00:00+03:00</onset><expires>2026-11-14T23:00:00+03:00</expires>
            <description>Sel ve su baskınlarına karşı dikkatli olunmalı.</description>
            <web>https://www.mgm.gov.tr/meteouyari/turkiye.aspx</web>
            <parameter><valueName>awareness_level</valueName><value>3; orange; Severe</value></parameter>
            <area><areaDesc>İstanbul, Kocaeli</areaDesc></area></info>
        </alert>
        <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
          <identifier>TR-2026-002</identifier><sent>2026-11-14T08:00:00+03:00</sent>
          <info><language>tr-TR</language><event>Fırtına</event><severity>Extreme</severity>
            <onset>2026-11-14T09:00:00+03:00</onset><expires>2026-11-14T23:00:00+03:00</expires>
            <area><areaDesc>Ankara</areaDesc></area></info>
        </alert>
        </alerts>
        """.trimIndent()
        val parsed = assertNotNull(CAPWarningParser.parse(cap.toByteArray()))
        assertEquals(2, parsed.size)
        val first = parsed[0]
        assertEquals(WarningLevel.ORANGE, first.level)
        assertEquals("Kuvvetli yağış", first.hazard, "Turkish info preferred")
        assertEquals(listOf("İstanbul", "Kocaeli"), first.provinces)
        assertEquals(TestClock.date(9), first.validFrom)
        assertEquals(WarningLevel.RED, parsed[1].level, "Severity fallback when no awareness level")

        val provider = ConfiguredMGMWarningProvider("https://example.invalid/cap", StubHttpClient(cap))
        val matched = assertIs<WarningAvailability.Available>(MGMAlertRepository(provider).warnings(istanbul, now)).warnings
        assertEquals(listOf("TR-2026-001"), matched.map { it.id })
    }

    @Test fun doctypeIsRejected() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><alert><identifier>&x;</identifier></alert>"""
        assertEquals(null, CAPWarningParser.parse(xxe.toByteArray()))
    }

    @Test fun orangeWarningDrivesSeriousBriefing() {
        val snap = MockWeatherData.snapshot(MockWeatherData.Scenario.SUNNY, now)
        val a = WeatherSummaryEngine().analyze(snap, now)
        val b = CharacterPersonalityEngine(PersonalityStyle.PLAYFUL).briefing(a, listOf(warning(WarningLevel.ORANGE)))
        assertEquals(CharacterMood.WARNING, b.restingMood)
        assertTrue(b.fullText.contains("MGM tarafından turuncu seviyede"))
        assertFalse(b.fullText.contains("Güneş bizimle"), "No jokes during a serious warning")
    }
}
