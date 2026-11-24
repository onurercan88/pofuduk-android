package com.pofuduk.app.home

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.pofuduk.app.ui.theme.LocalPalette
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.util.isSameDay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small weather symbol drawn with Canvas (same look on every device, unlike emoji; no icon dependency).
 * Decorative by itself: callers give it a content description.
 */
@Composable
fun WeatherIcon(condition: WeatherCondition, night: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val sun = Color(0xFFFFB627)
    val moon = if (palette.isDark) Color(0xFFF3E9B5) else Color(0xFFB9A64A)
    val cloud = if (palette.isDark) Color(0xFFE4E6F5) else Color(0xFF9AA0C3)
    val drop = palette.accent
    val bolt = Color(0xFFFFB627)
    Canvas(modifier) {
        val w = size.width
        when (condition) {
            WeatherCondition.CLEAR -> if (night) drawMoon(center, w * 0.32f, moon) else drawSun(center, w * 0.22f, sun)
            WeatherCondition.PARTLY_CLOUDY -> {
                if (night) drawMoon(Offset(w * 0.64f, w * 0.34f), w * 0.22f, moon) else drawSun(Offset(w * 0.64f, w * 0.34f), w * 0.15f, sun)
                drawCloud(Offset(w * 0.44f, w * 0.62f), w * 0.78f, cloud)
            }
            WeatherCondition.CLOUDY -> drawCloud(Offset(w * 0.5f, w * 0.52f), w * 0.92f, cloud)
            WeatherCondition.FOG -> {
                drawCloud(Offset(w * 0.5f, w * 0.38f), w * 0.8f, cloud)
                for (i in 0..1) {
                    val y = w * (0.72f + i * 0.16f)
                    drawLine(cloud, Offset(w * 0.12f, y), Offset(w * 0.88f, y), w * 0.07f, StrokeCap.Round)
                }
            }
            WeatherCondition.DRIZZLE -> { drawCloud(Offset(w * 0.5f, w * 0.38f), w * 0.86f, cloud); drawDrops(2, 0.12f, drop) }
            WeatherCondition.RAIN -> { drawCloud(Offset(w * 0.5f, w * 0.38f), w * 0.86f, cloud); drawDrops(3, 0.18f, drop) }
            WeatherCondition.HEAVY_RAIN -> { drawCloud(Offset(w * 0.5f, w * 0.36f), w * 0.9f, cloud); drawDrops(4, 0.26f, drop) }
            WeatherCondition.THUNDERSTORM -> {
                drawCloud(Offset(w * 0.5f, w * 0.36f), w * 0.9f, cloud)
                val p = Path().apply {
                    moveTo(w * 0.56f, w * 0.55f); lineTo(w * 0.40f, w * 0.78f); lineTo(w * 0.52f, w * 0.78f)
                    lineTo(w * 0.44f, w * 0.98f); lineTo(w * 0.66f, w * 0.70f); lineTo(w * 0.54f, w * 0.70f); close()
                }
                drawPath(p, bolt)
            }
            WeatherCondition.SNOW -> { drawCloud(Offset(w * 0.5f, w * 0.38f), w * 0.86f, cloud); drawFlakes(listOf(0.3f, 0.5f, 0.7f), drop) }
            WeatherCondition.SLEET -> {
                drawCloud(Offset(w * 0.5f, w * 0.38f), w * 0.86f, cloud)
                drawLine(drop, Offset(w * 0.36f, w * 0.68f), Offset(w * 0.31f, w * 0.88f), w * 0.07f, StrokeCap.Round)
                drawFlakes(listOf(0.62f), drop)
            }
            WeatherCondition.WINDY -> {
                val stroke = Stroke(w * 0.07f, cap = StrokeCap.Round)
                listOf(0.3f to 0.7f, 0.5f to 0.9f, 0.7f to 0.6f).forEach { (y, len) ->
                    val p = Path().apply {
                        moveTo(w * 0.1f, w * y)
                        lineTo(w * len, w * y)
                        quadraticTo(w * (len + 0.12f), w * (y - 0.02f), w * (len + 0.08f), w * (y - 0.12f))
                    }
                    drawPath(p, cloud, style = stroke)
                }
            }
        }
    }
}

private fun DrawScope.drawSun(c: Offset, r: Float, color: Color) {
    drawCircle(color, r, c)
    for (i in 0 until 8) {
        val a = i * Math.PI / 4
        val from = Offset(c.x + (r * 1.45f) * cos(a).toFloat(), c.y + (r * 1.45f) * sin(a).toFloat())
        val to = Offset(c.x + (r * 2.0f) * cos(a).toFloat(), c.y + (r * 2.0f) * sin(a).toFloat())
        drawLine(color, from, to, r * 0.32f, StrokeCap.Round)
    }
}

/** Crescent: a disc with a second disc cut out of it. */
private fun DrawScope.drawMoon(c: Offset, r: Float, color: Color) {
    val path = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(c, r))
        op(
            this,
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.35f), r * 0.9f)) },
            androidx.compose.ui.graphics.PathOperation.Difference,
        )
    }
    drawPath(path, color)
}

private fun DrawScope.drawCloud(c: Offset, width: Float, color: Color) {
    val h = width * 0.5f
    val left = c.x - width / 2
    val bottom = c.y + h / 2
    drawRoundRect(color, Offset(left, bottom - h * 0.55f), Size(width, h * 0.55f), CornerRadius(h * 0.28f))
    drawCircle(color, h * 0.36f, Offset(left + width * 0.32f, bottom - h * 0.52f))
    drawCircle(color, h * 0.48f, Offset(left + width * 0.6f, bottom - h * 0.58f))
}

private fun DrawScope.drawDrops(count: Int, length: Float, color: Color) {
    val w = size.width
    val step = 0.6f / (count + 1)
    for (i in 1..count) {
        val x = w * (0.2f + step * i + 0.04f)
        val top = w * 0.68f
        drawLine(color, Offset(x, top), Offset(x - w * 0.05f, top + w * length), w * 0.07f, StrokeCap.Round)
    }
}

private fun DrawScope.drawFlakes(xs: List<Float>, color: Color) {
    val w = size.width
    xs.forEachIndexed { i, x -> drawCircle(color, w * 0.06f, Offset(w * x, w * (0.74f + (i % 2) * 0.12f))) }
}

/** Night when the hour is before sunrise or after sunset of its day (daily data); unknown → day. */
fun isNight(time: Instant, daily: List<DailyForecast>, zone: ZoneId): Boolean {
    val day = daily.firstOrNull { it.date.isSameDay(time, zone) } ?: return false
    val sunrise = day.sunrise ?: return false
    val sunset = day.sunset ?: return false
    return time.isBefore(sunrise) || !time.isBefore(sunset)
}

/** Fades the content out at a scrollable edge, so a row clearly continues beyond it. */
fun Modifier.edgeFade(fadeStart: Boolean, fadeEnd: Boolean): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 32.dp.toPx().coerceAtMost(size.width / 4)
        if (fadeStart) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                size = Size(fade, size.height), blendMode = BlendMode.DstIn,
            )
        }
        if (fadeEnd) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstIn,
            )
        }
    }
