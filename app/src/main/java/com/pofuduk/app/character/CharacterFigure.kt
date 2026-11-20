package com.pofuduk.app.character

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.pofuduk.app.ui.theme.CharacterColors
import com.pofuduk.core.model.CharacterAppearance
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.TapReaction
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.speech.LipSync
import com.pofuduk.core.speech.MouthPose
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Tap reaction in progress at [time] (epoch seconds), or null. */
data class ActiveReaction(val kind: TapReaction, val progress: Double, val laugh: String)

fun CharacterAppearance.reactionAt(time: Double): ActiveReaction? {
    val playback = tapReaction ?: return null
    val p = playback.progress(time) ?: return null
    return ActiveReaction(playback.reaction, p, playback.laugh)
}

/** All procedural motion in one place (pure function of time). */
private class Motion(mood: CharacterMood, theme: WeatherTheme, t: Double) {
    var bob: Float
    val squashX: Float
    val squashY: Float
    var tilt: Float
    var sway: Float = 0f

    init {
        val breathe = sin(t * 2.0)
        bob = when (mood) {
            CharacterMood.CELEBRATING -> abs(sin(t * 4.0)) * 14
            CharacterMood.SPEAKING -> 4 + sin(t * 3.2) * 5
            CharacterMood.SLEEPING -> 2 + sin(t * 1.0) * 2
            CharacterMood.WARNING -> 3 + sin(t * 1.5) * 2
            else -> 5 + breathe * 5
        }.toFloat()
        squashX = (1 + breathe * 0.018).toFloat()
        squashY = (1 - breathe * 0.018).toFloat()
        tilt = when {
            theme == WeatherTheme.WINDY -> -5 + sin(t * 1.8) * 6
            mood == CharacterMood.LISTENING -> -6.0
            else -> sin(t * 0.9) * 1.5
        }.toFloat()
    }
}

/**
 * Draws Pofuduk in a 200×200 design space (callers scale the DrawScope). Port of the iOS SwiftUI figure;
 * consumes only [CharacterAppearance] so the renderer stays replaceable.
 */
fun DrawScope.drawPofuduk(appearance: CharacterAppearance, time: Double, cloudPath: Path) {
    val mood = appearance.mood
    val theme = appearance.theme
    val reaction = appearance.reactionAt(time)
    val motion = Motion(mood, theme, time)
    if (reaction?.kind == TapReaction.DANCE) {
        val beat = reaction.progress * 2 * PI * 3
        motion.bob = (abs(sin(beat)) * 18).toFloat()
        motion.tilt = (sin(beat) * 14).toFloat()
        motion.sway = (sin(beat / 2) * 16).toFloat()
    }
    val tossing = reaction?.kind == TapReaction.TOSS_GLASSES

    // Ground shadow
    val shadowWidth = 130f - motion.bob
    drawOval(Color.Black.copy(alpha = 0.08f), topLeft = Offset(100 - shadowWidth / 2, 184f), size = Size(shadowWidth, 16f))

    val bottom = Offset(100f, 200f)
    withTransform({
        translate(motion.sway, -motion.bob)
        rotate(motion.tilt, bottom)
        scale(motion.squashX, motion.squashY, bottom)
    }) {
        if (theme == WeatherTheme.RAINY) {
            drawUmbrella(time, Offset(156f, 18f), if (reaction?.kind == TapReaction.TWIRL_UMBRELLA) reaction.progress else null)
        }
        if (theme == WeatherTheme.WINDY && mood != CharacterMood.SLEEPING) drawKite(time)

        // Body: soft shadow, gradient fill, highlight, outline.
        translate(0f, 6f) { drawPath(cloudPath, CharacterColors.cloudShade.copy(alpha = 0.35f)) }
        drawPath(cloudPath, Brush.verticalGradient(listOf(CharacterColors.cloudTop, CharacterColors.cloudBottom), 0f, 200f))
        drawPath(cloudPath, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), Offset(80f, 50f), 70f))
        drawPath(cloudPath, CharacterColors.cloudShade.copy(alpha = 0.55f), style = Stroke(1.5f))

        drawFace(appearance, time, wearsSunglasses = appearance.wearsSunglasses && !tossing)

        if (theme == WeatherTheme.SNOWY || theme == WeatherTheme.WINDY) drawScarf(time, Offset(100f, 167f), windy = theme == WeatherTheme.WINDY)
        if (appearance.wearsSunglasses) drawSunglasses(Offset(100f, 117f), if (tossing) reaction?.progress else null)
        if (theme == WeatherTheme.STORMY) {
            drawUmbrellaHat(time, Offset(106f, 6f), if (reaction?.kind == TapReaction.SPIN_UMBRELLA_HAT) reaction.progress else null)
        }
        drawMoodDecorations(mood, time)
    }
}

