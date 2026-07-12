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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.design.ShipColors
import com.shiphappens.design.res.Res
import com.shiphappens.design.res.ic_archive_outline
import com.shiphappens.design.res.ic_archive_outline_open
import com.shiphappens.design.res.ic_trash_outline
import com.shiphappens.design.res.ic_trash_outline_open
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
    closedIcon = Res.drawable.ic_archive_outline, openIcon = Res.drawable.ic_archive_outline_open,
    onTrigger = onTrigger,
)

fun restoreAction(onTrigger: () -> Unit) = SwipeAction(
    label = "Restore", background = ShipColors.archiveAccent,
    closedIcon = Res.drawable.ic_archive_outline, openIcon = Res.drawable.ic_archive_outline_open,
    onTrigger = onTrigger,
)

fun deleteAction(onTrigger: () -> Unit) = SwipeAction(
    label = "Delete", background = ShipColors.urgent,
    closedIcon = Res.drawable.ic_trash_outline, openIcon = Res.drawable.ic_trash_outline_open,
    onTrigger = onTrigger,
)

/**
 * Two-directional swipe wrapper ported from WorldClock's DismissableCityListItem: a 96dp
 * positional threshold drives both a haptic tick and an icon swap (closed -> open) the instant
 * it's crossed, independently per direction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionRow(
    startToEnd: SwipeAction,
    endToStart: SwipeAction,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val positionalThreshold = with(density) { 96.dp.toPx() }
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
    // confirmValueChange is deliberately side-effect-free (just the threshold veto). Compose's
    // AnchoredDraggableState invokes it not only at gesture release but ALSO mid-drag, the instant
    // the raw drag offset crosses the halfway point between two anchors (AnchoredDraggableState's
    // AnchoredDragScope.dragTo -> updateIfNeeded, in
    // androidx.compose.foundation.gestures.AnchoredDraggable.kt) - i.e. roughly half the row's full
    // width, well past our much smaller 96dp hasReachedThreshold. Calling onTrigger() from inside
    // confirmValueChange (as this used to do) fires the real archive/delete/restore mutation while
    // the finger is still down, before the user has released - confirmed on-device with a drag past
    // the halfway point held without lifting. The actual dismiss action must only fire once the
    // gesture is truly committed on release, so it's wired below to SwipeToDismissBox's `onDismiss`
    // instead, which fires from `settledValue` - only set once per complete drag session (release +
    // settle animation), never mid-drag.
    val dismissState = remember(density) {
        @Suppress("DEPRECATION")
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { it != SwipeToDismissBoxValue.Settled && hasReachedThreshold },
            positionalThreshold = { positionalThreshold },
        )
    }

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
