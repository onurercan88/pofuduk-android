package com.pofuduk.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.pofuduk.app.character.cloudBodyPath
import com.pofuduk.app.character.drawPofuduk
import com.pofuduk.core.model.CharacterAppearance
import com.pofuduk.core.model.CharacterMood
import com.pofuduk.core.model.WeatherTheme
import java.io.File

/**
 * Debug-only: renders Play Store art from the same character renderer as the app (single source of truth, like the
 * iOS AppIconView). Output: files/store-art/{icon-512.png, feature-1024x500.png}.
 */
object StoreArt {
    private const val STILL_TIME = 1.0
    private val happy = CharacterAppearance(CharacterMood.HAPPY, WeatherTheme.CLOUDY)

    fun export(context: Context): File {
        val dir = File(context.filesDir, "store-art").apply { mkdirs() }
        save(render(512, 512) { icon() }, File(dir, "icon-512.png"))
        save(render(1024, 500) { feature() }, File(dir, "feature-1024x500.png"))
        // Adaptive launcher icon foreground (108 dp canvas, content inside the 66 dp safe zone), per density.
        mapOf("mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432).forEach { (density, px) ->
            save(render(px, px) { launcherForeground() }, File(dir, "ic_launcher_foreground-$density.png"))
        }
        return dir
    }

    private fun render(w: Int, h: Int, block: DrawScope.() -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(w.toFloat(), h.toFloat()), block)
        return bitmap
    }

    private fun save(bitmap: Bitmap, file: File) = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun DrawScope.background() {
        drawRect(Brush.linearGradient(listOf(Color(1f, 0.88f, 0.68f), Color(0.70f, 0.80f, 1f)), Offset.Zero, Offset(size.width, size.height)))
    }

    private fun DrawScope.sun(center: Offset, radius: Float) {
        drawCircle(Brush.radialGradient(listOf(Color(1f, 0.93f, 0.6f), Color(1f, 0.74f, 0.35f)), center, radius), radius, center)
    }

    /** Character (200×200 design space) scaled by [k], centred at [center]. */
    private fun DrawScope.character(center: Offset, k: Float) {
        val path = cloudBodyPath()
        translate(center.x - 100 * k, center.y - 100 * k) {
            scale(k, k, pivot = Offset.Zero) { drawPofuduk(happy, STILL_TIME, path) }
        }
    }

    /** Same composition as the iOS icon (1024 → 512). */
    private fun DrawScope.icon() {
        background()
        sun(Offset(381f, 131f), 75f)
        character(Offset(256f, 286f), 2.05f)
    }

    /** Transparent layer; the gradient comes from ic_launcher_background. Safe zone = central 61 %. */
    private fun DrawScope.launcherForeground() {
        val s = size.width
        sun(Offset(s * 0.66f, s * 0.33f), s * 0.11f)
        character(Offset(s * 0.5f, s * 0.53f), s * 0.6f / 200f)
    }

    private fun DrawScope.feature() {
        background()
        sun(Offset(975f, 35f), 72f)
        character(Offset(250f, 270f), 1.9f)
        val ink = android.graphics.Color.rgb(43, 48, 84)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 120f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val tagline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 42f }
        drawContext.canvas.nativeCanvas.apply {
            drawText("Pofuduk", 480f, 245f, title)
            drawText("Havayı sana Türkçe anlatan", 484f, 315f, tagline)
            drawText("sevimli bulut arkadaşın", 484f, 368f, tagline)
        }
    }
}
