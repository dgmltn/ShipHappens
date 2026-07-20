package com.shiphappens.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.design.ShipColors
import com.shiphappens.design.colorFromHex
import com.shiphappens.ui.LightStatusBarIcons
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WebLoginScreen(sourceId: String, onBack: () -> Unit) {
    val vm: WebLoginViewModel = koinViewModel(key = "login-$sourceId") { parametersOf(sourceId) }
    val s by vm.state.collectAsState()

    LaunchedEffect(s.done) { if (s.done) onBack() }
    LightStatusBarIcons()

    Column(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Row(
            Modifier.fillMaxWidth().background(colorFromHex(s.accentHex))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ Cancel", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f))
                    .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp))
            Text("Sign in to ${s.name}", color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        val spec = s.spec
        if (spec != null) {
            PlatformWebView(
                url = s.url, spec = spec,
                onPayload = { /* login page produces no tracking payloads worth applying */ },
                onEvent = vm::onEvent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
