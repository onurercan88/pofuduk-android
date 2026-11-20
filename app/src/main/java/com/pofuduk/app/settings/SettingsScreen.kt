package com.pofuduk.app.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import com.pofuduk.app.home.HomeUiState
import com.pofuduk.app.home.HomeViewModel
import com.pofuduk.core.alert.MGMOfficialPage
import com.pofuduk.core.model.BriefingLength
import com.pofuduk.core.model.PersonalityStyle
import com.pofuduk.core.model.SubtitleSize
import com.pofuduk.core.model.UserPreferences
import kotlin.math.roundToInt
import com.pofuduk.app.notifications.NotificationState
import com.pofuduk.app.ui.theme.LocalPalette
import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.notification.MorningBriefingSettings
import com.pofuduk.core.notification.NotificationCategory
import java.util.Locale

private val configurable = listOf(
    NotificationCategory.RAIN_SOON, NotificationCategory.HEAVY_RAIN, NotificationCategory.SEVERE_WEATHER,
    NotificationCategory.STRONG_WIND, NotificationCategory.TEMPERATURE_CHANGE, NotificationCategory.MGM_WARNING,
)

private fun hhmm(minute: Int) = String.format(Locale.ROOT, "%02d.%02d", minute / 60, minute % 60)

/** Central settings: personalization, voice, subtitles, morning briefing, notifications, sources, privacy. On device only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    home: HomeUiState,
    state: NotificationState,
    viewModel: HomeViewModel,
    onChangePlace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val coordinator = viewModel.notifications
    val placeName = home.snapshot?.place?.displayName
    val user = home.preferences
    val palette = LocalPalette.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var confirmDelete by remember { mutableStateOf(false) }
    val voices = remember(home.speechAvailable) { viewModel.availableVoices() }
    // What to do once the notification permission prompt (Android 13+) is answered.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    val withPermission = { action: () -> Unit ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.permissionGranted) {
            afterPermission = action
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
    val openNotificationSettings = {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
        }
    }
    val pickTime = { minute: Int, onPicked: (Int) -> Unit ->
        TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, minute / 60, minute % 60, true).show()
    }
    LaunchedEffect(Unit) { coordinator.refreshPermission() }

    val prefs = state.preferences
    val morning = state.morning
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.surfaceStrong.copy(alpha = 1f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ayarlar", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kapat", color = palette.accent) }
            }

            Section("Pofuduk") {
                Text("Konuşma tarzı", color = palette.ink, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PersonalityStyle.entries.forEach { s -> Chip(s.turkishName, user.style == s) { viewModel.updatePreferences { it.copy(style = s) } } }
                }
                Text("Anlatım", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BriefingLength.entries.forEach { l -> Chip(l.turkishName, user.briefingLength == l) { viewModel.updatePreferences { it.copy(briefingLength = l) } } }
                }
                Note("Tarz yalnızca selamlamayı ve kapanışı değiştirir; hava bilgileri her zaman aynıdır. Fırtınada Pofuduk şaka yapmaz.")
            }

            Section("Ses") {
                SwitchRow("Ses", home.soundOn) { viewModel.toggleSound() }
                if (voices.isEmpty()) {
                    Note("Türkçe ses yüklü değil. Android ayarlarında Metin okuma (TTS) bölümünden Türkçe sesi indirebilirsin. O zamana kadar altyazılar gösterilir.")
                } else {
                    Text("Türkçe ses", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Otomatik (en iyi)", user.voiceName == null) { viewModel.updatePreferences { it.copy(voiceName = null) } }
                        voices.forEach { v -> Chip(v.label, user.voiceName == v.name) { viewModel.updatePreferences { it.copy(voiceName = v.name) } } }
                    }
                }
                Text("Konuşma hızı: ${rateName(user.clampedSpeechRate)}", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = user.clampedSpeechRate.toFloat(),
                    onValueChange = { v -> viewModel.updatePreferences { it.copy(speechRateMultiplier = (v * 20).roundToInt() / 20.0) } },
                    valueRange = UserPreferences.SPEECH_RATE_MIN.toFloat()..UserPreferences.SPEECH_RATE_MAX.toFloat(),
                    steps = 8,
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent),
                    modifier = Modifier.semantics { contentDescription = "Konuşma hızı" },
                )
                OutlinedButton(
                    onClick = viewModel::previewVoice, enabled = home.soundOn && home.speechAvailable == true,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Sesi dene", color = palette.ink) }
            }

            Section("Altyazılar") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubtitleSize.entries.forEach { s -> Chip(s.turkishName, user.subtitleSize == s) { viewModel.updatePreferences { it.copy(subtitleSize = s) } } }
                }
                Text("Pofuduk böyle konuşur.", color = palette.ink, fontSize = subtitleFontSize(user.subtitleSize), lineHeight = subtitleFontSize(user.subtitleSize) * 1.35f)
                Note("Altyazılar her zaman açıktır ve Android yazı boyutu ayarıyla birlikte büyür.")
            }

            Section("Konum") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(placeName ?: "Seçilmedi", color = palette.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = onChangePlace, modifier = Modifier.heightIn(min = 48.dp)) { Text("Değiştir", color = palette.accent) }
                }
            }

            Section("Pofuduk Günaydın") {
                SwitchRow("Pofuduk Günaydın", morning.isEnabled) { on -> coordinator.updateMorning { it.copy(isEnabled = on) } }
                Note("Seçtiğin saatten öğlene kadar uygulamayı ilk açtığında Pofuduk günün havasını anlatır.")
                if (morning.isEnabled) {
                    Text("Saat", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MorningBriefingSettings.presets.forEach { m ->
                            Chip(hhmm(m), morning.minuteOfDay == m) { coordinator.updateMorning { it.copy(minuteOfDay = m) } }
                        }
                        val custom = morning.minuteOfDay !in MorningBriefingSettings.presets
                        Chip(if (custom) "Özel: ${hhmm(morning.minuteOfDay)}" else "Özel…", custom) {
                            pickTime(morning.minuteOfDay) { m -> coordinator.updateMorning { it.copy(minuteOfDay = m) } }
                        }
                    }
                    SwitchRow("Otomatik konuşsun", morning.speaksAutomatically) { on -> coordinator.updateMorning { it.copy(speaksAutomatically = on) } }
                    SwitchRow("Bildirim olarak da gönder", morning.sendsNotification) { on ->
                        if (on) withPermission { coordinator.setMorningNotification(true) } else coordinator.setMorningNotification(false)
                    }
                    Note(
                        "Otomatik konuşma ya da ses kapalıysa günaydın mesajı yazı olarak gösterilir. Bildirim seçtiğin saatte gelir, " +
                            "sessiz saatlerden etkilenmez ve o anki en son tahmine göre hazırlanır.",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Konum: ${placeName ?: "Henüz seçilmedi"}", color = palette.ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = onChangePlace, modifier = Modifier.heightIn(min = 48.dp)) { Text("Değiştir", color = palette.accent) }
                    }
                }
            }

            Section("Hava bildirimleri") {
                SwitchRow("Hava bildirimleri", prefs.isEnabled) { on ->
                    if (on) withPermission { coordinator.setEnabled(true) } else coordinator.setEnabled(false)
                }
                if (!state.permissionGranted && state.askedBefore) {
                    Note("Bildirim izni kapalı. Açmak için Android ayarlarını kullanabilirsin.")
                    OutlinedButton(onClick = openNotificationSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Bildirim ayarlarını aç", color = palette.ink)
                    }
                }
                Note("Pofuduk yalnızca senin açtığın bildirim türlerini gönderir.")
                if (prefs.isEnabled) {
                    configurable.forEach { c ->
                        SwitchRow(c.turkishName, c in prefs.enabledCategories) { on ->
                            coordinator.update { it.copy(enabledCategories = if (on) it.enabledCategories + c else it.enabledCategories - c) }
                        }
                    }
                    Text("Yağmurdan ne kadar önce", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60).forEach { m ->
                            Chip("$m dakika", prefs.rainLeadMinutes == m) { coordinator.update { it.copy(rainLeadMinutes = m) } }
                        }
                    }
                    Text("Yağış olasılığı en az", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(40, 50, 60, 70, 80, 90).forEach { p ->
                            Chip("%$p", prefs.rainProbabilityThreshold == p) { coordinator.update { it.copy(rainProbabilityThreshold = p) } }
                        }
                    }
                    SwitchRow("Sessiz saatler", prefs.quietHours.enabled) { on -> coordinator.update { it.copy(quietHours = it.quietHours.copy(enabled = on)) } }
                    if (prefs.quietHours.enabled) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Başlangıç ${hhmm(prefs.quietHours.startMinute)}", false) {
                                pickTime(prefs.quietHours.startMinute) { m -> coordinator.update { it.copy(quietHours = it.quietHours.copy(startMinute = m)) } }
                            }
                            Chip("Bitiş ${hhmm(prefs.quietHours.endMinute)}", false) {
                                pickTime(prefs.quietHours.endMinute) { m -> coordinator.update { it.copy(quietHours = it.quietHours.copy(endMinute = m)) } }
                            }
                        }
                    }
                    Note("Sessiz saatlerde yalnızca turuncu ve kırmızı seviyedeki resmi MGM uyarıları gönderilir.")
                    if (state.lastPlanned.isNotEmpty()) {
                        Text("Planlanan bildirimler", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        state.lastPlanned.forEach { n ->
                            Text("${n.title} · ${TurkishFormatting.clockTime(n.fireDate, java.time.ZoneId.systemDefault())}", color = palette.inkSecondary)
                        }
                    }
                }
            }

            Section("Nasıl çalışır?") {
                Note(
                    "Bildirimler cihazındaki en son hava tahminine göre planlanır. Android, uygulamanın arka planda ne zaman " +
                        "güncelleneceğine kendisi karar verir; bu yüzden bildirim zamanı birkaç dakika kayabilir. Tahmin eskiyse ya da " +
                        "bildirim çok gecikirse gönderilmez. Arka planda konum istenmez: seçtiğin şehir ya da son konumun (~1 km) kullanılır.",
                )
            }

            Section("Birimler ve dil") {
                Note("Sıcaklık: Santigrat (°C) · Rüzgâr: km/sa · Dil: Türkçe")
            }

            Section("Veri kaynakları") {
                LinkRow("Hava verisi: Open-Meteo.com (CC BY 4.0)") { uri.openUri("https://open-meteo.com/") }
                LinkRow("Resmi uyarılar: MGM (mgm.gov.tr)") { uri.openUri(MGMOfficialPage.WARNINGS) }
                Note("Pofuduk'un hava tahminleri resmi uyarı değildir.")
            }

            Section("Gizlilik") {
                Note(
                    "Konumun yalnızca uygulama açıkken ve sadece hava durumu için kullanılır; takip edilmez, satılmaz, paylaşılmaz. " +
                        "Mikrofon yalnızca sen düğmeye bastığında açılır. Tüm tercihler bu cihazda saklanır; hesap gerekmez.",
                )
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Verilerimi sil", color = Color(0xFFD32F2F))
                }
            }

            Section("Hakkında") {
                Note("Sürüm ${versionName(context)} · Pofuduk, hava durumunu Türkçe anlatan sevimli bir bulut arkadaş.")
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Tüm verilerin silinsin mi?") },
            text = {
                Text(
                    "Kayıtlı konum, önbellekteki tahminler, bildirim ve günaydın ayarları, kişiselleştirme ve sohbet geçmişi silinir. " +
                        "Bekleyen bildirimler iptal edilir.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteAllData() }) { Text("Verilerimi sil", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
        )
    }
}

private fun rateName(rate: Double): String = when {
    rate < 0.95 -> "yavaş"
    rate > 1.05 -> "hızlı"
    else -> "normal"
}

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "-"

/** Subtitle font size per setting (also scales with the system font size, since it is in sp). */
fun subtitleFontSize(size: SubtitleSize) = when (size) {
    SubtitleSize.STANDARD -> 20.sp
    SubtitleSize.LARGE -> 24.sp
    SubtitleSize.EXTRA_LARGE -> 28.sp
    SubtitleSize.HUGE -> 34.sp
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Text(
        text, color = LocalPalette.current.accent, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.semantics { heading() })
        content()
        HorizontalDivider(color = palette.inkSecondary.copy(alpha = 0.15f), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = palette.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.accent),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LocalPalette.current.inkSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        modifier = Modifier.heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(
            labelColor = palette.ink, selectedLabelColor = palette.inkInverse, selectedContainerColor = palette.accent,
        ),
    )
}
