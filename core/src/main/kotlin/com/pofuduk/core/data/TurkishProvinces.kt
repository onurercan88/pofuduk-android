package com.pofuduk.core.data

import com.pofuduk.core.model.Coordinate
import com.pofuduk.core.model.LocationSource
import com.pofuduk.core.model.Place
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline list of Turkey's 81 provinces (plate code, name, approximate city-centre coordinate).
 * Used for manual selection without network. Districts are found via geocoding search (later phase).
 */
object TurkishProvinces {
    data class Province(val plate: Int, val name: String, val coordinate: Coordinate) {
        val id: Int get() = plate
        val place: Place
            get() = Place(
                name = name, province = name, coordinate = coordinate,
                timeZoneIdentifier = "Europe/Istanbul", source = LocationSource.MANUAL,
            )
    }

    /** Turkish-aware, diacritic-insensitive search ("istanbul" matches "İstanbul", "sanliurfa" matches "Şanlıurfa"). */
    fun search(query: String): List<Province> {
        val q = normalize(query)
        if (q.isEmpty()) return all
        return all.filter { normalize(it.name).contains(q) || it.plate.toString() == q }
    }

    /**
     * Offline fallback when reverse geocoding is unavailable: the province whose centre is closest to
     * [coordinate], or null when none is within [maxDistanceKm] (e.g. abroad).
     */
    fun nearest(coordinate: Coordinate, maxDistanceKm: Double = 200.0): Province? =
        all.map { it to distanceKm(coordinate, it.coordinate) }
            .filter { it.second <= maxDistanceKm }
            .minByOrNull { it.second }?.first

    /** Great-circle distance (haversine). */
    fun distanceKm(a: Coordinate, b: Coordinate): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    private val foldMap = mapOf(
        'ı' to 'i', 'İ' to 'i', 'I' to 'i', 'ş' to 's', 'Ş' to 's', 'ğ' to 'g', 'Ğ' to 'g',
        'ü' to 'u', 'Ü' to 'u', 'ö' to 'o', 'Ö' to 'o', 'ç' to 'c', 'Ç' to 'c', 'â' to 'a', 'î' to 'i', 'û' to 'u',
    )

    fun normalize(s: String): String =
        s.trim(' ', '\t').map { foldMap[it] ?: it }.joinToString("").lowercase(java.util.Locale.ROOT)

    private val raw: List<Triple<Int, String, Pair<Double, Double>>> = listOf(
        r(1, "Adana", 37.00, 35.32), r(2, "Adıyaman", 37.76, 38.28), r(3, "Afyonkarahisar", 38.76, 30.54),
        r(4, "Ağrı", 39.72, 43.05), r(5, "Amasya", 40.65, 35.83), r(6, "Ankara", 39.93, 32.86),
        r(7, "Antalya", 36.90, 30.70), r(8, "Artvin", 41.18, 41.82), r(9, "Aydın", 37.85, 27.85),
        r(10, "Balıkesir", 39.65, 27.88), r(11, "Bilecik", 40.14, 29.98), r(12, "Bingöl", 38.88, 40.50),
        r(13, "Bitlis", 38.40, 42.11), r(14, "Bolu", 40.74, 31.61), r(15, "Burdur", 37.72, 30.29),
        r(16, "Bursa", 40.19, 29.06), r(17, "Çanakkale", 40.15, 26.41), r(18, "Çankırı", 40.60, 33.62),
        r(19, "Çorum", 40.55, 34.95), r(20, "Denizli", 37.78, 29.09), r(21, "Diyarbakır", 37.91, 40.24),
        r(22, "Edirne", 41.68, 26.56), r(23, "Elazığ", 38.67, 39.22), r(24, "Erzincan", 39.75, 39.49),
        r(25, "Erzurum", 39.90, 41.27), r(26, "Eskişehir", 39.78, 30.52), r(27, "Gaziantep", 37.07, 37.38),
        r(28, "Giresun", 40.91, 38.39), r(29, "Gümüşhane", 40.46, 39.48), r(30, "Hakkari", 37.58, 43.74),
        r(31, "Hatay", 36.20, 36.16), r(32, "Isparta", 37.76, 30.55), r(33, "Mersin", 36.81, 34.64),
        r(34, "İstanbul", 41.01, 28.98), r(35, "İzmir", 38.42, 27.14), r(36, "Kars", 40.60, 43.10),
        r(37, "Kastamonu", 41.38, 33.78), r(38, "Kayseri", 38.73, 35.48), r(39, "Kırklareli", 41.73, 27.22),
        r(40, "Kırşehir", 39.15, 34.16), r(41, "Kocaeli", 40.77, 29.92), r(42, "Konya", 37.87, 32.48),
        r(43, "Kütahya", 39.42, 29.98), r(44, "Malatya", 38.35, 38.31), r(45, "Manisa", 38.61, 27.43),
        r(46, "Kahramanmaraş", 37.58, 36.94), r(47, "Mardin", 37.31, 40.74), r(48, "Muğla", 37.22, 28.36),
        r(49, "Muş", 38.75, 41.50), r(50, "Nevşehir", 38.62, 34.71), r(51, "Niğde", 37.97, 34.68),
        r(52, "Ordu", 40.98, 37.88), r(53, "Rize", 41.02, 40.52), r(54, "Sakarya", 40.78, 30.40),
        r(55, "Samsun", 41.29, 36.33), r(56, "Siirt", 37.93, 41.94), r(57, "Sinop", 42.03, 35.15),
        r(58, "Sivas", 39.75, 37.02), r(59, "Tekirdağ", 40.98, 27.51), r(60, "Tokat", 40.31, 36.55),
        r(61, "Trabzon", 41.00, 39.72), r(62, "Tunceli", 39.11, 39.55), r(63, "Şanlıurfa", 37.16, 38.79),
        r(64, "Uşak", 38.68, 29.41), r(65, "Van", 38.50, 43.38), r(66, "Yozgat", 39.82, 34.81),
        r(67, "Zonguldak", 41.45, 31.79), r(68, "Aksaray", 38.37, 34.03), r(69, "Bayburt", 40.26, 40.23),
        r(70, "Karaman", 37.18, 33.22), r(71, "Kırıkkale", 39.85, 33.51), r(72, "Batman", 37.88, 41.13),
        r(73, "Şırnak", 37.52, 42.46), r(74, "Bartın", 41.64, 32.34), r(75, "Ardahan", 41.11, 42.70),
        r(76, "Iğdır", 39.92, 44.04), r(77, "Yalova", 40.65, 29.27), r(78, "Karabük", 41.20, 32.62),
        r(79, "Kilis", 36.72, 37.12), r(80, "Osmaniye", 37.07, 36.25), r(81, "Düzce", 40.84, 31.16),
    )

    private fun r(plate: Int, name: String, lat: Double, lon: Double) = Triple(plate, name, lat to lon)

    val all: List<Province> = raw.map { (plate, name, c) -> Province(plate, name, Coordinate(c.first, c.second)) }
}
