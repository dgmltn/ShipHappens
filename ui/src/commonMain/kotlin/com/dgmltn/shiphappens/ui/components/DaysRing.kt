package com.dgmltn.shiphappens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgmltn.shiphappens.design.ShipColors
import com.dgmltn.shiphappens.design.ShipPreview
import com.dgmltn.shiphappens.design.hankenFamily


@Composable
fun DaysRing(
    days: Int,
    accent: Color,
    urgent: Boolean = days > 7,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(50.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            val inset = 4.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(ShipColors.hairline, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
            drawArc(accent, -90f, 360f * days/7, false, Offset(inset, inset), arcSize, style = stroke)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-12).dp)
        ) {
            val text = if(days <= 7) "$days" else "7+"
            Text(
                text = text,
                color = if (urgent) ShipColors.urgent else ShipColors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = hankenFamily()
            )
            Text(
                text = if (days == 1) "DAY" else "DAYS",
                color = ShipColors.faint,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Preview
@Composable
private fun Preview_DaysRing() {
    var fraction by remember { mutableStateOf(0.7f) }
    ShipPreview {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            DaysRing(
                days = (fraction * 10).toInt(),
                accent = if (fraction > 0.8f) ShipColors.urgent else Color(0xFF2563EB),
                urgent = fraction > 0.8f
            )
            Spacer(Modifier.height(16.dp))
            Slider(
                value = fraction,
                onValueChange = { fraction = it }
            )
        }
    }
}

