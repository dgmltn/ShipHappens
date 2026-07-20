package com.dgmltn.shiphappens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgmltn.shiphappens.ui.list.ToastUi
import com.dgmltn.shiphappens.design.ShipColors
import com.dgmltn.shiphappens.design.ShipTheme
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun ToastOverlay(toast: ToastUi?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    if (toast == null) return
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(ShipColors.ink)
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(toast.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (toast.showUndo) {
            Text("Undo", color = Color(0xFFF2A15A), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 4.dp, vertical = 2.dp))
        }
    }
}

@Composable
fun ToastOverlay(message: String?, modifier: Modifier = Modifier) {
    ToastOverlay(message?.let { ToastUi(it) }, onUndo = {}, modifier = modifier)
}

@Preview
@Composable
private fun Preview_ToastOverlay_WithUndo() {
    ShipTheme {
        ToastOverlay(ToastUi("Package archived", showUndo = true), onUndo = {})
    }
}

@Preview
@Composable
private fun Preview_ToastOverlay_MessageOnly() {
    ShipTheme {
        ToastOverlay(ToastUi("Delivery added"), onUndo = {})
    }
}
