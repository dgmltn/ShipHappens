package com.shiphappens.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.design.*
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DetailScreen(parcelId: String, onBack: () -> Unit) {
    val vm: DetailViewModel = koinViewModel(key = parcelId) { parametersOf(parcelId) }
    val s by vm.state.collectAsState()
    DetailContent(s, onBack)
}

@Composable
fun DetailContent(state: DetailUiState, onBack: () -> Unit = {}) {
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
                Text(state.carrierName, color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
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
                    Row {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
                            val dotColor = when (step.state) {
                                StepState.DONE -> accent
                                StepState.CURRENT -> Color.White
                                StepState.TODO -> ShipColors.hairlineStrong
                            }
                            Box(Modifier.size(13.dp).clip(CircleShape).background(dotColor)
                                .then(if (step.state == StepState.CURRENT) Modifier.border(3.dp, accent, CircleShape) else Modifier))
                            if (i < state.timeline.lastIndex) {
                                Box(Modifier.width(2.dp).height(32.dp)
                                    .background(if (step.state == StepState.TODO) ShipColors.segmentBg else accent))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.padding(bottom = 4.dp)) {
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
                Text(state.trackingNumber, color = ShipColors.ink, fontFamily = monoFamily(), fontSize = 14.sp)
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
