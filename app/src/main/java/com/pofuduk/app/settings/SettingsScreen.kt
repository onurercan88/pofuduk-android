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
import com.pofuduk.app.notifications.NotificationCoordinator
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

/** Pofuduk Günaydın + weather notification settings. Honest about Android's background limits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(state: NotificationState, coordinator: NotificationCoordinator, placeName: String?, onChangePlace: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
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
        }
    }
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
