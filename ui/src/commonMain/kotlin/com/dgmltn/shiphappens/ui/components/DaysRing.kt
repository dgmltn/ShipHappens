package com.dgmltn.shiphappens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
    urgent: Boolean = days > 7,
    accentColor: Color = ShipColors.ringAccent,
    urgentColor: Color = ShipColors.urgent,
    modifier: Modifier = Modifier,
) {
    DaysRing(
        percent = days / 7f,
        text = if (days <= 7) "$days" else "7+",
        label = if (days == 1) "DAY" else "DAYS",
        accentColor = if (urgent) urgentColor else accentColor,
        textColor = if (urgent) ShipColors.urgent else ShipColors.ink,
        modifier = modifier,
    )
}

@Composable
fun DaysRing(
    percent: Float,
    text: String,
    label: String,
    accentColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val scrubbedPercent = percent.coerceIn(0f, 1f)
    Box(
        modifier = modifier.size(50.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 4.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            if (scrubbedPercent > 0f) {
                val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                drawArc(ShipColors.hairline, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
                drawArc(accentColor, -90f, 360f * scrubbedPercent, false, Offset(inset, inset), arcSize, style = stroke)
            } else {
                val stroke = Stroke(
                    width = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(1.dp.toPx(), 6.dp.toPx()),
                        phase = 0f
                    )
                )
                drawArc(ShipColors.hairline, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-12).dp)
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = hankenFamily()
            )
            Text(
                text = label,
                color = textColor.copy(alpha = 0.5f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Preview
@Composable
private fun Preview_DaysRing() {
    ShipPreview {
        FlowRow {
            (0..8).reversed().forEach {
                DaysRing(it)
            }
        }
    }
}

