package com.shiphappens.ui.settings

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import com.shiphappens.data.settings.RefreshFrequency
import com.shiphappens.ui.components.ToastOverlay
import com.shiphappens.design.*
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    SettingsContent(
        state = s,
        onBack = onBack,
        onToggle = vm::onToggle,
        onField = vm::onField,
        onTest = vm::onTest,
        onAutoImport = vm::onAutoImport,
        onFrequency = vm::onFrequency,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit = {},
    onToggle: (String) -> Unit = {},
    onField: (String, String, String) -> Unit = { _, _, _ -> },
    onTest: (String) -> Unit = {},
    onAutoImport: (Boolean) -> Unit = {},
    onFrequency: (RefreshFrequency) -> Unit = {},
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
                SectionLabel("Universal API")
                state.universal.forEach { SourceCard(it, onToggle, onField, onTest) }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Direct carrier APIs")
                state.carriers.forEach { SourceCard(it, onToggle, onField, onTest) }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Sync")
                SyncCard(state.autoImport, state.frequency, onAutoImport, onFrequency)
                Text(
                    "Keys are stored on this device only and used to fetch live tracking status directly from each carrier.",
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
    onField: (String, String, String) -> Unit,
    onTest: (String) -> Unit,
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
        card.fields.forEach { f ->
            Spacer(Modifier.height(11.dp))
            Column {
                Text(f.label.uppercase(), color = ShipColors.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                Spacer(Modifier.height(5.dp))
                BasicTextField(
                    value = f.value, onValueChange = { onField(card.id, f.key, it) }, singleLine = true,
                    visualTransformation = if (f.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ShipColors.ink, fontFamily = monoFamily(), fontSize = 13.sp),
                    decorationBox = { inner ->
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(ShipColors.cardAlt)
                                .border(1.dp, ShipColors.hairline, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            if (f.value.isEmpty()) Text(f.placeholder, color = ShipColors.faint, fontSize = 13.sp, fontFamily = monoFamily())
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        card.endpointText?.let { endpoint ->
            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(endpoint, color = ShipColors.faint, fontSize = 11.sp, fontFamily = monoFamily())
                TextButton(onClick = { onTest(card.id) }) {
                    Text("Test connection", color = ShipColors.ink,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF1EFE9))
                            .padding(horizontal = 13.dp, vertical = 8.dp))
                }
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

@Preview
@Composable
private fun Preview_SettingsContent_SourcesDisabled() {
    ShipTheme {
        SettingsContent(
            SettingsUiState(
                universal = listOf(
                    SourceCardUi(
                        id = "demo", name = "Demo data", accentHex = "#17150F", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                        fields = emptyList(), endpointText = null,
                    ),
                ),
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                        fields = emptyList(), endpointText = "Production endpoint",
                    ),
                    SourceCardUi(
                        id = "usps", name = "USPS", accentHex = "#1E3A8F", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                        fields = emptyList(), endpointText = "Production endpoint",
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
                universal = listOf(
                    SourceCardUi(
                        id = "demo", name = "Demo data", accentHex = "#17150F", enabled = true,
                        statusText = "Connected · demo parcels", statusColorHex = "#1F7A4D",
                        fields = emptyList(), endpointText = null,
                    ),
                ),
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = true,
                        statusText = "Enabled · add your credentials", statusColorHex = "#C2410C",
                        fields = listOf(FieldUi("clientId", "Client ID", "Your UPS client ID", isSecret = false, value = "")),
                        endpointText = "Production endpoint",
                    ),
                    SourceCardUi(
                        id = "fedex", name = "FedEx", accentHex = "#5A1B9A", enabled = true,
                        statusText = "Direct API coming soon", statusColorHex = "#A8A296",
                        fields = emptyList(), endpointText = "Production endpoint",
                    ),
                ),
                autoImport = true,
                frequency = RefreshFrequency.ONE_HOUR,
            ),
        )
    }
}
