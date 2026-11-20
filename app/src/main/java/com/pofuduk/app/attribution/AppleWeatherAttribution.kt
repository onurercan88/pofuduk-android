package com.pofuduk.app.attribution

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pofuduk.app.ui.theme.LocalPalette
import com.pofuduk.core.provider.UrlConnectionHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Apple Weather mark from Apple's attribution API (required wherever WeatherKit data is shown), cached per process.
 * The requests carry no location. Any failure → null and the UI shows the service name as text.
 */
object AppleWeatherAttributionLoader {
    const val LEGAL_PAGE = "https://weatherkit.apple.com/legal-attribution.html"
    private const val BASE = "https://weatherkit.apple.com"
    private val mutex = Mutex()
    private var marks: Map<Boolean, ImageBitmap>? = null

    suspend fun mark(dark: Boolean): ImageBitmap? = mutex.withLock {
        marks?.let { return it[dark] }
        val loaded = runCatching { load() }.getOrNull()?.takeIf { it.isNotEmpty() }
        marks = loaded
        loaded?.get(dark)
    }

    private suspend fun load(): Map<Boolean, ImageBitmap> {
        val http = UrlConnectionHttpClient(timeoutMillis = 10_000, retry = false)
        val response = http.get("$BASE/attribution/tr-TR")
        if (response.statusCode != 200) return emptyMap()
        val json = JSONObject(response.text)
        val result = mutableMapOf<Boolean, ImageBitmap>()
        for ((dark, key) in listOf(false to "logoLight@3x", true to "logoDark@3x")) {
            val path = json.optString(key).takeIf { it.startsWith("/") } ?: continue // only Apple's own host
            val image = http.get(BASE + path, mapOf("Accept" to "image/png"))
            if (image.statusCode != 200) continue
            BitmapFactory.decodeByteArray(image.body, 0, image.body.size)?.let { result[dark] = it.asImageBitmap() }
        }
        return result
    }
}

/**
 * Apple Weather mark + legal attribution link.
 * [isPrimarySource] = true: Apple Weather is the forecast source; false: it only cross-checked "now".
 */
@Composable
fun AppleWeatherAttribution(isPrimarySource: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val uri = LocalUriHandler.current
    var mark by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(palette.isDark) { mark = AppleWeatherAttributionLoader.mark(palette.isDark) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        val current = mark
        if (current != null) {
            Image(current, contentDescription = "Apple Weather", contentScale = ContentScale.Fit, modifier = Modifier.height(14.dp))
        } else {
            Text("Apple Weather", color = palette.inkSecondary, fontSize = 12.sp)
        }
        Text(
            if (isPrimarySource) "Diğer veri kaynakları" else "Anlık durum doğrulaması · Diğer veri kaynakları",
            color = palette.accent, fontSize = 12.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { uri.openUri(AppleWeatherAttributionLoader.LEGAL_PAGE) }
                .padding(vertical = 14.dp),
        )
    }
}