/** Boolean union of a rounded base and five puffs → one clean outline. */
fun cloudBodyPath(): Path {
    var p = Path().apply { addRoundRect(RoundRect(Rect(18f, 82f, 182f, 176f), CornerRadius(46f, 46f))) }
    for ((x, y, r) in listOf(Triple(62f, 98f, 46f), Triple(108f, 76f, 56f), Triple(152f, 100f, 42f), Triple(36f, 132f, 32f), Triple(168f, 134f, 30f))) {
        val circle = Path().apply { addOval(Rect(x - r, y - r, x + r, y + r)) }
        p = Path().apply { op(p, circle, PathOperation.Union) }
    }
    return p
}

// Face

private enum class EyeStyle { OPEN, WIDE, CLOSED, HAPPY }
private enum class MouthStyle { SMILE, BIG_SMILE, OH, FLAT, FROWN, SPEAKING }

private fun DrawScope.drawFace(appearance: CharacterAppearance, time: Double, wearsSunglasses: Boolean) {
    val mood = appearance.mood
    val theme = appearance.theme
    val eyeStyle = when (mood) {
        CharacterMood.SLEEPING -> EyeStyle.CLOSED
        CharacterMood.HAPPY, CharacterMood.CELEBRATING -> EyeStyle.HAPPY
        CharacterMood.SURPRISED, CharacterMood.LISTENING -> EyeStyle.WIDE
        else -> EyeStyle.OPEN
    }
    // Inward-slanted brows read as angry; storms/warnings get raised, slightly nervous brows instead.
    val worried = mood == CharacterMood.WARNING || theme == WeatherTheme.STORMY || mood == CharacterMood.SAD ||
        mood == CharacterMood.ERROR || (theme == WeatherTheme.RAINY && (mood == CharacterMood.IDLE || mood == CharacterMood.SPEAKING))
    val mouthStyle = when (mood) {
        CharacterMood.SPEAKING -> MouthStyle.SPEAKING
        CharacterMood.HAPPY, CharacterMood.CELEBRATING -> MouthStyle.BIG_SMILE
        CharacterMood.SURPRISED, CharacterMood.SLEEPING, CharacterMood.WARNING -> MouthStyle.OH
        CharacterMood.THINKING -> MouthStyle.FLAT
        CharacterMood.SAD, CharacterMood.ERROR -> MouthStyle.FROWN
        else -> MouthStyle.SMILE
    }
    val blink = if (time.mod(4.2) < 0.14) 0.12f else 1f
    val look = when (mood) {
        CharacterMood.THINKING -> Offset(4f, -5f)
        CharacterMood.LISTENING -> Offset(-3f, 0f)
        else -> Offset((sin(time * 0.5) * 1.5).toFloat(), 0f)
    }

    // Cheeks
    for (x in listOf(54f, 146f)) drawOval(CharacterColors.cheek.copy(alpha = 0.45f), Offset(x - 12, 131.5f), Size(24f, 13f))

    if (!wearsSunglasses) {
        for (x in listOf(75f, 125f)) drawEye(eyeStyle, Offset(x, 116f), blink, look)
        if (worried) {
            val brow = Stroke(4f, cap = StrokeCap.Round)
            drawLine(CharacterColors.eye, Offset(62f, 94f), Offset(84f, 88f), brow.width, StrokeCap.Round)
            drawLine(CharacterColors.eye, Offset(138f, 94f), Offset(116f, 88f), brow.width, StrokeCap.Round)
        }
    }

    val pose = when {
        mood != CharacterMood.SPEAKING -> MouthPose(0.0, 0.0)
        appearance.speechCue != null -> LipSync.pose(appearance.speechCue!!, Instant.ofEpochMilli((time * 1000).toLong()))
        else -> {
            val v = abs(sin(time * 11)) * (0.6 + 0.4 * sin(time * 3.7))
            MouthPose(0.25 + 0.75 * max(0.0, v), 0.0)
        }
    }
    drawMouth(mouthStyle, pose, Offset(100f, 145f))
}

