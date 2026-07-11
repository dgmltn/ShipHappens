# Slide-to-Archive / Slide-to-Delete Design

## Goal

Extend the list screen's swipe gesture so that:

1. Packages can be archived even if they haven't been delivered yet (remove the `delivered`-only gate).
2. Swipe adds a genuine delete action alongside archive, using both swipe directions.
3. The gesture is upgraded to match the polish of `~/Developer/WorldClock`'s `DismissableCityListItem` — positional threshold, haptic feedback at the threshold, and an icon that swaps between closed/open states as you cross it — ported into this KMP project via Compose Multiplatform resources instead of Android-only drawables.

## Current state (for reference)

- `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt:179-231` (`ParcelRow`) wraps rows in Material3 `SwipeToDismissBox`, only enabling `EndToStart` (right-to-left), which calls `onArchive()`. `enableDismissFromStartToEnd = false`.
- `ListViewModel.toCard()` (`ListViewModel.kt:82-99`) computes `swipeable = delivered && tab == ListTab.ACTIVE` — swipe is only available for delivered packages on the Active tab.
- The Archived tab shows a "Restore" `OutlinedButton` instead of a swipe gesture (`showRestore = tab == ListTab.ARCHIVED`).
- There is no delete action anywhere in the app. `ParcelDao` has no query that removes a row from `parcels`; `deleteEvents()` only clears `tracking_events` as part of refreshing tracking history.
- `Parcel.isArchived` (`domain/Parcel.kt`) is a real boolean backed 1:1 by `ParcelEntity.isArchived`/`archivedAt`. "Delivered" is derived from `status == TrackingStatus.DELIVERED`, not a stored boolean.
- `tracking_events` already has `ForeignKey(..., onDelete = CASCADE)` on `parcelId` (`data/.../db/Entities.kt:25-32`), so deleting a `parcels` row cascades automatically.

## Behavior matrix

Swipe becomes available on every row in both tabs (the `swipeable`/`showRestore` gating is removed entirely):

| Tab | Swipe left→right (StartToEnd) | Swipe right→left (EndToStart) |
|---|---|---|
| Active | Archive (any status, not just delivered) | Delete forever |
| Archived | Delete forever | Restore |

Note the destructive direction is *not* fixed across tabs — it's Delete on the right for Active, but Delete on the left for Archived. The swipe wrapper takes an explicit action + background per direction per call site; it does not assume "one side is always destructive."

## Data / ViewModel changes

- **Archive / Restore**: behavior is unchanged from today — immediate flag flip (`repository.archive`/`repository.restore`) followed by a toast with Undo that flips the flag back. Only the triggering gesture (direction, tab) changes.
- **Delete** is new. Since there's no way to "undo" a real hard delete without reconstructing a full `Parcel` (including tracking history), delete is implemented as an **optimistic client-side hide**, not an immediate DB write:
  1. On crossing the delete threshold, the id is added to an in-memory `pendingDeleteIds: Set<String>` in `ListViewModel`. The row is filtered out of `cards` immediately.
  2. A 3.8s job starts (same duration as the existing archive-undo toast) and a toast with Undo is shown.
  3. If Undo is tapped, the job is cancelled and the id is removed from `pendingDeleteIds` — the row reappears. No DB write ever happened.
  4. If the timer elapses without Undo, `repository.delete(id)` runs for real, and the id is cleared from `pendingDeleteIds` (harmless cleanup once the row is genuinely gone from the underlying flow).
  5. The job is launched in `viewModelScope`. If the process dies mid-countdown, the coroutine dies with it and the parcel is **not** deleted — this is an intentional safe default (no silent data loss on backgrounding), not a bug to fix later.
- `ListViewModel` replaces its single `lastArchivedId: String?` with a small sealed `PendingUndo`:
  - `PendingUndo.FlagFlip(id: String, reverse: suspend () -> Unit)` — archive/restore undo path (existing behavior, generalized): `onArchive`/`onRestore` perform their action immediately and stash the opposite repository call as `reverse`, so `onUndo()` just invokes it without needing to know which action was originally taken.
  - `PendingUndo.PendingDelete(id: String, job: Job)` — delete undo path.
  `onUndo()` branches on the sealed type instead of assuming "undo == restore".
- New repository plumbing:
  - `ParcelDao.deleteParcel(id: String)` — `@Query("DELETE FROM parcels WHERE id = :id")`. No separate `deleteEvents` call needed; cascade handles it.
  - `ParcelRepository.delete(id: String) = dao.deleteParcel(id)`.
