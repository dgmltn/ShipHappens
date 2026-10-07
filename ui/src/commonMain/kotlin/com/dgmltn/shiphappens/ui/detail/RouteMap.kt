package com.dgmltn.shiphappens.ui.detail

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgmltn.shiphappens.design.ShipColors
import com.dgmltn.shiphappens.design.monoFamily
import com.dgmltn.shiphappens.geo.GeoAssets
import com.dgmltn.shiphappens.geo.GeoBounds
import com.dgmltn.shiphappens.geo.LandOutline
import com.dgmltn.shiphappens.geo.LatLng
import com.dgmltn.shiphappens.geo.Projector
import com.dgmltn.shiphappens.geo.placeRing
import com.dgmltn.shiphappens.geo.routeBounds

private val HitRadius = 24.dp
private val LandColor = Color(0xFFE1DED5) // a step darker than the card's 0xFFEEECE6

/** Projected stops and the route line, computed once per (stops, size) for both drawing and hit testing. */
private class RouteGeometry(val bounds: GeoBounds, val projector: Projector, val points: List<Offset>, val line: Path?)

private fun routeGeometry(stops: List<StopUi>, size: IntSize): RouteGeometry? {
    if (size.width <= 0 || size.height <= 0) return null
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val bounds = routeBounds(stops.map { it.at }, aspect = (width / height).toDouble()) ?: return null
    val projector = Projector(bounds, width, height)
    val points = stops.map { Offset(projector.x(it.at.lng), projector.y(it.at.lat)) }
    val line = if (points.size > 1) {
        Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
    } else {
        null
    }
    return RouteGeometry(bounds, projector, points, line)
}

@Composable
fun RouteMap(route: RouteUi, accent: Color, locationText: String?, modifier: Modifier = Modifier) {
    var selected by remember(route.stops) { mutableStateOf<Int?>(null) }
    RouteMapContent(route, accent, locationText, selected, onSelect = { selected = it }, modifier)
}

