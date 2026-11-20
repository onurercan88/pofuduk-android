package com.pofuduk.app.travel

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pofuduk.app.AppClock
import com.pofuduk.app.home.HomeViewModel
import com.pofuduk.app.home.TravelUiState
import com.pofuduk.app.ui.theme.LocalPalette
import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.engine.TravelWeatherSummaryEngine
import java.time.LocalDate
import java.time.ZoneId

/** "Seyahat Hava Durumu": destination search, dates, comparison with the current place and a packing list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TravelDialog(t: TravelUiState, viewModel: HomeViewModel, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val today = AppClock.now().atZone(ZoneId.systemDefault()).toLocalDate()
    val pickDate = { initial: LocalDate, min: LocalDate, onPicked: (LocalDate) -> Unit ->
        DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            .apply { datePicker.minDate = min.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .show()
    }
    val zone = ZoneId.systemDefault()
    fun label(d: LocalDate) = TurkishFormatting.longDate(d.atTime(12, 0).atZone(zone).toInstant(), zone)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.surfaceStrong.copy(alpha = 1f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Seyahat Hava Durumu", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kapat", color = palette.accent) }
            }

            Title("Nereye?")
            OutlinedTextField(
                value = t.query, onValueChange = viewModel::travelQuery, singleLine = true,
                label = { Text("Şehir veya ilçe") }, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
                    focusedBorderColor = palette.accent, focusedLabelColor = palette.accent, unfocusedLabelColor = palette.inkSecondary,
                ),
            )
            t.candidates.forEach { place ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { viewModel.selectDestination(place) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(place.district ?: place.name, color = palette.ink, fontSize = 17.sp)
                    place.province?.takeIf { it != place.district }?.let { Text(it, color = palette.inkSecondary, fontSize = 13.sp) }
                }
                HorizontalDivider(color = palette.inkSecondary.copy(alpha = 0.15f))
            }

            Title("Ne zaman?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate(t.departure, today) { d -> viewModel.setTravelDates(d, t.returnDate) } },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Gidiş: ${label(t.departure)}", color = palette.ink) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pickDate(t.returnDate ?: t.departure.plusDays(1), t.departure) { d -> viewModel.setTravelDates(t.departure, d) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(t.returnDate?.let { "Dönüş: ${label(it)}" } ?: "Dönüş tarihi ekle", color = palette.ink) }
                if (t.returnDate != null) {
                    TextButton(onClick = { viewModel.setTravelDates(t.departure, null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Kaldır", color = palette.accent)
                    }
                }
            }
            Text("En fazla ${TravelWeatherSummaryEngine.MAX_TRAVEL_DAYS} gün gösterilir.", color = palette.inkSecondary, fontSize = 13.sp)

            Button(
                onClick = viewModel::showTravelWeather, enabled = t.destination != null && !t.isLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse),
            ) {
                if (t.isLoading) CircularProgressIndicator(color = palette.inkInverse, modifier = Modifier.heightIn(max = 24.dp))
                else Text("Hava durumunu göster", fontWeight = FontWeight.SemiBold)
            }
            t.error?.let { Text(it, color = palette.inkSecondary) }

            t.summary?.let { s ->
                Card {
                    Text(s.briefing.fullText, color = palette.ink, fontSize = 17.sp, lineHeight = 24.sp)
                    OutlinedButton(onClick = viewModel::speakTravel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("🔊 Pofuduk anlatsın", color = palette.ink)
                    }
                }
                if (s.days.isNotEmpty()) {
                    Title("Günlük")
                    val destZone = s.destination.timeZone
                    s.days.forEach { day ->
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(TurkishFormatting.longDate(day.date, destZone), color = palette.ink, fontWeight = FontWeight.SemiBold)
                                Text(TurkishFormatting.conditionName(day.condition), color = palette.inkSecondary, fontSize = 13.sp)
                                val originName = s.origin?.name
                                if (day.originMaxTemperature != null && originName != null) {
                                    Text("$originName: en yüksek ${TurkishFormatting.shortTemperature(day.originMaxTemperature!!)}",
                                        color = palette.inkSecondary, fontSize = 13.sp)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${TurkishFormatting.shortTemperature(day.maxTemperature)} / ${TurkishFormatting.shortTemperature(day.minTemperature)}",
                                    color = palette.ink, fontWeight = FontWeight.SemiBold)
                                day.precipitationProbability?.takeIf { it >= 20 }?.let { Text("☔ %$it", color = palette.accent, fontSize = 13.sp) }
                            }
                        }
                        HorizontalDivider(color = palette.inkSecondary.copy(alpha = 0.15f))
                    }
                }
                if (s.packing.isNotEmpty()) {
                    Title("Yanına al")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.packing.forEach {
                            Text("${it.symbol} ${it.name}", color = palette.ink, fontSize = 14.sp,
                                modifier = Modifier.background(palette.surface, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            Text(
                "Yol ve trafik durumu bilgisi verilmez; yalnızca hava tahmini gösterilir. Tahminler yerel saate göredir. " +
                    "Şimdilik yalnızca Türkiye'deki yerler aranabilir.",
                color = palette.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = LocalPalette.current.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(LocalPalette.current.surface, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
