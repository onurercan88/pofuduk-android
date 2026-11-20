package com.pofuduk.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pofuduk.app.ui.theme.LocalPalette
import com.pofuduk.core.data.TurkishProvinces
import com.pofuduk.core.model.LocationIssue
import com.pofuduk.core.model.Place
import java.util.Locale

/** Pofuduk explains why location helps BEFORE the system prompt, and always offers an alternative. */
@Composable
fun LocationPromptCard(prompt: LocationPrompt, onAllow: () -> Unit, onOpenSettings: (servicesOff: Boolean) -> Unit, onChooseCity: () -> Unit) {
    val palette = LocalPalette.current
    val issue = (prompt as? LocationPrompt.Issue)?.issue
    val blocked = issue == LocationIssue.PERMISSION_DENIED || issue == LocationIssue.SERVICES_DISABLED
    val message = when (prompt) {
        LocationPrompt.Explanation ->
            "Bulunduğun yerin havasını anlatabilmem için yaklaşık konumuna ihtiyacım var. Konumunu yalnızca uygulama açıkken " +
                "ve sadece hava durumu için kullanırım. Seni takip etmem, kimseyle paylaşmam."
        // Android has no IP-based "approximate location" option, so the iOS wording that offers it is not used here.
        is LocationPrompt.Issue -> when (prompt.issue) {
            LocationIssue.PERMISSION_DENIED -> "Konum izni verilmedi. Sorun değil! İstersen bir şehir ya da ilçe seçebilirsin."
            else -> prompt.issue.turkishExplanation
        }
    }
    HomeCard {
        Text(
            if (prompt == LocationPrompt.Explanation) "Neredesin?" else "Konumunu bulamadım",
            color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(message, color = palette.inkSecondary, fontSize = 16.sp, lineHeight = 22.sp)
        Spacer(Modifier.padding(2.dp))
        val primary = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse)
        when {
            !blocked -> Button(onClick = onAllow, colors = primary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (prompt == LocationPrompt.Explanation) "📍 Konumumu kullan" else "Tekrar dene", fontSize = 16.sp)
            }
            else -> Button(
                onClick = { onOpenSettings(issue == LocationIssue.SERVICES_DISABLED) }, colors = primary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (issue == LocationIssue.SERVICES_DISABLED) "Konum servislerini aç" else "Ayarlardan izin ver", fontSize = 16.sp)
            }
        }
        OutlinedButton(onClick = onChooseCity, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("🏙️ Şehir veya ilçe seç", color = palette.ink, fontSize = 16.sp)
        }
    }
}

/** Province list (offline) + district/city search (platform geocoder). */
@Composable
fun LocationPickerDialog(
    results: List<Place>,
    onSearch: (String) -> Unit,
    onSelect: (Place) -> Unit,
    onUseDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.surfaceStrong.copy(alpha = 1f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Konum seç", color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kapat", color = palette.accent) }
            }
            OutlinedButton(onClick = onUseDevice, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("📍 Konumumu kullan", color = palette.ink)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; onSearch(it) },
                label = { Text("İl veya ilçe ara") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
                    focusedBorderColor = palette.accent, focusedLabelColor = palette.accent, unfocusedLabelColor = palette.inkSecondary,
                ),
            )
            val provinces = TurkishProvinces.search(query)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (query.trim().length >= 2 && results.isNotEmpty()) {
                    item { SectionTitle("İlçe ve şehir sonuçları") }
                    items(results, key = { "r-" + it.displayName }) { place ->
                        PickerRow(place.district ?: place.name, place.province?.takeIf { it != place.district }) { onSelect(place) }
                    }
                }
                if (provinces.isNotEmpty()) item { SectionTitle("İller") }
                items(provinces, key = { "p-" + it.plate }) { p ->
                    PickerRow(p.name, String.format(Locale.ROOT, "%02d", p.plate)) { onSelect(p.place) }
                }
                if (provinces.isEmpty() && results.isEmpty()) {
                    item { Text("Sonuç bulunamadı", color = palette.inkSecondary, modifier = Modifier.padding(vertical = 12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val palette = LocalPalette.current
    Text(
        text, color = palette.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun PickerRow(title: String, trailing: String?, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, color = palette.inkSecondary, fontSize = 13.sp, textAlign = TextAlign.End) }
        }
        HorizontalDivider(color = palette.inkSecondary.copy(alpha = 0.15f))
    }
}

@Composable
fun FailedCard(reason: String, onRetry: () -> Unit, onChooseCity: () -> Unit) {
    val palette = LocalPalette.current
    HomeCard {
        Text("Hava durumu alınamadı", color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(reason, color = palette.inkSecondary, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse),
            ) { Text("Tekrar dene") }
            OutlinedButton(onClick = onChooseCity, modifier = Modifier.heightIn(min = 48.dp)) { Text("Şehir seç", color = palette.ink) }
        }
    }
}
