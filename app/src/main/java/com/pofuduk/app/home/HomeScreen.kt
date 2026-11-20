package com.pofuduk.app.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pofuduk.app.character.CharacterView
import com.pofuduk.app.settings.SettingsDialog
import com.pofuduk.app.ui.theme.LocalPalette
import com.pofuduk.app.ui.theme.PofudukTheme
import com.pofuduk.app.ui.theme.backgroundGradient
import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.mock.MockWeatherData.Scenario
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.speech.PlaybackStatus
import com.pofuduk.core.util.capitalizedFirstTurkish
import com.pofuduk.core.util.startOfHour
import java.time.Instant
import java.time.ZoneId

private val Scenario.turkishName: String
    get() = when (this) {
        Scenario.SUNNY -> "Güneşli"
        Scenario.RAINY_AFTERNOON -> "Öğleden sonra yağmur"
        Scenario.STORMY -> "Fırtına"
        Scenario.SNOWY -> "Kar"
        Scenario.CLOUDY -> "Bulutlu"
        Scenario.WINDY -> "Rüzgârlı"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, viewModel: HomeViewModel) {
    val context = LocalContext.current
    // The system prompt is shown only from Pofuduk's own explanation (or the picker's "Konumumu kullan").
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onPermissionResult() }
    val askLocation = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
    // Microphone permission only when the user taps the mic (and never for typed questions).
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startListening() else viewModel.microphoneDenied()
    }
    val onMic = { viewModel.toggleListening { micPermission.launch(Manifest.permission.RECORD_AUDIO) } }
    val openSettings = { servicesOff: Boolean ->
        val intent = if (servicesOff) {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    val theme = state.theme
    PofudukTheme(forceDark = theme == WeatherTheme.NIGHT) {
        val palette = LocalPalette.current
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
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
                Header(state, onToggleSound = viewModel::toggleSound, onChangePlace = viewModel::openLocationPicker, onSettings = viewModel::openSettings)
                CharacterView(
                    state.appearance,
                    Modifier.clickable(onClickLabel = "Pofuduk'a dokun", role = Role.Button) { viewModel.onCharacterTap() },
                )
                SubtitleCard(state)
                when (val load = state.loadState) {
                    is LoadState.NeedsLocation -> LocationPromptCard(load.prompt, askLocation, openSettings, viewModel::openLocationPicker)
                    is LoadState.Failed -> FailedCard(load.reason, viewModel::refresh, viewModel::openLocationPicker)
                    LoadState.Loading -> Text("Hava durumuna bakıyorum…", color = palette.inkSecondary)
                    LoadState.Loaded -> {
                        SpeechControls(state, onPlay = viewModel::playBriefing, onStop = viewModel::stop)
                        AskBar(state, onInput = viewModel::onInputChange, onSend = viewModel::send, onMic = onMic, onSuggestion = viewModel::ask)
                        NowCard(state)
                        if (state.advice.isNotEmpty()) AdviceCard(state.advice)
                        state.clothing?.let { ClothingCard(it.items.map { i -> "${i.symbol} ${i.name}" }, it.summary) }
                        HourlyCard(state)
                    }
                }
                state.demoScenario?.let { ScenarioPicker(it, viewModel::setDemo) }
                Footer(state, onDemo = viewModel::setDemo)
            }
        }
        if (state.showSettings) {
            val notificationState by viewModel.notifications.state.collectAsStateWithLifecycle()
            SettingsDialog(
                state = notificationState, coordinator = viewModel.notifications,
                placeName = state.snapshot?.place?.displayName,
                onChangePlace = { viewModel.closeSettings(); viewModel.openLocationPicker() },
                onDismiss = viewModel::closeSettings,
            )
        }
        if (state.showLocationPicker) {
            LocationPickerDialog(
                results = state.searchResults,
                onSearch = viewModel::search,
                onSelect = viewModel::selectPlace,
                onUseDevice = askLocation,
                onDismiss = viewModel::closeLocationPicker,
            )
        }
    }
}

@Composable
private fun Header(state: HomeUiState, onToggleSound: () -> Unit, onChangePlace: () -> Unit, onSettings: () -> Unit) {
    val palette = LocalPalette.current
    val place = state.snapshot?.place
    val zone = place?.timeZone ?: ZoneId.systemDefault()
    val canChange = state.demoScenario == null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .then(if (canChange) Modifier.clickable(onClickLabel = "Konumu değiştir", onClick = onChangePlace) else Modifier),
        ) {
            Text(
                (place?.displayName ?: "Pofuduk") + if (canChange) " ▾" else "",
                color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(TurkishFormatting.longDate(Instant.now(), zone), color = palette.inkSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (state.demoScenario != null) Badge("Örnek veri")
                if (state.isStale) Badge("Güncel olmayabilir")
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onToggleSound, modifier = Modifier.heightIn(min = 48.dp), border = BorderStroke(1.dp, palette.accent)) {
                Text(if (state.soundOn) "🔊 Ses açık" else "🔇 Ses kapalı", color = palette.ink)
            }
            TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("⚙️ Ayarlar", color = palette.ink) }
        }
    }
}

