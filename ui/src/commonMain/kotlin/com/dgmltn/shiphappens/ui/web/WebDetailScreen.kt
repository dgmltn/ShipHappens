package com.dgmltn.shiphappens.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.dgmltn.shiphappens.design.ShipColors
import com.dgmltn.shiphappens.design.colorFromHex
import com.dgmltn.shiphappens.ui.LightStatusBarIcons
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WebDetailScreen(parcelId: String, onBack: () -> Unit) {
    val vm: WebDetailViewModel = koinViewModel(key = "web-$parcelId") { parametersOf(parcelId) }
    val s by vm.state.collectAsState()
    val accent = colorFromHex(s.accentHex)
    LightStatusBarIcons()

    Column(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Row(
            Modifier.fillMaxWidth().background(accent)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ Back", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f))
                    .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp))
            Text(s.carrierName, color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        if (s.showLoginHint) {
            Text(
                "Sign in on this page for delivery photos and precise windows — Ship Happens remembers the session.",
                color = ShipColors.muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(ShipColors.card).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        val spec = s.spec
        if (s.loaded && spec != null) {
            PlatformWebView(
                url = s.url, spec = spec,
                onPayload = vm::onPayload, onEvent = vm::onEvent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
