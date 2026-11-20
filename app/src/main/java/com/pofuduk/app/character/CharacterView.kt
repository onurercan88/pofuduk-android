package com.pofuduk.app.character

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pofuduk.core.model.CharacterAppearance
import com.pofuduk.core.model.CharacterMood
import kotlin.math.min

/** A frozen moment with open eyes, for "remove animations" (Reduce Motion) and still renders. */
private const val STILL_TIME = 1.0

/** True when the user turned animations off (Settings › Accessibility › Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Public entry point of the character system. Consumes only [CharacterAppearance], so the Canvas renderer
 * can be replaced (e.g. by a Rive state machine) without touching features.
 */
@Composable
fun CharacterView(appearance: CharacterAppearance, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val reduceMotion = rememberReduceMotion()
    var wallClock by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        while (true) withFrameMillis { wallClock = System.currentTimeMillis() / 1000.0 }
    }
    val time = if (reduceMotion) STILL_TIME else wallClock
    val cloud = remember { cloudBodyPath() }
    val description = accessibilityText(appearance)

    Box(
        modifier
            .size(size * 1.7f, size * 1.35f)
            .clipToBounds()
            .semantics { contentDescription = description; role = Role.Image },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawWeatherEffects(appearance.theme, time) }
        Canvas(Modifier.requiredSize(size)) {
            val k = min(this.size.width, this.size.height) / 200f
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPofuduk(appearance, time, cloud) }
        }
        appearance.reactionAt(time)?.let { LaughBubble(it, size) }
    }
}

/** "Hihi!" in a little bubble that floats up and fades out. */
@Composable
private fun LaughBubble(reaction: ActiveReaction, size: Dp) {
    val p = reaction.progress
    val k = size / 200
    Text(
        reaction.laugh,
        color = Color(0.36f, 0.32f, 0.82f),
        fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp,
        modifier = Modifier
            .offset(x = k * 38, y = k * (-86 - (p * 18).toFloat()))
            .scale((0.6 + 0.4 * minOf(1.0, p * 5)).toFloat())
            .alpha(if (p > 0.75) ((1 - p) * 4).toFloat() else 1f)
            .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun accessibilityText(a: CharacterAppearance): String {
    val mood = when (a.mood) {
        CharacterMood.SPEAKING -> "konuşuyor"
        CharacterMood.LISTENING -> "dinliyor"
        CharacterMood.THINKING -> "düşünüyor"
        CharacterMood.WARNING -> "uyarı modunda"
        CharacterMood.SLEEPING -> "uyuyor"
        CharacterMood.ERROR -> "bilgiye ulaşamadı"
        else -> ""
    }
    return listOf(a.theme.accessibilityDescription, mood).filter { it.isNotEmpty() }.joinToString(", ")
}
