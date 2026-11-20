package com.pofuduk.app.character

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.pofuduk.core.model.WeatherTheme
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** Ambient weather effects behind the character, drawn procedurally (port of the iOS Canvas). */
fun DrawScope.drawWeatherEffects(theme: WeatherTheme, time: Double) {
    when (theme) {
        WeatherTheme.SUNNY -> drawSun(time)
        WeatherTheme.RAINY -> drawRain(time, 34, 260.0)
        WeatherTheme.STORMY -> {
            drawRain(time, 50, 380.0)
            drawLightning(time)
        }
        WeatherTheme.SNOWY -> drawSnow(time)
        WeatherTheme.WINDY -> drawWind(time)
        WeatherTheme.NIGHT -> drawNight(time)
        WeatherTheme.CLOUDY -> drawBackgroundClouds(time)
    }
}

/** Deterministic pseudo-random in 0 until 1 for particle [i]. */
private fun rand(i: Int, salt: Int = 0): Double {
    val v = sin((i * 127 + salt * 311) * 12.9898) * 43758.5453
    return v - floor(v)
}

private fun DrawScope.drawSun(time: Double) {
    val center = Offset(size.width * 0.78f, size.height * 0.16f)
    rotate((time * 0.25 * 180 / Math.PI).mod(360.0).toFloat(), center) {
        for (i in 0 until 12) {
            rotate(i * 30f, center) {
                val pulse = (1 + 0.08 * sin(time * 2 + i)).toFloat()
                drawRoundRect(
                    Color(1f, 0.78f, 0.35f, 0.75f), Offset(center.x + 44, center.y - 4), Size(22 * pulse, 8f), CornerRadius(4f, 4f),
                )
            }
        }
    }
    drawCircle(
        Brush.radialGradient(listOf(Color(1f, 0.92f, 0.55f), Color(1f, 0.72f, 0.32f)), center, 40f),
        36f, center,
    )
}

private fun DrawScope.drawRain(time: Double, count: Int, speed: Double) {
    val color = Color(0.45f, 0.6f, 0.95f, 0.55f)
    for (i in 0 until count) {
        val x = (rand(i) * size.width).toFloat()
        val offset = rand(i, 1) * size.height
        val y = ((time * speed * (0.8 + rand(i, 2) * 0.4) + offset).mod(size.height + 30.0) - 15).toFloat()
        drawLine(color, Offset(x, y), Offset(x - 3, y + 14), 2.2f, StrokeCap.Round)
    }
}

private fun DrawScope.drawLightning(time: Double) {
    val phase = time.mod(5.5)
    if (phase >= 0.22) return
    val flicker = phase < 0.08 || (phase > 0.14 && phase < 0.2)
    if (!flicker) return
    drawRect(Color.White.copy(alpha = 0.25f))
    val x = size.width * 0.18f
    val bolt = Path().apply {
        moveTo(x + 14, 10f); lineTo(x, 52f); lineTo(x + 12, 52f); lineTo(x - 2, 96f)
        lineTo(x + 24, 42f); lineTo(x + 12, 42f); lineTo(x + 26, 10f); close()
    }
    drawPath(bolt, Color(1f, 0.88f, 0.4f))
}

private fun DrawScope.drawSnow(time: Double) {
    for (i in 0 until 40) {
        val r = (2 + rand(i, 3) * 3.5).toFloat()
        val baseX = rand(i) * size.width
        val y = ((time * (22 + rand(i, 2) * 26) + rand(i, 1) * size.height).mod(size.height + 10.0) - 5).toFloat()
        val x = (baseX + sin(time * 1.2 + i) * 10).toFloat()
        drawCircle(Color.White.copy(alpha = 0.9f), r, Offset(x + r, y + r))
    }
}

private fun DrawScope.drawWind(time: Double) {
    for (i in 0 until 7) {
        val y = (size.height * (0.15 + rand(i) * 0.7)).toFloat()
        val length = 50 + rand(i, 1) * 60
        val x = ((time * (140 + rand(i, 2) * 80) + rand(i, 3) * size.width).mod(size.width + length * 2) - length).toFloat()
        val l = length.toFloat()
        val p = Path().apply { moveTo(x, y); cubicTo(x + l * 0.35f, y - 8, x + l * 0.65f, y + 8, x + l, y) }
        drawPath(p, Color.White.copy(alpha = 0.65f), style = Stroke(2.5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawNight(time: Double) {
    for (i in 0 until 24) {
        val x = (rand(i) * size.width).toFloat()
        val y = (rand(i, 1) * size.height * 0.6).toFloat()
        val twinkle = (0.3 + 0.7 * abs(sin(time * (0.8 + rand(i, 2)) + i))).toFloat()
        drawCircle(Color.White.copy(alpha = twinkle), 1.5f, Offset(x + 1.5f, y + 1.5f))
    }
    val c = Offset(size.width * 0.8f, size.height * 0.15f)
    val moon = Path().apply {
        op(
            Path().apply { addOval(Rect(c.x - 22, c.y - 22, c.x + 22, c.y + 22)) },
            Path().apply { addOval(Rect(c.x - 10, c.y - 30, c.x + 34, c.y + 14)) },
            PathOperation.Difference,
        )
    }
    drawPath(moon, Color(1f, 0.93f, 0.7f))
}

private fun DrawScope.drawBackgroundClouds(time: Double) {
    for (i in 0 until 3) {
        val w = (90 + rand(i) * 50).toFloat()
        val x = ((time * (8 + i * 4) + rand(i, 1) * size.width).mod((size.width + w).toDouble()) - w / 2).toFloat()
        val y = size.height * (0.08f + i * 0.12f)
        val color = Color.White.copy(alpha = 0.35f)
        translate(x, y) {
            drawOval(color, Offset(0f, 10f), Size(w, w * 0.3f))
            drawOval(color, Offset(w * 0.2f, 0f), Size(w * 0.45f, w * 0.35f))
            drawOval(color, Offset(w * 0.45f, 4f), Size(w * 0.4f, w * 0.3f))
        }
    }
}