private fun DrawScope.drawEye(style: EyeStyle, c: Offset, blink: Float, look: Offset) {
    when (style) {
        EyeStyle.OPEN, EyeStyle.WIDE -> {
            val k = if (style == EyeStyle.WIDE) 1.18f else 1f
            scale(1f, blink, c) {
                val w = 22 * k
                val h = 30 * k
                drawRoundRect(CharacterColors.eye, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(w / 2, w / 2))
                drawCircle(Color.White, 4.5f, Offset(c.x - 4 + look.x, c.y - 7 + look.y))
                drawCircle(Color.White.copy(alpha = 0.8f), 2f, Offset(c.x + 4 + look.x, c.y + 6 + look.y))
            }
        }
        EyeStyle.CLOSED -> drawPath(arc(Rect(c.x - 11, c.y - 4, c.x + 11, c.y + 4), bulgeUp = false), CharacterColors.eye, style = Stroke(4f, cap = StrokeCap.Round))
        EyeStyle.HAPPY -> drawPath(arc(Rect(c.x - 12, c.y - 5.5f, c.x + 12, c.y + 5.5f), bulgeUp = true), CharacterColors.eye, style = Stroke(4.5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawMouth(style: MouthStyle, pose: MouthPose, c: Offset) {
    val color = CharacterColors.mouth
    when (style) {
        MouthStyle.SMILE -> drawPath(arc(Rect(c.x - 13, c.y - 5, c.x + 13, c.y + 5), bulgeUp = false), color, style = Stroke(4f, cap = StrokeCap.Round))
        MouthStyle.BIG_SMILE -> {
            val r = Rect(c.x - 15, c.y - 8, c.x + 15, c.y + 8)
            val smile = Path().apply {
                moveTo(r.left, r.top); lineTo(r.right, r.top)
                quadraticTo(r.center.x, r.bottom + r.height * 0.9f, r.left, r.top); close()
            }
            drawPath(smile, color)
            clipPath(smile) { drawOval(CharacterColors.cheek, Offset(c.x - 7, c.y + 5 - 3), Size(14f, 6f)) }
        }
        MouthStyle.OH -> drawOval(color, Offset(c.x - 5.5f, c.y - 6.5f), Size(11f, 13f))
        MouthStyle.FLAT -> drawRoundRect(color, Offset(c.x - 10, c.y - 2), Size(20f, 4f), CornerRadius(2f, 2f))
        MouthStyle.FROWN -> drawPath(arc(Rect(c.x - 11, c.y - 4, c.x + 11, c.y + 4), bulgeUp = true), color, style = Stroke(4f, cap = StrokeCap.Round))
        MouthStyle.SPEAKING -> {
            val open = pose.open.toFloat()
            val w = (24 - 9 * pose.round).toFloat()
            val h = 3 + 17 * open
            val mouth = Path().apply { addOval(Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)) }
            drawPath(mouth, color)
            clipPath(mouth) {
                val th = max(2f, 6 * open)
                drawOval(CharacterColors.cheek, Offset(c.x - 6, c.y + 5 * open - th / 2), Size(12f, th))
            }
        }
    }
}

/** bulgeUp = ∩ (happy eyes, frown), otherwise ∪ (smile, closed eyes). */
private fun arc(r: Rect, bulgeUp: Boolean): Path = Path().apply {
    val y = if (bulgeUp) r.bottom else r.top
    moveTo(r.left, y)
    quadraticTo(r.center.x, if (bulgeUp) r.top - r.height else r.bottom + r.height, r.right, y)
}

// Accessories

private val umbrellaHandle = Color(0.45f, 0.32f, 0.28f)

/** Canopy with a scalloped bottom edge, in [r]. */
private fun canopy(r: Rect): Path = Path().apply {
    moveTo(r.left, r.bottom)
    quadraticTo(r.center.x, r.top - r.height * 0.9f, r.right, r.bottom)
    val n = 4
    val w = r.width / n
    for (i in n - 1 downTo 0) {
        val x0 = r.left + i * w
        quadraticTo(x0 + w / 2, r.bottom - r.height * 0.28f, x0, r.bottom)
    }
    close()
}

/** Held umbrella; frame 100×96 centred at [center]. Twirl = spin on its handle + a little hop. */
private fun DrawScope.drawUmbrella(time: Double, center: Offset, twirl: Double?) {
    val origin = Offset(center.x - 50, center.y - 48)
    val pivot = Offset(center.x, center.y + 48)
    val p = twirl ?: 0.0
    val c = if (twirl == null) 1.0 else cos(p * 4 * PI)
    val flip = (if (c >= 0) max(0.05, c) else min(-0.05, c)).toFloat()
    withTransform({
        translate(0f, (-sin(p * PI) * 22).toFloat())
        rotate((sin(p * 4 * PI) * 18).toFloat(), pivot)
        scale(flip, 1f, Offset(center.x, center.y))
        rotate((-14 + sin(time * 2.2) * 3).toFloat(), pivot)
    }) {
        translate(origin.x, origin.y) {
            val handle = Path().apply { moveTo(50f, 30f); lineTo(50f, 82f); quadraticTo(44f, 92f, 38f, 82f) }
            drawPath(handle, umbrellaHandle, style = Stroke(3.5f, cap = StrokeCap.Round))
            val r = Rect(0f, 4f, 100f, 44f)
            drawPath(canopy(r), Brush.horizontalGradient(listOf(Color(1f, 0.62f, 0.62f), Color(1f, 0.78f, 0.55f)), r.left, r.right))
            drawPath(canopy(r), Color.White.copy(alpha = 0.7f), style = Stroke(1.5f))
            drawCircle(Color(1f, 0.62f, 0.62f), 3.5f, Offset(50f, 3.5f))
        }
    }
}

/** Thunderstorm joke: a tiny umbrella worn like a hat; frame 80×46 centred at [center]. */
private fun DrawScope.drawUmbrellaHat(time: Double, center: Offset, flip: Double?) {
    val phase = time.mod(2.6)
    val shiver = if (phase < 0.35) sin(phase * 60) * 4 else 0.0
    val origin = Offset(center.x - 40, center.y - 23)
    val p = flip ?: 0.0
    withTransform({
        translate(0f, (-sin(p * PI) * 60).toFloat())
        rotate((p * 360).toFloat(), center)
        rotate((-6 + shiver + sin(time * 1.6) * 2).toFloat(), Offset(center.x, center.y + 23))
    }) {
        translate(origin.x, origin.y) {
            drawRoundRect(umbrellaHandle, Offset(38f, 26f), Size(4f, 18f), CornerRadius(2f, 2f))
            val r = Rect(1f, 4f, 79f, 34f)
            drawPath(canopy(r), Brush.horizontalGradient(listOf(Color(0.42f, 0.62f, 1f), Color(0.55f, 0.85f, 1f)), r.left, r.right))
            drawPath(canopy(r), Color.White.copy(alpha = 0.75f), style = Stroke(1.5f))
            drawCircle(Color(0.42f, 0.62f, 1f), 3.5f, Offset(40f, 3.5f))
        }
    }
}

/** Sunglasses centred at [c]; toss = fly up spinning and land back on the nose. */
private fun DrawScope.drawSunglasses(c: Offset, toss: Double?) {
    val p = toss ?: 0.0
    val a = sin(p * PI)
    withTransform({
        translate((a * 18).toFloat(), (-a * 120).toFloat())
        rotate((p * 720).toFloat(), c)
    }) {
        for (dx in listOf(-25f, 25f)) {
            val lc = Offset(c.x + dx, c.y)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0.18f, 0.16f, 0.32f), Color(0.36f, 0.22f, 0.48f)), lc.y - 14, lc.y + 14),
                Offset(lc.x - 19, lc.y - 14), Size(38f, 28f), CornerRadius(12f, 12f),
            )
            rotate(-20f, Offset(lc.x - 6, lc.y - 6)) {
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(lc.x - 13, lc.y - 8), Size(14f, 4f), CornerRadius(2f, 2f))
            }
        }
        drawRoundRect(Color(0.12f, 0.12f, 0.2f), Offset(c.x - 8, c.y - 8), Size(16f, 4f), CornerRadius(2f, 2f))
    }
}

