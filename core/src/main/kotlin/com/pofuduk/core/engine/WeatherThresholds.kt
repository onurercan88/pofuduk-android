package com.pofuduk.core.engine

/** Tunable interpretation thresholds. Changing these never requires UI changes. */
data class WeatherThresholds(
    /** Probability (%) at or above which precipitation is "likely". */
    val likelyPrecipitationProbability: Int = 60,
    /** Probability (%) at or above which precipitation is "possible" (communicated with uncertainty). */
    val possiblePrecipitationProbability: Int = 30,
    /** Hourly amount (mm) at or above which an hour counts as wet when amounts are available. */
    val minimumWetHourMm: Double = 0.1,
    /** Hourly rate (mm/h) boundaries: light < moderate < heavy. */
    val moderateRainMmPerHour: Double = 2.5,
    val heavyRainMmPerHour: Double = 7.6,
    /** Sustained wind (km/h) considered strong. */
    val strongWindKmh: Double = 40.0,
    /** Gust (km/h) considered strong. */
    val strongGustKmh: Double = 60.0,
    /** UV index considered high. */
    val highUVIndex: Double = 6.0,
    /** Feels-like difference (°C) worth mentioning. */
    val notableFeelsLikeDifference: Double = 3.0,
    /** Daily min/max swing (°C) worth mentioning. */
    val notableTemperatureSwing: Double = 10.0,
) {
    companion object {
        val DEFAULT = WeatherThresholds()
    }
}
