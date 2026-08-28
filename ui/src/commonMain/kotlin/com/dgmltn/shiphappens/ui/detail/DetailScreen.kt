package com.dgmltn.shiphappens.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgmltn.shiphappens.design.*
import com.dgmltn.shiphappens.ui.components.DelayNote
import com.dgmltn.shiphappens.ui.StatusBarIconsForHeader
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DetailScreen(parcelId: String, onBack: () -> Unit, onOpenWeb: () -> Unit = {}) {
    val vm: DetailViewModel = koinViewModel(key = parcelId) { parametersOf(parcelId) }
    val s by vm.state.collectAsState()
    StatusBarIconsForHeader(colorFromHex(s.accentHex))
    DetailContent(s, onBack, onOpenWeb)
}

@Composable
fun DetailContent(state: DetailUiState, onBack: () -> Unit = {}, onOpenWeb: () -> Unit = {}) {
    val accent = colorFromHex(state.accentHex)

    Column(Modifier.fillMaxSize().background(ShipColors.bg)) {
        // Carrier-colored header
        Column(
            Modifier.fillMaxWidth().background(accent)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f))
                        .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.refreshing) {
                        CircularProgressIndicator(Modifier.size(12.dp), color = Color.White.copy(alpha = .9f), strokeWidth = 1.5.dp)
                    }
                    Text(state.carrierName, color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(state.name, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, fontFamily = hankenFamily())
            Spacer(Modifier.height(9.dp))
            Text(state.headline, color = Color.White.copy(alpha = .92f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 34.dp)) {
            DetailCard {
                CardLabel(state.windowLabel)
                Text(state.windowText, color = ShipColors.ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, fontFamily = hankenFamily())
            }
            // Directly under the ETA it qualifies: the date stays the headline, the delay
            // explains it, rather than one replacing the other.
            state.delayNote?.let {
                Spacer(Modifier.height(10.dp))
                DelayNote(it)
            }
            Spacer(Modifier.height(14.dp))
            // Map placeholder card (spec: decorative, with real location text)
            Box(Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFEEECE6)).border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp))) {
                Box(Modifier.align(Alignment.Center).size(20.dp).clip(CircleShape).background(accent))
                Text(state.locationText ?: "package location", color = ShipColors.muted, fontSize = 10.sp, fontFamily = monoFamily(),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .72f)).padding(horizontal = 7.dp, vertical = 3.dp))
            }
            Spacer(Modifier.height(14.dp))
            DetailCard {
                CardLabel("Tracking history")
                Spacer(Modifier.height(8.dp))
                state.timeline.forEachIndexed { i, step ->
                    // IntrinsicSize.Min + weight(1f) stretches the connector to exactly meet the
                    // next dot, however tall the text column gets — no fixed-height gaps.
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp).fillMaxHeight()) {
                            val dotColor = when (step.state) {
                                StepState.DONE -> accent
                                StepState.CURRENT -> Color.White
                                StepState.TODO -> ShipColors.hairlineStrong
                            }
                            Box(Modifier.size(13.dp).clip(CircleShape).background(dotColor)
                                .then(if (step.state == StepState.CURRENT) Modifier.border(3.dp, accent, CircleShape) else Modifier))
                            if (i < state.timeline.lastIndex) {
                                Box(Modifier.width(2.dp).weight(1f)
                                    .background(if (step.state == StepState.TODO) ShipColors.segmentBg else accent))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(
                            if (i < state.timeline.lastIndex) Modifier.heightIn(min = 45.dp).padding(bottom = 4.dp)
                            else Modifier.padding(bottom = 4.dp)
                        ) {
                            Text(step.label, fontSize = 14.sp,
                                fontWeight = if (step.state == StepState.TODO) FontWeight.SemiBold else FontWeight.Bold,
                                color = if (step.state == StepState.TODO) Color(0xFFB4AFA5) else ShipColors.ink)
                            step.time?.let { Text(it, fontSize = 12.sp, color = ShipColors.faint) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            DetailCard {
                CardLabel("${state.carrierName} tracking number")
                // No portable ClipEntry(text) factory in CMP 1.11.1 commonMain (see ClipEntry.withPlainText,
                // iOS-only and @ExperimentalComposeUiApi) — LocalClipboardManager is deprecated but the only
                // cross-platform way to write plain text today.
                @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
                val haptics = LocalHapticFeedback.current
                Text(
                    state.trackingNumber, color = ShipColors.ink, fontFamily = monoFamily(), fontSize = 14.sp,
                    modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            clipboard.setText(AnnotatedString(state.trackingNumber))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    ),
                )
            }
            state.webCarrierName?.let { name ->
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ShipColors.card)
                        .border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenWeb).padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("More details on $name", color = ShipColors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("›", color = ShipColors.faint, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ShipColors.card)
        .border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        content = content)
}

@Composable
private fun CardLabel(text: String) {
    Text(text.uppercase(), color = ShipColors.faint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Preview
@Composable
private fun Preview_DetailContent_InTransit() {
    ShipTheme {
        DetailContent(
            DetailUiState(
                loaded = true,
                name = "Baseball cap",
                carrierName = "USPS",
                accentHex = "#1E3A8F",
                headline = "Arrives in 2 days",
                windowLabel = "Estimated delivery",
                windowText = "Sun, Jul 13 · by 8:00 PM",
                locationText = "Memphis, TN",
                trackingNumber = "1ZW463200377332024",
                timeline = listOf(
                    TimelineStepUi("Label created", "Wed, Jul 9", StepState.DONE),
                    TimelineStepUi("Shipped", "Thu, Jul 10", StepState.DONE),
                    TimelineStepUi("In transit", "Fri, Jul 11 · latest update", StepState.CURRENT),
                    TimelineStepUi("Out for delivery", null, StepState.TODO),
                    TimelineStepUi("Delivered", null, StepState.TODO),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_DetailContent_Delayed() {
    ShipTheme {
        DetailContent(
            DetailUiState(
                loaded = true,
                name = "Boots",
                carrierName = "UPS",
                accentHex = "#5A3A22",
                // The delay does NOT displace the ETA headline — that is the point of the state.
                headline = "Arrives tomorrow",
                windowLabel = "Estimated delivery",
                windowText = "Sat, Aug 29 · 2:00 – 6:00 PM",
                delayNote = "Due to weather, your package is delayed by one business day.",
                locationText = "Houston, TX",
                trackingNumber = "1ZX9Y8Z70311111111",
                timeline = listOf(
                    TimelineStepUi("Label created", "Tue, Aug 25", StepState.DONE),
                    TimelineStepUi("Shipped", "Tue, Aug 25", StepState.DONE),
                    TimelineStepUi("In transit", "Fri, Aug 28 · latest update", StepState.CURRENT),
                    TimelineStepUi("Out for delivery", null, StepState.TODO),
                    TimelineStepUi("Delivered", null, StepState.TODO),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_DetailContent_Refreshing() {
    ShipTheme {
        DetailContent(
            DetailUiState(
                loaded = true,
                name = "Trail running shoes",
                carrierName = "FedEx",
                accentHex = "#5A1B9A",
                headline = "Arriving today",
                windowLabel = "Estimated delivery",
                windowText = "Fri, Jul 11 · by 9:00 PM",
                locationText = "On vehicle for delivery",
                trackingNumber = "FX 8823 0199 4422",
                refreshing = true,
                timeline = listOf(
                    TimelineStepUi("Label created", "Tue, Jul 8", StepState.DONE),
                    TimelineStepUi("Shipped", "Wed, Jul 9", StepState.DONE),
                    TimelineStepUi("In transit", "Thu, Jul 10", StepState.DONE),
                    TimelineStepUi("Out for delivery", "Fri, Jul 11 · latest update", StepState.CURRENT),
                    TimelineStepUi("Delivered", null, StepState.TODO),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_DetailContent_Delivered() {
    ShipTheme {
        DetailContent(
            DetailUiState(
                loaded = true,
                name = "Oat-blend coffee beans",
                carrierName = "USPS",
                accentHex = "#1E3A8F",
                headline = "Delivered",
                windowLabel = "Delivered",
                windowText = "Wed, Jul 9 · 2:14 PM",
                locationText = "Front porch",
                trackingNumber = "9400 1118 9922 3197 4284",
                timeline = listOf(
                    TimelineStepUi("Label created", "Sat, Jul 5", StepState.DONE),
                    TimelineStepUi("Shipped", "Sun, Jul 6", StepState.DONE),
                    TimelineStepUi("In transit", "Mon, Jul 7", StepState.DONE),
                    TimelineStepUi("Out for delivery", "Wed, Jul 9", StepState.DONE),
                    TimelineStepUi("Delivered", "Wed, Jul 9 · 2:14 PM", StepState.DONE),
                ),
            ),
        )
    }
}