@Composable
internal fun RouteMapContent(
    route: RouteUi,
    accent: Color,
    locationText: String?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var land by remember { mutableStateOf(LandOutline.EMPTY) }
    LaunchedEffect(Unit) { land = GeoAssets.land() }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val geometry = remember(route.stops, canvasSize) { routeGeometry(route.stops, canvasSize) }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selectedStop = selected?.let { route.stops.getOrNull(it) }
    val selectedPoint = selected?.let { geometry?.points?.getOrNull(it) }

    Box(modifier.fillMaxSize().semantics { contentDescription = route.description }) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(geometry) {
                    val points = geometry?.points ?: return@pointerInput
                    val radiusPx = HitRadius.toPx()
                    detectTapGestures { tap ->
                        currentOnSelect(nextSelection(currentSelected, nearestStop(points, tap, radiusPx)))
                    }
                }
                // The land path is built in the cache block so it is rebuilt only when the geometry
                // or loaded land changes, not on every draw or selection change.
                .drawWithCache {
                    if (geometry == null) {
                        onDrawBehind {}
                    } else {
                        val landPath = landPath(land, geometry.projector, geometry.bounds)
                        onDrawBehind {
                            drawPath(landPath, LandColor)
                            drawRoute(route, geometry.points, geometry.line, accent, currentSelected)
                        }
                    }
                },
        )
        if (selectedStop != null && selectedPoint != null) {
            RouteCallout(selectedStop, selectedPoint)
        } else {
            locationText?.let {
                Text(
                    it,
                    color = ShipColors.muted,
                    fontSize = 10.sp,
                    fontFamily = monoFamily(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = .72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun RouteCallout(stop: StopUi, anchor: Offset) {
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val edge = with(LocalDensity.current) { 6.dp.roundToPx() }
    Column(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val right = anchor.x.roundToInt() + gap
                    val x = if (right + placeable.width <= constraints.maxWidth) right else anchor.x.roundToInt() - gap - placeable.width
                    val top = calloutTop(anchor.y.roundToInt(), placeable.height, gap, edge, constraints.maxHeight)
                    placeable.place(x.coerceAtLeast(0), top)
                }
            }
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = .94f))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(stop.name, color = ShipColors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        for (line in calloutLines(stop)) {
            Text(line, color = ShipColors.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Prefers above the stop, flips below when the top edge would clip it, and clamps as a last resort. */
internal fun calloutTop(anchorY: Int, height: Int, gap: Int, edge: Int, maxHeight: Int): Int {
    val maxTop = (maxHeight - height - edge).coerceAtLeast(edge)
    val above = anchorY - gap - height
    if (above >= edge) return above.coerceAtMost(maxTop)
    val below = anchorY + gap
    return if (below <= maxTop) below else above.coerceIn(edge, maxTop)
}

private fun landPath(land: LandOutline, p: Projector, b: GeoBounds): Path {
    val path = Path()
    for (ring in land.rings) {
        if (ring.size < 6) continue
        val placed = placeRing(ring, b) ?: continue
        path.moveTo(p.xPlaced(placed[0].toDouble()), p.y(placed[1].toDouble()))
        var i = 2
        while (i < placed.size) {
            path.lineTo(p.xPlaced(placed[i].toDouble()), p.y(placed[i + 1].toDouble()))
            i += 2
        }
        path.close()
    }
    return path
}

internal fun DrawScope.drawRoute(route: RouteUi, pts: List<Offset>, line: Path?, accent: Color, selected: Int? = null) {
    if (line != null) {
        drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    pts.forEachIndexed { i, o ->
        val last = i == pts.lastIndex
        when {
            last -> {
                drawCircle(accent, radius = 7.dp.toPx(), center = o)
                if (route.delivered) drawCheck(o)
            }
            i == 0 -> {
                drawCircle(Color.White, radius = 5.dp.toPx(), center = o)
                drawCircle(accent, radius = 5.dp.toPx(), center = o, style = Stroke(2.dp.toPx()))
            }
            else -> drawCircle(accent, radius = 3.5.dp.toPx(), center = o)
        }
        if (i == selected) {
            drawCircle(accent.copy(alpha = .35f), radius = 11.dp.toPx(), center = o, style = Stroke(3.dp.toPx()))
        }
    }
}

private fun DrawScope.drawCheck(c: Offset) {
    val s = 3.dp.toPx()
    val check = Path().apply {
        moveTo(c.x - s, c.y)
        lineTo(c.x - s * .2f, c.y + s * .8f)
        lineTo(c.x + s * 1.1f, c.y - s * .9f)
    }
    drawPath(check, Color.White, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private val PreviewAccent = Color(0xFF2F6FED)

private fun previewStop(name: String, lat: Double, lng: Double) =
    StopUi(name, LatLng(lat, lng), listOf(StopEventUi("Tue, Aug 18 · 9:12 AM", "Departed FedEx location")))

private val FedexTrail = listOf(
    previewStop("Foster City, CA", 37.55, -122.27),
    previewStop("South San Francisco, CA", 37.65, -122.41),
    previewStop("Sacramento, CA", 38.58, -121.49),
    previewStop("Carlsbad, CA", 33.16, -117.35),
)

@Composable
private fun PreviewCard(route: RouteUi, locationText: String, selected: Int? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(152.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFEEECE6))
            .border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp)),
    ) {
        RouteMapContent(route, PreviewAccent, locationText, selected, onSelect = {})
    }
}

@Preview
@Composable
private fun Preview_RouteMap_SinglePin() {
    val route = RouteUi(listOf(previewStop("Sacramento, CA", 38.58, -121.49)), delivered = false, description = "Sacramento, CA")
    PreviewCard(route, "Sacramento, CA")
}

@Preview
@Composable
private fun Preview_RouteMap_FedexTrail() {
    PreviewCard(RouteUi(FedexTrail, delivered = false, description = "Foster City to Carlsbad"), "Carlsbad, CA")
}

@Preview
@Composable
private fun Preview_RouteMap_Delivered() {
    PreviewCard(RouteUi(FedexTrail, delivered = true, description = "Foster City to Carlsbad"), "Carlsbad, CA")
}

@Preview
@Composable
private fun Preview_RouteMap_Pacific() {
    val stops = listOf(
        previewStop("Shenzhen, China", 22.54, 114.06),
        previewStop("Anchorage, AK", 61.17, -150.0),
        previewStop("Louisville, KY", 38.17, -85.74),
    )
    PreviewCard(RouteUi(stops, delivered = false, description = "Shenzhen to Louisville"), "Louisville, KY")
}

@Preview
@Composable
private fun Preview_RouteMap_CalloutOpen() {
    val stops = FedexTrail.mapIndexed { i, stop ->
        if (i == 2) stop.copy(events = (1..6).map { StopEventUi("Tue, Aug 18 · $it:12 AM", "Scan $it at facility") }) else stop
    }
    PreviewCard(RouteUi(stops, delivered = false, description = "Foster City to Carlsbad"), "Carlsbad, CA", selected = 2)
}