- `ParcelCardUi` (`ListUiState.kt`) drops `swipeable` and `showRestore`. The right-side `when` in `ParcelRow` simplifies to just "Delivered pill if delivered, else DaysRing" regardless of tab.
- Row-level guard: `isDismissEnabled = id !in pendingDeleteIds`, so a row already mid-undo-countdown can't be swiped again.
- Copy update: the Active-tab empty-state string ("Swipe a delivered package left to archive it") is stale once delivery status no longer gates archiving; update to reflect that any package can be archived.

## Gesture visuals, icons, haptics, colors

Ported from WorldClock's `DismissableCityListItem` (`~/Developer/WorldClock/app/src/main/java/com/dgmltn/worldclock/ui/screen/citylist/CityListItem.kt:75-151`) into a new composable, `ui/list/SwipeAction.kt`, so `ListScreen.kt` stays focused on layout:

- **Threshold + haptics**: 96dp `positionalThreshold` (via `LocalDensity`), a `LaunchedEffect(dismissState.progress)` per direction tracking `hasReachedThreshold`, firing `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)` the instant that direction's threshold is crossed — same mechanics as WorldClock, applied independently to whichever action (archive/delete/restore) is assigned to that direction on that tab.
- **Icon assets**: WorldClock's icons are Android-only `res/drawable` XML consumed via `painterResource(Int)`, which doesn't work in KMP `commonMain`. Instead, two open/closed vector pairs are added to `design/src/commonMain/composeResources/drawable/` (Compose Multiplatform resources, generating `Res.drawable.*`):
  - `ic_trash_outline` / `ic_trash_outline_open` — copied near-verbatim from WorldClock's two vector XML files (same lid-rotation-via-pivot-group technique), used for every "Delete forever" background.
  - `ic_archive_outline` / `ic_archive_outline_open` — new asset pair, same simple line-icon style (closed box → flap rotated open via the same pivot/rotation trick), reused for both "Archive" (Active tab) and "Restore" (Archived tab) backgrounds — "opening the box" reads fine for either action.
- **Colors**: `ShipColors.urgent` (existing red token) is reused for every "Delete forever" background — it already reads as danger and isn't otherwise used on this screen. A new token, `ShipColors.archiveAccent` (blue, `0xFF2563EB`), is added for Archive/Restore backgrounds, replacing today's use of red for archive (which read oddly given "urgent" now means "delete").
- Icon swaps instantly on threshold cross (no interpolation, matching WorldClock); the text label ("Archive" / "Delete" / "Restore") stays static.

## File-level changes

- `ui/list/SwipeAction.kt` (new) — reusable swipe wrapper: threshold tracking, haptics, icon-swap background, per-direction action/label/icon params.
- `design/src/commonMain/composeResources/drawable/{ic_trash_outline,ic_trash_outline_open,ic_archive_outline,ic_archive_outline_open}.xml` (new)
- `design/src/commonMain/kotlin/com/shiphappens/design/Theme.kt` — add `ShipColors.archiveAccent`.
- `data/src/commonMain/kotlin/com/shiphappens/data/db/ParcelDao.kt` — add `deleteParcel(id)`.
- `data/src/commonMain/kotlin/com/shiphappens/data/ParcelRepository.kt` — add `delete(id)`.
- `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListViewModel.kt` — `PendingUndo` sealed type, `onDelete(id)`, generalized `onUndo()`, remove `delivered &&` gate, updated empty-state copy.
- `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListUiState.kt` — remove `swipeable`/`showRestore` from `ParcelCardUi`.
- `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt` — `ParcelRow` uses `SwipeAction.kt`, simplified right-side `when`.

## Testing

- Unit tests on `ListViewModel`:
  - Archive-undo restores the flag (existing behavior, verify still covered).
  - Restore-undo re-archives the flag.
  - Delete-undo cancels the pending job and the row remains in `cards`.
  - Delete without undo (advance virtual time past 3.8s) calls `repository.delete` and the row is gone.
  - A row in `pendingDeleteIds` reports `isDismissEnabled = false`.
- `Preview_` coverage for the new swipe backgrounds (Archive, Delete, Restore, each pre/post threshold), following the existing preview-coverage convention from `a2379f1`.
- No DB migration needed — `isArchived`/cascade-delete already exist; `deleteParcel` is a plain query addition.

## Explicitly out of scope

- No confirmation dialog before delete — the threshold + haptic + undo-toast combination is considered sufficient friction/safety, matching WorldClock's approach (which has no confirm dialog either).
- No "can't delete the last item" guard — doesn't apply; the list already has an empty state for zero packages.
- No changes to how "delivered" is computed or displayed elsewhere in the app.