/** Windy: a kite on a string, held by Pofuduk (200×200 space). */
private fun DrawScope.drawKite(time: Double) {
    val kite = Offset((182 + sin(time * 1.3) * 10).toFloat(), (-10 + cos(time * 1.7) * 7).toFloat())
    val hand = Offset(176f, 128f)
    val string = Path().apply {
        moveTo(hand.x, hand.y)
        quadraticTo((hand.x + kite.x) / 2 + 18, (hand.y + kite.y) / 2 + 14, kite.x - 2, kite.y + 22)
    }
    drawPath(string, Color(0.45f, 0.38f, 0.36f, 0.8f), style = Stroke(1.5f, cap = StrokeCap.Round))
    rotate((sin(time * 2.1) * 0.2 * 180 / PI).toFloat(), kite) {
        val diamond = Path().apply {
            moveTo(kite.x, kite.y - 22); lineTo(kite.x + 16, kite.y); lineTo(kite.x, kite.y + 24); lineTo(kite.x - 16, kite.y); close()
        }
        drawPath(diamond, Brush.verticalGradient(listOf(Color(1f, 0.55f, 0.45f), Color(1f, 0.82f, 0.4f)), kite.y - 22, kite.y + 24))
        val spar = Color.White.copy(alpha = 0.7f)
        drawLine(spar, Offset(kite.x, kite.y - 22), Offset(kite.x, kite.y + 24), 1.2f)
        drawLine(spar, Offset(kite.x - 16, kite.y), Offset(kite.x + 16, kite.y), 1.2f)
    }
    for (i in 0 until 3) {
        val k = (i + 1).toDouble()
        val bow = Offset((kite.x - 4 * k + sin(time * 3 + k) * 3).toFloat(), (kite.y + 24 + 9 * k).toFloat())
        rotate((sin(time * 3 + k) * 30).toFloat(), bow) {
            drawRoundRect(Color(1f, 0.62f, 0.62f), Offset(bow.x - 4, bow.y - 2), Size(8f, 4f), CornerRadius(2f, 2f))
        }
    }
}