@Composable
private fun Badge(text: String) {
    val palette = LocalPalette.current
    Text(
        text, color = palette.inkInverse, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(palette.accent, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun HomeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
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
    if (track.sentences.isEmpty()) return
    HomeCard {
        state.lastQuestion?.let { Text("Sen: “$it”", color = palette.inkSecondary, fontSize = 14.sp) }
        val sentence = track.currentSentence ?: track.sentences.first()
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
    val speaking = state.isSpeaking || state.track.status == PlaybackStatus.SPEAKING
    Button(
        onClick = if (speaking) onStop else onPlay,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse),
    ) { Text(if (speaking) "⏹ Durdur" else "▶ Havayı anlat", fontSize = 16.sp) }
    if (state.speechAvailable == false) {
        HomeCard {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskBar(state: HomeUiState, onInput: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit, onSuggestion: (String) -> Unit) {
    val palette = LocalPalette.current
    val text = state.inputText
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.microphoneMessage?.let {
            Text(it, color = palette.inkSecondary, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (text.isEmpty() && !state.isListening) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SUGGESTIONS.forEach { s ->
                    Text(
                        s, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(palette.surface, RoundedCornerShape(50))
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { onSuggestion(s) }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                placeholder = { Text(if (state.isListening) "Dinliyorum…" else "Örn. “Akşam yağmur var mı?”") },
                label = { Text(if (state.isListening) "Dinliyorum…" else "Pofuduk'a sor") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
                    focusedContainerColor = palette.surfaceStrong, unfocusedContainerColor = palette.surface,
                    focusedBorderColor = palette.accent, unfocusedBorderColor = if (state.isListening) palette.accent else palette.inkSecondary,
                    focusedLabelColor = palette.accent,
                    unfocusedLabelColor = palette.inkSecondary, unfocusedPlaceholderColor = palette.inkSecondary,
                    focusedPlaceholderColor = palette.inkSecondary,
                ),
            )
            val micColors = if (state.isListening) {
                ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse)
            } else {
                ButtonDefaults.buttonColors(containerColor = palette.surfaceStrong, contentColor = palette.ink)
            }
            Button(
                onClick = onMic, colors = micColors,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = if (state.isListening) "Dinlemeyi bitir" else "Sesli sor"
                },
            ) { Text(if (state.isListening) "⏹" else "🎤", fontSize = 18.sp) }
            if (text.isNotBlank() && !state.isListening) {
                Button(
                    onClick = onSend,
                    enabled = !state.isThinking,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.inkInverse),
                ) { Text("Sor") }
            }
        }
    }
}

private val SUGGESTIONS = listOf("Yarın hava nasıl?", "Şemsiye gerekir mi?", "Bugün ne giysem?", "Akşam yağmur var mı?")

@Composable
private fun NowCard(state: HomeUiState) {
    val palette = LocalPalette.current
    val c = state.snapshot?.current ?: return
    val a = state.analysis ?: return
    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(TurkishFormatting.shortTemperature(c.temperature), color = palette.ink, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(TurkishFormatting.conditionName(c.condition, c.isDay).capitalizedFirstTurkish(), color = palette.ink, fontSize = 18.sp)
                Text("Hissedilen ${TurkishFormatting.shortTemperature(c.feelsLike)}", color = palette.inkSecondary)
                val lo = a.minTemperature
                val hi = a.maxTemperature
                if (lo != null && hi != null) {
                    Text("En düşük ${TurkishFormatting.shortTemperature(lo)} · En yüksek ${TurkishFormatting.shortTemperature(hi)}", color = palette.inkSecondary)
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
    HomeCard {
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
    HomeCard {
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
    val snapshot = state.snapshot ?: return
    val zone = snapshot.place.timeZone
    val from = Instant.now().startOfHour(zone)
    val hours = snapshot.hourly.filter { !it.time.isBefore(from) }.take(12)
    if (hours.isEmpty()) return
    HomeCard {
        Text("Saatlik", color = palette.ink, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            hours.forEach { h ->
                val rain = h.precipitationProbability?.takeIf { it >= 30 }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(TurkishFormatting.clockTime(h.time, zone), color = palette.inkSecondary, fontSize = 13.sp)
                    Text(TurkishFormatting.shortTemperature(h.temperature), color = palette.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(rain?.let { "☔ %$it" } ?: " ", color = palette.accent, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioPicker(selected: Scenario, onSelect: (Scenario?) -> Unit) {
    val palette = LocalPalette.current
    HomeCard {
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

/** Source attribution (Open-Meteo CC BY 4.0 requires a visible credit) + demo switch. */
@Composable
private fun Footer(state: HomeUiState, onDemo: (Scenario?) -> Unit) {
    val palette = LocalPalette.current
    val uri = LocalUriHandler.current
    val snapshot = state.snapshot
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 16.dp)) {
        if (snapshot != null) {
            val time = TurkishFormatting.clockTime(snapshot.retrievedAt, snapshot.place.timeZone)
            if (snapshot.source == WeatherDataSource.DEMO) {
                Text("Kaynak: örnek veri — gerçek bir tahmin değil.", color = palette.inkSecondary, fontSize = 12.sp)
            } else {
                Text("Tahmin: ${snapshot.source.attribution} · $time itibarıyla", color = palette.inkSecondary, fontSize = 12.sp)
                if (snapshot.source == WeatherDataSource.OPEN_METEO) {
                    Text(
                        "Weather data by Open-Meteo.com (CC BY 4.0)",
                        color = palette.accent, fontSize = 12.sp, textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { uri.openUri("https://open-meteo.com/") }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        }
        TextButton(onClick = { onDemo(if (state.demoScenario == null) Scenario.SUNNY else null) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.demoScenario == null) "Örnek verilerle göster" else "Canlı tahmine dön", color = palette.accent)
        }
    }
}
