package com.pofuduk.app.home

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pofuduk.app.character.CharacterView
import com.pofuduk.app.ui.theme.LocalPalette
import com.pofuduk.app.ui.theme.PofudukTheme
import com.pofuduk.app.ui.theme.backgroundGradient
import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.mock.MockWeatherData.Scenario
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.speech.PlaybackStatus
import com.pofuduk.core.util.capitalizedFirstTurkish
import com.pofuduk.core.util.startOfHour
import java.time.Instant

private val Scenario.turkishName: String
    get() = when (this) {
        Scenario.SUNNY -> "Güneşli"
        Scenario.RAINY_AFTERNOON -> "Öğleden sonra yağmur"
        Scenario.STORMY -> "Fırtına"
        Scenario.SNOWY -> "Kar"
        Scenario.CLOUDY -> "Bulutlu"
        Scenario.WINDY -> "Rüzgârlı"
    }

@Composable
fun HomeScreen(state: HomeUiState, viewModel: HomeViewModel) {
    val theme = state.analysis.theme
    PofudukTheme(forceDark = theme == WeatherTheme.NIGHT) {
        val palette = LocalPalette.current
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(backgroundGradient(theme, palette.isDark))),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(state, onToggleSound = viewModel::toggleSound)
                CharacterView(
                    state.appearance,
                    Modifier.clickable(onClickLabel = "Pofuduk'a dokun") { viewModel.onCharacterTap() },
                )
                SubtitleCard(state)
                SpeechControls(state, onPlay = viewModel::playBriefing, onStop = viewModel::stop)
                AskBar(state.lastQuestion, onAsk = viewModel::ask)
                NowCard(state)
                if (state.advice.isNotEmpty()) AdviceCard(state.advice)
                state.clothing?.let { ClothingCard(it.items.map { i -> "${i.symbol} ${i.name}" }, it.summary) }
                HourlyCard(state)
                ScenarioPicker(state.scenario, viewModel::selectScenario)
                Text(
                    "Kaynak: örnek veri — gerçek bir tahmin değil. Canlı tahmin (Open-Meteo) sonraki fazda.",
                    color = palette.inkSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(state: HomeUiState, onToggleSound: () -> Unit) {
    val palette = LocalPalette.current
    val zone = state.snapshot.place.timeZone
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                state.snapshot.place.displayName, color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(TurkishFormatting.longDate(Instant.now(), zone), color = palette.inkSecondary, fontSize = 14.sp)
            Text(
                "Örnek veri",
                color = palette.inkInverse, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(palette.accent, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        OutlinedButton(
            onClick = onToggleSound,
            modifier = Modifier.heightIn(min = 48.dp),
            border = BorderStroke(1.dp, palette.accent),
        ) {
            Text(if (state.soundOn) "🔊 Ses açık" else "🔇 Ses kapalı", color = palette.ink)
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.surfaceStroke),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** The sentence being spoken, with the current word highlighted (subtitles = the spoken strings). */
@Composable
private fun SubtitleCard(state: HomeUiState) {
    val palette = LocalPalette.current
    val track = state.track
    var showAll by rememberSaveable { mutableStateOf(false) }
    Card {
        state.lastQuestion?.let { Text("Sen: “$it”", color = palette.inkSecondary, fontSize = 14.sp) }
        val sentence = track.currentSentence ?: track.sentences.firstOrNull().orEmpty()
        val range = track.wordRange
        val text = buildAnnotatedString {
            if (range != null && range.location + range.length <= sentence.length) {
                append(sentence.substring(0, range.location))
                withStyle(SpanStyle(background = palette.highlight.copy(alpha = 0.6f), color = palette.ink)) {
                    append(sentence.substring(range.location, range.location + range.length))
                }
                append(sentence.substring(range.location + range.length))
            } else {
                append(sentence)
            }
        }
        Text(
            text, color = palette.ink, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (track.sentences.size > 1) {
            Text(
                if (showAll) "Metni gizle" else "Metnin tamamı",
                color = palette.accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { showAll = !showAll }
                    .padding(vertical = 12.dp),
            )
            if (showAll) Text(track.transcript, color = palette.inkSecondary, fontSize = 16.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun SpeechControls(state: HomeUiState, onPlay: () -> Unit, onStop: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val speaking = state.isSpeaking || state.track.status == PlaybackStatus.SPEAKING
        Button(
            onClick = if (speaking) onStop else onPlay,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse),
        ) { Text(if (speaking) "⏹ Durdur" else "▶ Havayı anlat", fontSize = 16.sp) }
    }
    if (state.speechAvailable == false) {
        Card {
            Text("Bu cihazda Türkçe ses bulunamadı; söylediklerimi yazı olarak gösteriyorum.", color = palette.ink)
            Text(
                "Ses ayarlarını aç",
                color = palette.accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable {
                        try {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AskBar(lastQuestion: String?, onAsk: (String) -> Unit) {
    val palette = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        onAsk(text)
        text = ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(if (lastQuestion == null) "Örn. “Akşam yağmur var mı?”" else "Başka bir soru…") },
            label = { Text("Pofuduk'a sor") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
                focusedContainerColor = palette.surfaceStrong, unfocusedContainerColor = palette.surface,
                focusedBorderColor = palette.accent, focusedLabelColor = palette.accent,
                unfocusedLabelColor = palette.inkSecondary, unfocusedPlaceholderColor = palette.inkSecondary,
                focusedPlaceholderColor = palette.inkSecondary,
            ),
        )
        Button(
            onClick = submit,
            enabled = text.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse),
        ) { Text("Sor") }
    }
}

@Composable
private fun NowCard(state: HomeUiState) {
    val palette = LocalPalette.current
    val c = state.snapshot.current
    val a = state.analysis
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(TurkishFormatting.shortTemperature(c.temperature), color = palette.ink, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(TurkishFormatting.conditionName(c.condition, c.isDay).capitalizedFirstTurkish(), color = palette.ink, fontSize = 18.sp)
                Text("Hissedilen ${TurkishFormatting.shortTemperature(c.feelsLike)}", color = palette.inkSecondary)
                if (a.minTemperature != null && a.maxTemperature != null) {
                    Text(
                        "En düşük ${TurkishFormatting.shortTemperature(a.minTemperature!!)} · En yüksek ${TurkishFormatting.shortTemperature(a.maxTemperature!!)}",
                        color = palette.inkSecondary,
                    )
                }
            }
        }
        Text(
            "Nem %${c.humidity} · Rüzgâr ${c.windSpeedKmh.toInt()} km/sa" + (c.uvIndex?.let { " · UV ${it.toInt()}" } ?: ""),
            color = palette.inkSecondary, fontSize = 14.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdviceCard(advice: List<String>) {
    val palette = LocalPalette.current
    Card {
        Text("Pofuduk'un önerileri", color = palette.ink, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            advice.forEach {
                Text(
                    it, color = palette.ink, fontSize = 14.sp,
                    modifier = Modifier
                        .background(palette.highlight.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClothingCard(items: List<String>, summary: String) {
    val palette = LocalPalette.current
    Card {
        Text("Ne giysem?", color = palette.ink, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(summary, color = palette.ink, fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach {
                Text(
                    it, color = palette.ink, fontSize = 14.sp,
                    modifier = Modifier
                        .background(palette.surfaceStrong, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HourlyCard(state: HomeUiState) {
    val palette = LocalPalette.current
    val zone = state.snapshot.place.timeZone
    val from = Instant.now().startOfHour(zone)
    val hours = state.snapshot.hourly.filter { !it.time.isBefore(from) }.take(12)
    if (hours.isEmpty()) return
    Card {
        Text("Saatlik", color = palette.ink, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            hours.forEach { h ->
                val label = TurkishFormatting.clockTime(h.time, zone)
                val rain = h.precipitationProbability?.takeIf { it >= 30 }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    Text(label, color = palette.inkSecondary, fontSize = 13.sp)
                    Text(TurkishFormatting.shortTemperature(h.temperature), color = palette.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(rain?.let { "☔ %$it" } ?: " ", color = palette.accent, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioPicker(selected: Scenario, onSelect: (Scenario) -> Unit) {
    val palette = LocalPalette.current
    Card {
        Text("Örnek hava (demo)", color = palette.ink, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scenario.entries.forEach { s ->
                FilterChip(
                    selected = s == selected,
                    onClick = { onSelect(s) },
                    label = { Text(s.turkishName) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = palette.ink, selectedLabelColor = palette.inkInverse, selectedContainerColor = palette.accent,
                    ),
                )
            }
        }
    }
}