/** Scarf centred at [c]; the tail flutters when windy, hangs when snowy. */
private fun DrawScope.drawScarf(time: Double, c: Offset, windy: Boolean) {
    val red = Color(0.93f, 0.42f, 0.48f)
    val stripe = Color(1f, 0.86f, 0.86f, 0.7f)
    drawRoundRect(red, Offset(c.x - 71, c.y - 8), Size(142f, 16f), CornerRadius(8f, 8f))
    for (i in 0 until 6) drawRoundRect(stripe, Offset(c.x - 52 + i * 20, c.y - 5.5f), Size(4f, 11f), CornerRadius(2f, 2f))
    val top = Offset(c.x + 38, c.y)
    val angle = if (windy) 70 + sin(time * 7) * 14 else 8 + sin(time * 1.5) * 4
    rotate(angle.toFloat(), top) {
        drawRoundRect(red, Offset(top.x - 9, top.y), Size(18f, 36f), CornerRadius(6f, 6f))
        drawRoundRect(stripe, Offset(top.x - 7, top.y + 26), Size(14f, 4f), CornerRadius(2f, 2f))
    }
}

/** Small symbols that make mood readable at a glance (all drawn, purely decorative). */
private fun DrawScope.drawMoodDecorations(mood: CharacterMood, time: Double) {
    val accent = Color(0.36f, 0.32f, 0.82f)
    when (mood) {
        CharacterMood.SLEEPING -> for (i in 0 until 3) {
            val phase = (time * 0.6 + i / 3.0).mod(1.0)
            val s = 9f + i * 3f
            val o = Offset((160 + phase * 16).toFloat() - s / 2, (50 - phase * 40).toFloat() - s / 2)
            val z = Path().apply { moveTo(o.x, o.y); lineTo(o.x + s, o.y); lineTo(o.x, o.y + s); lineTo(o.x + s, o.y + s) }
            drawPath(z, accent.copy(alpha = (1 - phase).toFloat()), style = Stroke(2.5f, cap = StrokeCap.Round))
        }
        CharacterMood.THINKING -> {
            drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(141f, 19.5f), Size(54f, 21f), CornerRadius(10.5f, 10.5f))
            for (i in 0 until 3) {
                val a = 0.35 + 0.65 * max(0.0, sin(time * 5 - i * 0.8))
                drawCircle(accent.copy(alpha = a.toFloat()), 3.5f, Offset(156f + i * 12f, 30f))
            }
        }
        CharacterMood.LISTENING -> for (i in 0 until 3) {
            val a = 0.4 + 0.6 * max(0.0, sin(time * 4 - i))
            val w = 14f + i * 9f
            val cx = 10f - i * 6f
            rotate(-90f, Offset(cx, 112f)) {
                drawPath(arc(Rect(cx - w / 2, 109f, cx + w / 2, 115f), bulgeUp = true), accent.copy(alpha = a.toFloat()), style = Stroke(3f))
            }
        }
        CharacterMood.SURPRISED -> drawExclamation(Offset(170f, 36f), accent)
        CharacterMood.WARNING -> {
            val tri = Path().apply { moveTo(172f, 22f); lineTo(185f, 46f); lineTo(159f, 46f); close() }
            drawPath(tri, Color(0xFFFF9500))
            drawLine(Color.White, Offset(172f, 30f), Offset(172f, 38f), 3f, StrokeCap.Round)
            drawCircle(Color.White, 1.7f, Offset(172f, 42.5f))
        }
        CharacterMood.CELEBRATING -> {
            val xs = listOf(20f, 180f, 30f, 170f, 100f)
            val ys = listOf(40f, 50f, 160f, 160f, 8f)
            for (i in 0 until 5) {
                val a = 0.4 + 0.6 * max(0.0, sin(time * 3 + i))
                drawSparkle(Offset(xs[i], ys[i]), 6f + (i % 3) * 2f, Color(1f, 0.86f, 0.45f, a.toFloat()))
            }
        }
        CharacterMood.ERROR -> {
            val q = Path().apply {
                moveTo(162f, 30f); cubicTo(162f, 22f, 175f, 22f, 175f, 30f); cubicTo(175f, 36f, 168f, 36f, 168f, 42f)
            }
            drawPath(q, Color(0.36f, 0.38f, 0.52f), style = Stroke(3.5f, cap = StrokeCap.Round))
            drawCircle(Color(0.36f, 0.38f, 0.52f), 2f, Offset(168f, 48f))
        }
        else -> Unit
    }
}

private fun DrawScope.drawExclamation(c: Offset, color: Color) {
    drawLine(color, Offset(c.x, c.y - 12), Offset(c.x, c.y + 4), 5f, StrokeCap.Round)
    drawCircle(color, 3f, Offset(c.x, c.y + 12))
}

private fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color) {
    val s = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y); quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y); quadraticTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(s, color)
}
