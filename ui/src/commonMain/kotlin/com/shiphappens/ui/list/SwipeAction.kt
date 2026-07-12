package com.shiphappens.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.design.ShipColors
import com.shiphappens.design.res.Res
import com.shiphappens.design.res.ic_archive_outline
import com.shiphappens.design.res.ic_archive_outline_open
import com.shiphappens.design.res.ic_trash_outline
import com.shiphappens.design.res.ic_trash_outline_open
import kotlin.math.abs
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
    closedIcon = Res.drawable.ic_archive_outline,
    openIcon = Res.drawable.ic_archive_outline_open,
    onTrigger = onTrigger,
)

fun restoreAction(onTrigger: () -> Unit) = SwipeAction(
    label = "Restore", background = ShipColors.archiveAccent,
    closedIcon = Res.drawable.ic_archive_outline,
    openIcon = Res.drawable.ic_archive_outline_open,
    onTrigger = onTrigger,
)

fun deleteAction(onTrigger: () -> Unit) = SwipeAction(
    label = "Delete", background = ShipColors.urgent,
    closedIcon = Res.drawable.ic_trash_outline,
    openIcon = Res.drawable.ic_trash_outline_open,
    onTrigger = onTrigger,
)

/**
 * Two-directional swipe wrapper ported from WorldClock's DismissableCityListItem: the positional
 * [threshold] drives both a haptic tick and an icon swap (closed -> open) the instant it's crossed,
 * independently per direction. The dismiss action fires only if, at the moment the finger is
 * released, the row is still dragged at least [threshold] past its resting position - a fast flick
 * or a drag that retreats back under [threshold] before release springs back instead.
 *
 * @param threshold how far the row must be dragged (and still be, on release) to trigger the action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionRow(
    startToEnd: SwipeAction,
    endToStart: SwipeAction,
    modifier: Modifier = Modifier,
    threshold: Dp = 96.dp,
    content: @Composable () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val positionalThreshold = with(density) { threshold.toPx() }
    var rowWidth by remember { mutableIntStateOf(0) }
    var hasReachedThreshold by remember { mutableStateOf(false) }

    // Deliberately a plain `remember`, NOT `rememberSwipeToDismissBoxState`'s `rememberSaveable`.
    // Material3's default state is saveable, keyed by this composable's position inside the
    // LazyColumn item it lives in (see ListContent's `items(state.cards, key = ...)`). Since a
    // dismissed row's data mutation removes it from its current list, its LazyColumn item key
    // (and this row's slot) gets disposed - but rememberSaveable's underlying SaveableStateHolder
    // keeps the last-saved (non-Settled!) dismiss value around indefinitely for that exact key.
    // If the same package later returns to a tab/key it previously occupied (e.g. Active ->
    // Archived -> restored back to Active), that key is reused and the stale saved value
    // resurrects, snapping the row open again on its very first layout pass with no animation -
    // reproducing the "stuck swipe background" bug. A row here should ALWAYS start Settled: it's
    // never legitimate for gesture state to outlive the composable instance. Plain `remember`
    // scopes this state to the current composition subtree only, so a fresh instance is always
    // Settled regardless of what the same key did previously.
    // The dismiss action must fire ONLY when the finger is released AND the row is still past
    // [threshold] at that instant. Two independent traps make this subtle:
    //
    //  1. onTrigger must never run mid-drag. It's wired below to SwipeToDismissBox's `onDismiss`,
    //     which fires off `settledValue` - set only once a drag settles on release, never mid-drag.
    //     (Calling onTrigger from confirmValueChange, as this once did, fired the real mutation while
    //     the finger was still down, the instant the drag crossed the halfway anchor.)
    //
    //  2. The threshold veto must read the LIVE release offset, not a latched flag. Compose's fling
    //     evaluates confirmValueChange synchronously against the release offset
    //     (SnapLayoutInfoProvider.calculateSnapOffset in foundation's AnchoredDraggable.kt), and that
    //     fling ignores the positional threshold entirely once release velocity exceeds ~125dp/s.
    //     Gating on the async `hasReachedThreshold` latch (updated by the LaunchedEffect below, a
    //     frame behind) let a fast flick, or a drag that retreated back under the threshold, still
    //     dismiss. Reading requireOffset() here vetoes both: unless |offset| is genuinely >=
    //     [threshold] the moment the finger lifts, the row springs back. confirmValueChange stays
    //     side-effect-free. It runs only during a live drag/settle (never from the pre-drag
    //     trySnapTo path), so requireOffset() is always initialized when we read it. `stateHolder`
    //     breaks the chicken-and-egg of referencing the state from its own constructor lambda.
    val stateHolder = remember { object { var state: SwipeToDismissBoxState? = null } }
    val dismissState = remember(density, positionalThreshold) {
        @Suppress("DEPRECATION")
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { target ->
                val offset = stateHolder.state?.requireOffset() ?: 0f
                target != SwipeToDismissBoxValue.Settled && abs(offset) >= positionalThreshold
            },
            positionalThreshold = { positionalThreshold },
        )
    }.also { stateHolder.state = it }

    LaunchedEffect(dismissState.progress) {
        val distance = dismissState.progress * rowWidth
        val next = distance > positionalThreshold && rowWidth != 0 &&
            dismissState.targetValue != SwipeToDismissBoxValue.Settled
        if (next != hasReachedThreshold) {
            hasReachedThreshold = next
            if (next) hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.onSizeChanged { rowWidth = it.width },
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.StartToEnd) startToEnd.onTrigger() else endToStart.onTrigger()
        },
        backgroundContent = {
            val isStartToEnd = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = if (isStartToEnd) startToEnd else endToStart
            val icon = if (hasReachedThreshold) action.openIcon else action.closedIcon
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(action.background)
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
        },
        content = { content() },
    )
}
