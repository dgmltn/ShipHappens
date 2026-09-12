package com.dgmltn.shiphappens.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgmltn.shiphappens.design.ShipColors
import com.dgmltn.shiphappens.design.ShipTheme
import com.dgmltn.shiphappens.design.colorFromHex
import com.dgmltn.shiphappens.design.res.Res
import com.dgmltn.shiphappens.design.res.ic_open_in_new
import com.dgmltn.shiphappens.ui.StatusBarIconsForHeader
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WebDetailScreen(parcelId: String, onBack: () -> Unit) {
    val vm: WebDetailViewModel = koinViewModel(key = "web-$parcelId") { parametersOf(parcelId) }
    val s by vm.state.collectAsState()
    val accent = colorFromHex(s.accentHex)
    val uriHandler = LocalUriHandler.current
    StatusBarIconsForHeader(accent)

    Column(Modifier.fillMaxSize().background(ShipColors.bg)) {
        WebDetailHeader(
            carrierName = s.carrierName,
            accent = accent,
            onBack = onBack,
            // Null until the parcel resolves to a web spec: no URL to hand off yet.
            onOpenInBrowser = if (s.loaded) ({ uriHandler.openUri(s.url) }) else null,
        )
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

/**
 * Accent-colored top bar: "‹ Back" on the left, carrier name plus an open-in-browser action on
 * the right. [onOpenInBrowser] null hides the action (nothing to open yet).
 */
@Composable
fun WebDetailHeader(
    carrierName: String,
    accent: Color,
    onBack: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().background(accent)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 20.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹ Back", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f))
                .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(carrierName, color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            if (onOpenInBrowser != null) {
                Icon(
                    painterResource(Res.drawable.ic_open_in_new),
                    contentDescription = "Open in browser",
                    tint = Color.White.copy(alpha = .9f),
                    // 44dp tap target around a 24dp glyph; the circle clip bounds the ripple.
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onOpenInBrowser).padding(10.dp),
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview_WebDetailHeader() {
    ShipTheme {
        WebDetailHeader(carrierName = "Amazon", accent = colorFromHex("#FF9900"), onBack = {}, onOpenInBrowser = {})
    }
}

@Preview
@Composable
private fun Preview_WebDetailHeader_NotLoaded() {
    ShipTheme {
        WebDetailHeader(carrierName = "", accent = colorFromHex("#17150F"), onBack = {}, onOpenInBrowser = null)
    }
}
