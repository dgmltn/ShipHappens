package com.shiphappens.ui.list

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.design.ShipColors
import com.shiphappens.design.ShipPreview
import com.shiphappens.design.res.Res
import com.shiphappens.design.res.ic_box_closed
import com.shiphappens.design.res.ic_box_open
import com.shiphappens.design.res.ic_trash_outline
import com.shiphappens.design.res.ic_trash_outline_open
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

data class SwipeAction(
    val label: String,
    val background: Color,
    val closedIcon: DrawableResource,
    val openIcon: DrawableResource,
    val onTrigger: () -> Unit,
)

fun archiveAction(onTrigger: () -> Unit) = SwipeAction(
    label = "Archive", background = ShipColors.archiveAccent,
    closedIcon = Res.drawable.ic_box_closed,
    openIcon = Res.drawable.ic_box_open,
    onTrigger = onTrigger,
)

fun restoreAction(onTrigger: () -> Unit) = SwipeAction(
    label = "Restore", background = ShipColors.archiveAccent,
    closedIcon = Res.drawable.ic_box_closed,
    openIcon = Res.drawable.ic_box_open,
    onTrigger = onTrigger,
)

fun deleteAction(onTrigger: () -> Unit) = SwipeAction(
    label = "Delete", background = ShipColors.urgent,
    closedIcon = Res.drawable.ic_trash_outline,
    openIcon = Res.drawable.ic_trash_outline_open,
    onTrigger = onTrigger,
)

/**
 * Where the row settles when the finger lifts: at or past [thresholdPx] it exits toward the
 * swiped edge ([width], signed by drag direction); under it, back to rest (0). Release position
 * is the only input - velocity deliberately is not, so a fast flick lifted under the threshold
 * cancels like any other under-threshold release.
 */
internal fun swipeReleaseTarget(offset: Float, thresholdPx: Float, width: Float): Float =
    if (abs(offset) >= thresholdPx) sign(offset) * width else 0f

/**
 * Row that reveals the [startToEnd] / [endToStart] actions behind its content while dragged
 * horizontally. One rule decides everything at release (see [swipeReleaseTarget]): lifted at or
 * past [threshold], the row animates offscreen and the revealed action's onTrigger fires exactly
 * once, after the exit animation; lifted under it, the row springs back. Crossing [threshold]
 * mid-drag ticks a haptic and swaps the icon to its open variant; retreating swaps it back.
 * A row caught mid-animation is simply dragged again and the rule re-applies at next release.
 *
 * Gesture state is scoped to the composition (plain [remember], never saved/restored), so a
 * LazyColumn item key that gets recycled always starts at rest.
 */
@Composable
fun SwipeActionRow(
    startToEnd: SwipeAction,
    endToStart: SwipeAction,
    modifier: Modifier = Modifier,
    threshold: Dp = 96.dp,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    // The offset is in reading-direction units: positive reveals startToEnd. Drag deltas are
    // physical pixels, so mirror them under RTL; Modifier.offset {} below is RTL-aware to match.
    val mirror = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    var rowWidth by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    var triggered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pastThreshold by remember(thresholdPx) {
        derivedStateOf { abs(offset.value) >= thresholdPx }
    }
    LaunchedEffect(thresholdPx) {
        snapshotFlow { abs(offset.value) >= thresholdPx }.collect { engaged ->
            if (engaged) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            }
        }
    }

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            val limit = rowWidth.toFloat()
            offset.snapTo((offset.value + delta * mirror).coerceIn(-limit, limit))
        }
    }

    Box(
        modifier
            .onSizeChanged { rowWidth = it.width }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = !triggered,
                onDragStopped = {
                    val target = swipeReleaseTarget(offset.value, thresholdPx, rowWidth.toFloat())
                    scope.launch {
                        // A new drag interrupts animateTo (snapTo cancels it), so a caught row
                        // never triggers: this coroutine dies at the animateTo call.
                        offset.animateTo(target)
                        if (target != 0f) {
                            triggered = true
                            (if (target > 0f) startToEnd else endToStart).onTrigger()
                        }
                    }
                },
            ),
    ) {
        val isStartToEnd by remember { derivedStateOf { offset.value > 0f } }
        val action = if (isStartToEnd) startToEnd else endToStart
        val icon = if (pastThreshold) action.openIcon else action.closedIcon
        Row(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(20.dp))
                .background(action.background)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isStartToEnd) Arrangement.Start else Arrangement.End,
        ) {
            if (isStartToEnd) {
                Icon(painterResource(icon), action.label, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(action.label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            } else {
                Text(action.label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(icon), action.label, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

// Deliberately unstyled children: the preview demonstrates the gesture mechanics (run it in
// interactive mode), not row styling. The status line records each onTrigger, proving the action
// fires exactly once and only on a release at or past the threshold.
@Preview
@Composable
private fun Preview_SwipeActionRow() {
    var lastTriggered by remember { mutableStateOf("nothing yet") }
    ShipPreview {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Triggered: $lastTriggered")
            SwipeActionRow(
                startToEnd = archiveAction { lastTriggered = "Archive" },
                endToStart = deleteAction { lastTriggered = "Delete" },
            ) {
                PreviewRowContent("Active row: archive ->, <- delete")
            }
            SwipeActionRow(
                startToEnd = restoreAction { lastTriggered = "Restore" },
                endToStart = deleteAction { lastTriggered = "Delete" },
            ) {
                PreviewRowContent("Archived row: restore ->, <- delete")
            }
        }
    }
}

@Composable
private fun PreviewRowContent(label: String) {
    Box(
        Modifier.fillMaxWidth().background(Color.LightGray).padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label)
    }
}
