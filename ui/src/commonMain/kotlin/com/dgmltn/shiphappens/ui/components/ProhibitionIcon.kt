package com.dgmltn.shiphappens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dgmltn.shiphappens.design.ShipColors
import com.dgmltn.shiphappens.design.ShipPreview

/** Circle-with-slash "prohibited" glyph, drawn to match the app's thin hairline strokes. */
@Composable
fun ProhibitionIcon(
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val semantics = if (contentDescription == null) Modifier
        else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.size(16.dp).then(semantics)) {
        val stroke = 1.5.dp.toPx()
        val radius = (size.minDimension - stroke) / 2f
        drawCircle(tint, radius = radius, style = Stroke(width = stroke))
        // Slash from upper-left to lower-right of the circle, endpoints on the rim at 45°.
        val reach = radius * 0.7071f
        drawLine(
            tint,
            start = center - Offset(reach, reach),
            end = center + Offset(reach, reach),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Preview
@Composable
private fun Preview_ProhibitionIcon() {
    ShipPreview {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(24.dp),
        ) {
            ProhibitionIcon(tint = ShipColors.muted, contentDescription = "Source disabled")
            ProhibitionIcon(tint = ShipColors.urgent, contentDescription = null)
        }
    }
}
