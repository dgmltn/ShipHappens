package com.dgmltn.shiphappens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgmltn.shiphappens.design.ShipColors
import com.dgmltn.shiphappens.design.ShipPreview

/**
 * The delay modifier's two presentations. A delay is orthogonal to the tracking stage — a package
 * can be in transit and late — so both of these sit BESIDE the stage rather than replacing it.
 *
 * The home card gets [DelayIcon] alone, matching the bare-icon idiom `sourceless` already uses on
 * that row: a labelled chip there was measured squeezing "In transit" down to "In tr..." on a
 * 720px-wide device, and the stage text is the thing that must stay readable. The full wording
 * and the carrier's explanation live on the detail screen, in [DelayNote].
 */

/** Warning triangle, drawn to match the app's thin hairline strokes (cf. ProhibitionIcon). */
@Composable
fun DelayIcon(
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val semantics = if (contentDescription == null) Modifier
        else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.size(16.dp).then(semantics)) {
        val stroke = 1.5.dp.toPx()
        val w = size.width
        val h = size.height
        val inset = stroke / 2f
        // Triangle with its apex at top-center and its base on the bottom edge.
        val apex = Offset(w / 2f, inset)
        val left = Offset(inset, h - inset)
        val right = Offset(w - inset, h - inset)
        listOf(apex to left, left to right, right to apex).forEach { (a, b) ->
            drawLine(tint, start = a, end = b, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        // Exclamation bar and dot, inset from the apex and base.
        drawLine(
            tint,
            start = Offset(w / 2f, h * 0.38f),
            end = Offset(w / 2f, h * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(tint, radius = stroke * 0.6f, center = Offset(w / 2f, h * 0.79f))
    }
}

/**
 * The detail screen's delay block: the "Delayed" heading plus the carrier's own explanation.
 * [note] is the carrier's sentence; when it adds nothing beyond the heading (some carriers only
 * repeat their status headline, e.g. UPS's "On the Way: Delayed"), only the heading renders.
 */
@Composable
fun DelayNote(note: String, modifier: Modifier = Modifier) {
    val body = note.takeIf { !it.equals("delayed", ignoreCase = true) && !it.endsWith(": Delayed") }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ShipColors.delayedBg)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DelayIcon(tint = ShipColors.delayed, contentDescription = null)
            Text("Delayed", color = ShipColors.delayed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (body != null) {
            Text(body, color = ShipColors.ink, fontSize = 13.sp)
        }
    }
}

@Preview
@Composable
private fun Preview_DelayNote_WithReason() {
    ShipPreview {
        DelayNote("Due to weather, your package is delayed by one business day.")
    }
}

@Preview
@Composable
private fun Preview_DelayNote_HeadingOnly() {
    // UPS's DOM fallback quotes its own headline, which would just say "Delayed" twice.
    ShipPreview { DelayNote("On the Way: Delayed") }
}

@Preview
@Composable
private fun Preview_DelayIcon() {
    ShipPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DelayIcon(tint = ShipColors.delayed, contentDescription = null)
            DelayIcon(tint = ShipColors.muted, contentDescription = null)
        }
    }
}
