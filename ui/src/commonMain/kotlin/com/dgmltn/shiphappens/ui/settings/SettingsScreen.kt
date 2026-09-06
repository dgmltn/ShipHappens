package com.dgmltn.shiphappens.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgmltn.shiphappens.data.settings.RefreshFrequency
import com.dgmltn.shiphappens.domain.designTime
import kotlinx.datetime.LocalTime
import com.dgmltn.shiphappens.ui.components.ToastOverlay
import com.dgmltn.shiphappens.design.*
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogin: (String) -> Unit = {}, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    val permission = rememberNotificationPermissionController()
    SettingsContent(
        state = s,
        onBack = onBack,
        onToggle = vm::onToggle,
        onAutoImport = vm::onAutoImport,
        onFrequency = vm::onFrequency,
        onSignIn = onOpenLogin,
        onSignOut = { vm.onSignOut(it) },
        onDailyUpdate = { enabled ->
            if (enabled) {
                permission.request { granted -> vm.onDailyUpdateEnabled(true, granted) }
            } else {
                vm.onDailyUpdateEnabled(false, permissionGranted = true)
            }
        },
        onDailyUpdateTime = { vm.onDailyUpdateTime(it) },
        // Notifications can be turned off in system settings long after the toggle was flipped
        // on; surfacing it here keeps the card honest about whether it can actually deliver.
        permissionRevoked = s.dailyUpdateEnabled && !permission.isGranted,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit = {},
    onToggle: (String) -> Unit = {},
    onAutoImport: (Boolean) -> Unit = {},
    onFrequency: (RefreshFrequency) -> Unit = {},
    onSignIn: (String) -> Unit = {},
    onSignOut: (String) -> Unit = {},
    onDailyUpdate: (Boolean) -> Unit = {},
    onDailyUpdateTime: (LocalTime) -> Unit = {},
    permissionRevoked: Boolean = false,
) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(ShipColors.card)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("‹", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink,
                    modifier = Modifier.clip(RoundedCornerShape(13.dp)).border(1.dp, ShipColors.hairlineStrong, RoundedCornerShape(13.dp))
                        .clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 6.dp))
                Text("Settings", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = ShipColors.ink, fontFamily = hankenFamily())
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 34.dp)) {
                SectionLabel("Direct carrier APIs")
                state.carriers.forEach { SourceCard(it, onToggle, onSignIn, onSignOut) }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Sync")
                SyncCard(state.autoImport, state.frequency, onAutoImport, onFrequency)
                DailyUpdateCard(
                    enabled = state.dailyUpdateEnabled,
                    time = state.dailyUpdateTime,
                    blocked = state.dailyUpdateBlocked || permissionRevoked,
                    is24Hour = state.uses24HourClock,
                    onEnabled = onDailyUpdate,
                    onTime = onDailyUpdateTime,
                )
                Text(
                    "Signing in to a carrier is stored on this device only and used to fetch live tracking status from that carrier's website.",
                    color = ShipColors.faint, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 16.dp),
                )
            }
        }
        ToastOverlay(
            state.toast,
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 28.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = ShipColors.faint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(ShipColors.card)
            .border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun SourceCard(
    card: SourceCardUi,
    onToggle: (String) -> Unit,
    onSignIn: (String) -> Unit,
    onSignOut: (String) -> Unit,
) {
    val accent = colorFromHex(card.accentHex)
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent),
                contentAlignment = Alignment.Center,
            ) { Text("📦", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
            Column(Modifier.weight(1f)) {
                Text(card.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = ShipColors.ink, fontFamily = hankenFamily())
                Text(card.statusText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colorFromHex(card.statusColorHex))
            }
            Switch(
                checked = card.enabled, onCheckedChange = { onToggle(card.id) },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, uncheckedTrackColor = ShipColors.toggleOff),
            )
        }
        if (card.webCapable && card.enabled) {
            TextButton(onClick = { if (card.signedIn) onSignOut(card.id) else onSignIn(card.id) }) {
                Text(if (card.signedIn) "Sign out of ${card.name}" else "Sign in to ${card.name}", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SyncCard(
    autoImport: Boolean,
    frequency: RefreshFrequency,
    onAutoImport: (Boolean) -> Unit,
    onFrequency: (RefreshFrequency) -> Unit,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-import from clipboard", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
                Text("Detect tracking numbers when you open the app", fontSize = 12.sp, color = ShipColors.muted)
            }
            Switch(checked = autoImport, onCheckedChange = onAutoImport,
                colors = SwitchDefaults.colors(checkedTrackColor = ShipColors.ink, uncheckedTrackColor = ShipColors.toggleOff))
        }
        Spacer(Modifier.height(15.dp))
        Text("Refresh frequency", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RefreshFrequency.FIFTEEN_MIN to "15 min", RefreshFrequency.ONE_HOUR to "1 hour", RefreshFrequency.MANUAL to "Manual").forEach { (f, label) ->
                val active = frequency == f
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else Color(0xFF6B665C),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                        .background(if (active) ShipColors.ink else ShipColors.card)
                        .border(1.dp, if (active) ShipColors.ink else ShipColors.hairline, RoundedCornerShape(11.dp))
                        .clickable { onFrequency(f) }.padding(vertical = 9.dp))
            }
        }
    }
}

@Composable
private fun DailyUpdateCard(
    enabled: Boolean,
    time: LocalTime,
    blocked: Boolean,
    is24Hour: Boolean,
    onEnabled: (Boolean) -> Unit,
    onTime: (LocalTime) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily update", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
                Text(
                    "Check undelivered packages every morning and notify you when something changes",
                    fontSize = 12.sp, color = ShipColors.muted,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = ShipColors.ink, uncheckedTrackColor = ShipColors.toggleOff))
        }
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable { picking = true }
                    .border(1.dp, ShipColors.hairline, RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Check at", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink,
                    modifier = Modifier.weight(1f))
                Text(time.designTime(is24Hour), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
            }
        }
        if (blocked) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Notifications are turned off for Ship Happens. Turn them on in system settings to get daily updates.",
                fontSize = 12.sp, lineHeight = 17.sp, color = ShipColors.faint,
            )
        }
    }
    if (picking) {
        DailyTimePickerDialog(time, is24Hour, onDismiss = { picking = false }, onConfirm = { onTime(it); picking = false })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DailyTimePickerDialog(
    initial: LocalTime,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState = androidx.compose.material3.rememberTimePickerState(
        initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime(pickerState.hour, pickerState.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { androidx.compose.material3.TimePicker(state = pickerState) },
    )
}

@Preview
@Composable
private fun Preview_SettingsContent_SourcesDisabled() {
    ShipTheme {
        SettingsContent(
            SettingsUiState(
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                    ),
                    SourceCardUi(
                        id = "usps", name = "USPS", accentHex = "#1E3A8F", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                    ),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_SettingsContent_SourcesEnabled() {
    ShipTheme {
        SettingsContent(
            SettingsUiState(
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = true,
                        statusText = "Connected · syncing", statusColorHex = "#1F7A4D",
                    ),
                    SourceCardUi(
                        id = "amazon", name = "Amazon", accentHex = "#995C00", enabled = true,
                        statusText = "Coming soon", statusColorHex = "#A8A296",
                    ),
                ),
                autoImport = true,
                frequency = RefreshFrequency.ONE_HOUR,
                dailyUpdateEnabled = true,
                dailyUpdateTime = LocalTime(8, 0),
            ),
        )
    }
}
