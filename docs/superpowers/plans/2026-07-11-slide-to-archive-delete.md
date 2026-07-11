# Slide-to-Archive / Slide-to-Delete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the list screen's swipe gesture so packages can be archived regardless of delivery status, add a real (soft-undo) delete action, and port WorldClock's threshold/haptic/icon-swap polish via Compose Multiplatform resources.

**Architecture:** Both swipe directions become active on every row in both tabs, with the destructive action (delete) on a different side per tab (Active: right=delete; Archived: left=delete). Archive/Restore are immediate flag flips with an undo toast (existing pattern, generalized). Delete is an optimistic client-side hide (`pendingDeleteIds` in `ListViewModel`) with a 3.8s grace period before the real DB delete fires — undo just cancels the pending job, no DB write ever happens if undone.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Material3 `SwipeToDismissBox`), Room 3, Compose Multiplatform resources (`composeResources/drawable`).

## Global Constraints

- Behavior matrix (from spec): Active tab — left→right (StartToEnd) = Archive, right→left (EndToStart) = Delete forever. Archived tab — left→right (StartToEnd) = Delete forever, right→left (EndToStart) = Restore.
- Positional swipe threshold: 96dp, tracked via `LaunchedEffect(dismissState.progress)`, firing `HapticFeedbackType.GestureThresholdActivate` exactly once per threshold crossing (ported verbatim from `~/Developer/WorldClock`'s `DismissableCityListItem`).
- Icon assets live in `design/src/commonMain/composeResources/drawable/` as Compose Multiplatform vector resources (`Res.drawable.*`), not Android-only `res/drawable` + `painterResource(Int)`.
- Colors: `ShipColors.urgent` (existing red) = every "Delete forever" background. New `ShipColors.archiveAccent` (blue, `0xFF2563EB`) = Archive/Restore backgrounds.
- Delete undo window: 3.8s (matches the existing archive-undo toast duration).
- No confirmation dialog before delete, and no "last item" dismiss guard — both explicitly out of scope per the spec.
- Deviation from spec (documented here since it changes an implementation detail, not behavior): the spec's per-row `isDismissEnabled` guard against double-swiping a row mid-undo-countdown is unnecessary and is **not** implemented — a row enters `pendingDeleteIds` and is filtered out of `cards` in the same state update, so it's never rendered (and thus never swipeable) during its own countdown.
- Spec doc: `docs/superpowers/specs/2026-07-11-slide-to-archive-delete-design.md`

---

### Task 1: Data layer — hard delete support

**Files:**
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/db/ParcelDao.kt`
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/ParcelRepository.kt`
- Test: `data/src/jvmTest/kotlin/com/shiphappens/data/db/ParcelDaoTest.kt`
- Test: `data/src/jvmTest/kotlin/com/shiphappens/data/ParcelRepositoryTest.kt`

**Interfaces:**
- Produces: `ParcelDao.deleteParcel(id: String)` (suspend), `ParcelRepository.delete(id: String)` (suspend) — consumed by Task 3's `ListViewModel.onDelete`.

- [ ] **Step 1: Write the failing DAO test**

Add to `ParcelDaoTest.kt`, after `archive_and_restore`:

```kotlin
    @Test fun delete_removes_parcel_and_cascades_events() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        val ev = TrackingEvent(Instant.fromEpochMilliseconds(2000), "Departed facility", "Memphis, TN", TrackingStatus.IN_TRANSIT)
        dao.replaceEvents("a", listOf(ev.toEntity("a")))
        dao.deleteParcel("a")
        assertNull(dao.getById("a"))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :data:jvmTest --tests "com.shiphappens.data.db.ParcelDaoTest.delete_removes_parcel_and_cascades_events"`
Expected: FAIL — `deleteParcel` is unresolved.

- [ ] **Step 3: Add `deleteParcel` to `ParcelDao`**

In `ParcelDao.kt`, after the `restore` query:

```kotlin
    @Query("UPDATE parcels SET isArchived = 0, archivedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("DELETE FROM parcels WHERE id = :id")
    suspend fun deleteParcel(id: String)
}
```

(The existing `tracking_events` foreign key already has `onDelete = ForeignKey.CASCADE` on `parcelId`, so no separate `deleteEvents` call is needed.)

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :data:jvmTest --tests "com.shiphappens.data.db.ParcelDaoTest.delete_removes_parcel_and_cascades_events"`
Expected: PASS

- [ ] **Step 5: Write the failing repository test**

Add to `ParcelRepositoryTest.kt`, after `archive_restore_and_sorting`:

```kotlin
    @Test fun delete_removes_parcel() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val a = r.addParcel("A", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        r.delete(a.parcel.id)
        assertEquals(0, r.observeParcels(false).first().size)
        assertNull(r.observeParcel(a.parcel.id).first())
    }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :data:jvmTest --tests "com.shiphappens.data.ParcelRepositoryTest.delete_removes_parcel"`
Expected: FAIL — `delete` is unresolved on `ParcelRepository`.

- [ ] **Step 7: Add `delete` to `ParcelRepository`**

In `ParcelRepository.kt`, after `restore`:

```kotlin
    suspend fun archive(id: String) = dao.archive(id, clock.now().toEpochMilliseconds())
    suspend fun restore(id: String) = dao.restore(id)
    suspend fun delete(id: String) = dao.deleteParcel(id)
```

- [ ] **Step 8: Run it to verify it passes**

Run: `./gradlew :data:jvmTest --tests "com.shiphappens.data.ParcelRepositoryTest.delete_removes_parcel"`
Expected: PASS

- [ ] **Step 9: Run the full `:data` test suite to check for regressions**

Run: `./gradlew :data:jvmTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add data/src/commonMain/kotlin/com/shiphappens/data/db/ParcelDao.kt \
        data/src/commonMain/kotlin/com/shiphappens/data/ParcelRepository.kt \
        data/src/jvmTest/kotlin/com/shiphappens/data/db/ParcelDaoTest.kt \
        data/src/jvmTest/kotlin/com/shiphappens/data/ParcelRepositoryTest.kt
git commit -m "$(cat <<'EOF'
[data] Add hard-delete support for parcels

DELETE cascades to tracking_events via the existing FK, so no
separate event-cleanup call is needed.
EOF
)"
```

---

### Task 2: Design assets — icons and color token

**Files:**
- Create: `design/src/commonMain/composeResources/drawable/ic_trash_outline.xml`
- Create: `design/src/commonMain/composeResources/drawable/ic_trash_outline_open.xml`
- Create: `design/src/commonMain/composeResources/drawable/ic_archive_outline.xml`
- Create: `design/src/commonMain/composeResources/drawable/ic_archive_outline_open.xml`
- Modify: `design/src/commonMain/kotlin/com/shiphappens/design/Theme.kt`

**Interfaces:**
- Produces: `Res.drawable.ic_trash_outline`, `Res.drawable.ic_trash_outline_open`, `Res.drawable.ic_archive_outline`, `Res.drawable.ic_archive_outline_open` (all under `com.shiphappens.design.res`, per `compose.resources { packageOfResClass = "com.shiphappens.design.res" }` in `design/build.gradle.kts`), and `ShipColors.archiveAccent: Color` — all consumed by Task 4's `SwipeAction.kt`.

- [ ] **Step 1: Create `ic_trash_outline.xml`** (ported verbatim from `~/Developer/WorldClock/app/src/main/res/drawable/ic_trash_outline.xml`)

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <path
      android:pathData="M10.31,3.25h3.38c0.217,0 0.406,0 0.584,0.028a2.25,2.25 0,0 1,1.64 1.183c0.084,0.16 0.143,0.339 0.212,0.544l0.111,0.335l0.03,0.085a1.25,1.25 0,0 0,1.233 0.825h3a0.75,0.75 0,0 1,0 1.5h-17a0.75,0.75 0,0 1,0 -1.5h3.09a1.25,1.25 0,0 0,1.173 -0.91l0.112,-0.335c0.068,-0.205 0.127,-0.384 0.21,-0.544a2.25,2.25 0,0 1,1.641 -1.183c0.178,-0.028 0.367,-0.028 0.583,-0.028m-1.302,3a3,3 0,0 0,0.175 -0.428l0.1,-0.3c0.091,-0.273 0.112,-0.328 0.133,-0.368a0.75,0.75 0,0 1,0.547 -0.395a3,3 0,0 1,0.392 -0.009h3.29c0.288,0 0.348,0.002 0.392,0.01a0.75,0.75 0,0 1,0.547 0.394c0.021,0.04 0.042,0.095 0.133,0.369l0.1,0.3l0.039,0.112q0.059,0.164 0.136,0.315z"
      android:fillColor="#000"
      android:fillType="evenOdd"/>
  <path
      android:pathData="M5.915,9.45a0.75,0.75 0,1 0,-1.497 0.1l0.464,6.952c0.085,1.282 0.154,2.318 0.316,3.132c0.169,0.845 0.455,1.551 1.047,2.104s1.315,0.793 2.17,0.904c0.822,0.108 1.86,0.108 3.146,0.108h0.879c1.285,0 2.324,0 3.146,-0.108c0.854,-0.111 1.578,-0.35 2.17,-0.904c0.591,-0.553 0.877,-1.26 1.046,-2.104c0.162,-0.813 0.23,-1.85 0.316,-3.132l0.464,-6.952a0.75,0.75 0,0 0,-1.497 -0.1l-0.46,6.9c-0.09,1.347 -0.154,2.285 -0.294,2.99c-0.137,0.685 -0.327,1.047 -0.6,1.303c-0.274,0.256 -0.648,0.422 -1.34,0.512c-0.713,0.093 -1.653,0.095 -3.004,0.095h-0.774c-1.35,0 -2.29,-0.002 -3.004,-0.095c-0.692,-0.09 -1.066,-0.256 -1.34,-0.512c-0.273,-0.256 -0.463,-0.618 -0.6,-1.302c-0.14,-0.706 -0.204,-1.644 -0.294,-2.992z"
      android:fillColor="#000"/>
  <path
      android:pathData="M9.425,11.254a0.75,0.75 0,0 1,0.821 0.671l0.5,5a0.75,0.75 0,0 1,-1.492 0.15l-0.5,-5a0.75,0.75 0,0 1,0.671 -0.821m5.15,0a0.75,0.75 0,0 1,0.671 0.82l-0.5,5a0.75,0.75 0,0 1,-1.492 -0.149l0.5,-5a0.75,0.75 0,0 1,0.82 -0.671"
      android:fillColor="#000"/>
</vector>
```

- [ ] **Step 2: Create `ic_trash_outline_open.xml`** (ported from WorldClock's open variant — same lid path, wrapped in a rotated `<group>`)

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <group
      android:pivotY="5.5"
      android:pivotX="5.5"
      android:rotation="-20">
    <path
        android:pathData="M10.31,3.25h3.38c0.217,0 0.406,0 0.584,0.028a2.25,2.25 0,0 1,1.64 1.183c0.084,0.16 0.143,0.339 0.212,0.544l0.111,0.335l0.03,0.085a1.25,1.25 0,0 0,1.233 0.825h3a0.75,0.75 0,0 1,0 1.5h-17a0.75,0.75 0,0 1,0 -1.5h3.09a1.25,1.25 0,0 0,1.173 -0.91l0.112,-0.335c0.068,-0.205 0.127,-0.384 0.21,-0.544a2.25,2.25 0,0 1,1.641 -1.183c0.178,-0.028 0.367,-0.028 0.583,-0.028m-1.302,3a3,3 0,0 0,0.175 -0.428l0.1,-0.3c0.091,-0.273 0.112,-0.328 0.133,-0.368a0.75,0.75 0,0 1,0.547 -0.395a3,3 0,0 1,0.392 -0.009h3.29c0.288,0 0.348,0.002 0.392,0.01a0.75,0.75 0,0 1,0.547 0.394c0.021,0.04 0.042,0.095 0.133,0.369l0.1,0.3l0.039,0.112q0.059,0.164 0.136,0.315z"
        android:fillColor="#000"
        android:fillType="evenOdd"/>
  </group>
  <path
      android:pathData="M5.915,9.45a0.75,0.75 0,1 0,-1.497 0.1l0.464,6.952c0.085,1.282 0.154,2.318 0.316,3.132c0.169,0.845 0.455,1.551 1.047,2.104s1.315,0.793 2.17,0.904c0.822,0.108 1.86,0.108 3.146,0.108h0.879c1.285,0 2.324,0 3.146,-0.108c0.854,-0.111 1.578,-0.35 2.17,-0.904c0.591,-0.553 0.877,-1.26 1.046,-2.104c0.162,-0.813 0.23,-1.85 0.316,-3.132l0.464,-6.952a0.75,0.75 0,0 0,-1.497 -0.1l-0.46,6.9c-0.09,1.347 -0.154,2.285 -0.294,2.99c-0.137,0.685 -0.327,1.047 -0.6,1.303c-0.274,0.256 -0.648,0.422 -1.34,0.512c-0.713,0.093 -1.653,0.095 -3.004,0.095h-0.774c-1.35,0 -2.29,-0.002 -3.004,-0.095c-0.692,-0.09 -1.066,-0.256 -1.34,-0.512c-0.273,-0.256 -0.463,-0.618 -0.6,-1.302c-0.14,-0.706 -0.204,-1.644 -0.294,-2.992z"
      android:fillColor="#000"/>
  <path
      android:pathData="M9.425,11.254a0.75,0.75 0,0 1,0.821 0.671l0.5,5a0.75,0.75 0,0 1,-1.492 0.15l-0.5,-5a0.75,0.75 0,0 1,0.671 -0.821m5.15,0a0.75,0.75 0,0 1,0.671 0.82l-0.5,5a0.75,0.75 0,0 1,-1.492 -0.149l0.5,-5a0.75,0.75 0,0 1,0.82 -0.671"
      android:fillColor="#000"/>
</vector>
```

- [ ] **Step 3: Create `ic_archive_outline.xml`** (new — a lid bar over a hollow box outline, same simple-line style)

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <path
      android:pathData="M3,3 L21,3 L21,7 L3,7 Z M10,4.5 L14,4.5 L14,5.5 L10,5.5 Z"
      android:fillColor="#000"
      android:fillType="evenOdd"/>
  <path
      android:pathData="M4,9 L20,9 L20,20 L4,20 Z M5.5,10.5 L18.5,10.5 L18.5,18.5 L5.5,18.5 Z"
      android:fillColor="#000"
      android:fillType="evenOdd"/>
</vector>
```

- [ ] **Step 4: Create `ic_archive_outline_open.xml`** (same box body, lid rotated open via a pivoted `<group>` — same technique as the trash icon)

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <group
      android:pivotX="3"
      android:pivotY="7"
      android:rotation="-25">
    <path
        android:pathData="M3,3 L21,3 L21,7 L3,7 Z M10,4.5 L14,4.5 L14,5.5 L10,5.5 Z"
        android:fillColor="#000"
        android:fillType="evenOdd"/>
  </group>
  <path
      android:pathData="M4,9 L20,9 L20,20 L4,20 Z M5.5,10.5 L18.5,10.5 L18.5,18.5 L5.5,18.5 Z"
      android:fillColor="#000"
      android:fillType="evenOdd"/>
</vector>
```

- [ ] **Step 5: Add `ShipColors.archiveAccent`**

In `Theme.kt`, in the `ShipColors` object, after `urgent`:

```kotlin
    val urgent = Color(0xFFC2410C)
    val archiveAccent = Color(0xFF2563EB)
    val delivered = Color(0xFF1F7A4D)
```

- [ ] **Step 6: Verify the module builds and resources generate**

Run: `./gradlew :design:build`
Expected: BUILD SUCCESSFUL. (This regenerates the `Res.drawable.*` accessors used in Task 4 — if any XML has a typo, this is where it surfaces.)

- [ ] **Step 7: Commit**

```bash
git add design/src/commonMain/composeResources/drawable/ic_trash_outline.xml \
        design/src/commonMain/composeResources/drawable/ic_trash_outline_open.xml \
        design/src/commonMain/composeResources/drawable/ic_archive_outline.xml \
        design/src/commonMain/composeResources/drawable/ic_archive_outline_open.xml \
        design/src/commonMain/kotlin/com/shiphappens/design/Theme.kt
git commit -m "$(cat <<'EOF'
[design] Add archive/trash swipe-action icons and archiveAccent color

Trash icons ported from ~/Developer/WorldClock; archive icons are new,
using the same pivoted-group lid-rotation technique for the open state.
EOF
)"
```

---

### Task 3: ViewModel — swipe behavior, delete-with-undo, restore-with-undo

**Files:**
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListUiState.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListViewModel.kt`
- Test: `ui/src/androidHostTest/kotlin/com/shiphappens/ui/list/ListViewModelTest.kt`

**Interfaces:**
- Consumes: `ParcelRepository.delete(id: String)` (suspend, from Task 1).
- Produces: `ParcelCardUi` with fields `id, name, carrierName, accentHex, statusText, delivered, ring, urgent` (no `swipeable`/`showRestore`); `ListViewModel.onDelete(id: String)`, generalized `onArchive`/`onRestore`/`onUndo` — all consumed by Task 4's `ParcelRow`/`ListContent`.

- [ ] **Step 1: Remove `swipeable`/`showRestore` from `ParcelCardUi`**

In `ListUiState.kt`:

```kotlin
data class ParcelCardUi(
    val id: String,
    val name: String,
    val carrierName: String,
    val accentHex: String,
    val statusText: String,
    val delivered: Boolean,
    val ring: RingUi?,
    val urgent: Boolean,
)
```

- [ ] **Step 2: Write the failing tests**

In `ListViewModelTest.kt`:

1. Add `import kotlinx.coroutines.delay` to the import list (needed by the new helper in step below).

2. Add this helper method, next to `awaitRecorded`:

```kotlin
    /** Polls the repository (real time) until [id] is actually gone — for delete-without-undo. */
    private suspend fun awaitParcelDeleted(id: String, timeoutMs: Long = 10_000) =
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs) { while (repo.observeParcel(id).first() != null) delay(50) }
        }
```

3. Replace the existing `archived_tab_shows_restore_and_empty_texts` test (it asserts a field that's being removed) with:

```kotlin
    @Test fun archived_tab_shows_correct_header_and_empty_texts() = runTest {
        val vm = vm()
        vm.onTabSelect(ListTab.ARCHIVED)
        val empty = awaitState { it.tab == ListTab.ARCHIVED && it.emptyText != null }
        assertEquals("Nothing archived yet. Swipe a package right to archive it.", empty.emptyText)
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        repo.archive(added.parcel.id)
        val s = awaitState { it.cards.size == 1 }
        assertEquals("1 package archived", s.headerSub)
    }
```

4. Add these new tests after it:

```kotlin
    @Test fun archive_available_for_non_delivered_package() = runTest {
        val vm = vm()
        val added = repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        val before = awaitState { it.cards.size == 1 }
        assertFalse(before.cards.single().delivered)  // FakeSource defaults to IN_TRANSIT
        vm.onArchive(added.parcel.id)
        val afterArchive = awaitState { it.cards.isEmpty() }
        assertTrue(afterArchive.cards.isEmpty())
        vm.onTabSelect(ListTab.ARCHIVED)
        val archivedTab = awaitState { it.tab == ListTab.ARCHIVED && it.cards.size == 1 }
        assertEquals("Keyboard", archivedTab.cards.single().name)
    }

    @Test fun restore_shows_undo_toast_and_undo_rearchives() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        awaitState { it.cards.size == 1 }
        repo.archive(added.parcel.id)
        vm.onTabSelect(ListTab.ARCHIVED)
        awaitState { it.tab == ListTab.ARCHIVED && it.cards.size == 1 }
        vm.onRestore(added.parcel.id)
        val afterRestore = awaitState { it.tab == ListTab.ARCHIVED && it.cards.isEmpty() }
        assertTrue(afterRestore.cards.isEmpty())
        val toast = awaitRecorded { it.toast?.message == "Package restored" }.toast!!
        assertTrue(toast.showUndo)
        vm.onUndo()
        val afterUndo = awaitState { it.tab == ListTab.ARCHIVED && it.cards.size == 1 && it.toast == null }
        assertEquals(1, afterUndo.cards.size)
    }

    @Test fun delete_hides_immediately_and_undo_restores_it() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        awaitState { it.cards.size == 1 }
        vm.onDelete(added.parcel.id)
        val afterDelete = awaitState { it.cards.isEmpty() }
        assertTrue(afterDelete.cards.isEmpty())
        val toast = awaitRecorded { it.toast?.message == "Package deleted" }.toast!!
        assertTrue(toast.showUndo)
        assertNotNull(repo.observeParcel(added.parcel.id).first())  // hidden, not deleted yet
        vm.onUndo()
        val afterUndo = awaitState { it.cards.size == 1 && it.toast == null }
        assertEquals(1, afterUndo.cards.size)
        assertNotNull(repo.observeParcel(added.parcel.id).first())
    }

    @Test fun delete_without_undo_removes_parcel_after_grace_period() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        awaitState { it.cards.size == 1 }
        vm.onDelete(added.parcel.id)
        awaitState { it.cards.isEmpty() }
        awaitParcelDeleted(added.parcel.id)
        assertNull(repo.observeParcel(added.parcel.id).first())
    }
```

- [ ] **Step 3: Run the new/changed tests to verify they fail**

Run: `./gradlew :ui:testAndroidHostTest --tests "com.shiphappens.ui.list.ListViewModelTest"`
Expected: FAIL to compile — `onDelete` is unresolved, `ParcelCardUi` still has `swipeable`/`showRestore` used elsewhere in the module (that's expected; Task 4 fixes the UI side).

Since `ListScreen.kt` (Task 4) also references the removed fields, this module won't compile until Task 4 lands. That's fine — do Step 4 below first (which makes `ListViewModel.kt`/`ListUiState.kt` internally consistent), then treat "all green" as blocked on Task 4 completing; re-run this test command at the end of Task 4 instead of here.

- [ ] **Step 4: Implement the ViewModel changes**

Replace `ListViewModel.kt` in full with:

```kotlin
package com.shiphappens.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.data.*
import com.shiphappens.data.clipboard.ClipboardImportManager
import com.shiphappens.data.clipboard.PendingImport
import com.shiphappens.data.source.BuiltInCarrierDetection
import com.shiphappens.domain.*
import com.shiphappens.source.api.FailureReason
import com.shiphappens.design.accentHex
import com.shiphappens.ui.util.designFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.daysUntil

private val STATUS_TEXT = mapOf(
    TrackingStatus.LABEL_CREATED to "Label created",
    TrackingStatus.SHIPPED to "Shipped",
    TrackingStatus.IN_TRANSIT to "In transit",
    TrackingStatus.OUT_FOR_DELIVERY to "Out for delivery",
    TrackingStatus.DELIVERED to "Delivered",
    TrackingStatus.EXCEPTION to "Delivery exception",
    TrackingStatus.UNKNOWN to "Waiting for first update",
)

private sealed interface PendingUndo {
    data class FlagFlip(val reverse: suspend () -> Unit) : PendingUndo
    data class PendingDelete(val id: String, val job: Job) : PendingUndo
}

class ListViewModel(
    private val repository: ParcelRepository,
    private val clipboard: ClipboardImportManager,
    private val coordinator: RefreshCoordinator,
    private val clock: AppClock,
) : ViewModel() {

    private val tab = MutableStateFlow(ListTab.ACTIVE)
    private val manual = MutableStateFlow(ManualAddUi(options = carrierOptions()))
    private val pendingName = MutableStateFlow("")
    private val toast = MutableStateFlow<ToastUi?>(null)
    private val refreshing = MutableStateFlow(false)
    private val pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    private var pendingUndo: PendingUndo? = null
    private var toastJob: Job? = null

    init {
        viewModelScope.launch {
            coordinator.summaries.collect { s -> if (s.failed > 0) flash(failureMessage(s.firstFailureReason)) }
        }
    }

    private data class Content(
        val tab: ListTab, val active: List<Parcel>, val archived: List<Parcel>,
        val pending: PendingImport?, val manual: ManualAddUi,
    )

    private val content = combine(
        tab, repository.observeParcels(false), repository.observeParcels(true),
        clipboard.pending, manual,
    ) { t, act, arc, pend, man -> Content(t, act, arc, pend, man) }

    val state: StateFlow<ListUiState> =
        combine(content, pendingName, toast, refreshing, pendingDeleteIds) { c, pName, t, r, del ->
            val active = c.active.filterNot { it.id in del }
            val archived = c.archived.filterNot { it.id in del }
            val parcels = if (c.tab == ListTab.ACTIVE) active else archived
            val arriving = active.count { it.status != TrackingStatus.DELIVERED }
            ListUiState(
                dateLabel = clock.today().designFormat(),
                headerSub = if (c.tab == ListTab.ACTIVE) "$arriving arriving soon"
                    else "${archived.size} package${if (archived.size == 1) "" else "s"} archived",
                tab = c.tab,
                cards = parcels.map { it.toCard() },
                emptyText = if (parcels.isNotEmpty()) null
                    else if (c.tab == ListTab.ACTIVE) "No active deliveries right now."
                    else "Nothing archived yet. Swipe a package right to archive it.",
                pendingImport = if (c.tab == ListTab.ACTIVE) c.pending?.let {
                    PendingImportUi(it.carrier.displayName, it.carrier.accentHex(), it.trackingNumber, pName)
                } else null,
                manualAdd = c.manual,
                toast = t,
                isRefreshing = r,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private fun Parcel.toCard(): ParcelCardUi {
        val delivered = status == TrackingStatus.DELIVERED
        val days = etaDate?.let { clock.today().daysUntil(it) }
        val urgent = !delivered && days != null && days <= 1
        val statusText = if (!delivered && days != null && days <= 0 && status != TrackingStatus.EXCEPTION)
            "Out for delivery today" else STATUS_TEXT.getValue(status)
        return ParcelCardUi(
            id = id, name = name, carrierName = carrier.displayName, accentHex = carrier.accentHex(),
            statusText = statusText, delivered = delivered,
            ring = if (delivered) null else RingUi(
                number = (days?.coerceAtLeast(0) ?: 0).toString(),
                fraction = (status.stepIndex.coerceAtLeast(0)) / 4f,
            ),
            urgent = urgent,
        )
    }

    private fun carrierOptions() = listOf(CarrierOption(null, "Auto-detect", null)) +
        WellKnownCarriers.all.map { CarrierOption(it.code, it.displayName, it.accentHex()) }

    fun onTabSelect(t: ListTab) { tab.value = t }

    fun onManualName(v: String) = manual.update { it.copy(name = v) }
    fun onManualTracking(v: String) = manual.update { it.withEffective(tracking = v) }
    fun onPickerToggle() = manual.update { it.copy(pickerOpen = !it.pickerOpen) }
    fun onPickCarrier(code: String?) = manual.update { it.withEffective(picked = code, closePicker = true) }
    fun onClearManual() = manual.update { ManualAddUi(options = it.options) }

    private fun ManualAddUi.withEffective(
        tracking: String = this.tracking, picked: String? = this.pickedCarrierCode, closePicker: Boolean = false,
    ): ManualAddUi {
        val effective = picked?.let { WellKnownCarriers.byCode(it) } ?: BuiltInCarrierDetection.detect(tracking)
        return copy(
            tracking = tracking, pickedCarrierCode = picked,
            pickerOpen = if (closePicker) false else pickerOpen,
            effectiveCarrierName = effective?.displayName,
            effectiveAccentHex = effective?.accentHex(),
        )
    }

    fun onAddManual() {
        val m = manual.value
        if (m.tracking.isBlank()) return flash("Enter a tracking number")
        val carrier = m.pickedCarrierCode?.let { WellKnownCarriers.byCode(it) }
        viewModelScope.launch {
            when (repository.addParcel(m.name, m.tracking, carrier)) {
                is AddResult.Added -> { onClearManual(); flash("Delivery added") }
                AddResult.Duplicate -> flash("That package is already in your list")
                AddResult.NoCarrier -> flash("Tap the icon to choose a carrier")
            }
        }
    }

    fun onPendingName(v: String) { pendingName.value = v }
    fun onDismissPending() { clipboard.dismiss(); pendingName.value = "" }
    fun onAcceptPending() {
        val p = clipboard.pending.value ?: return
        viewModelScope.launch {
            when (repository.addParcel(pendingName.value, p.trackingNumber, p.carrier)) {
                is AddResult.Added -> { clipboard.dismiss(); pendingName.value = ""; flash("Delivery added") }
                AddResult.Duplicate -> flash("That package is already in your list")
                AddResult.NoCarrier -> flash("Tap the icon to choose a carrier")
            }
        }
    }

    fun onArchive(id: String) {
        pendingUndo = PendingUndo.FlagFlip { repository.restore(id) }
        viewModelScope.launch { repository.archive(id); flash("Package archived", undo = true, ms = 3_800) }
    }

    fun onRestore(id: String) {
        pendingUndo = PendingUndo.FlagFlip { repository.archive(id) }
        viewModelScope.launch { repository.restore(id); flash("Package restored", undo = true, ms = 3_800) }
    }

    fun onDelete(id: String) {
        pendingDeleteIds.update { it + id }
        val job = viewModelScope.launch {
            delay(3_800)
            repository.delete(id)
            pendingDeleteIds.update { it - id }
        }
        pendingUndo = PendingUndo.PendingDelete(id, job)
        flash("Package deleted", undo = true, ms = 3_800)
    }

    fun onUndo() {
        when (val pending = pendingUndo) {
            is PendingUndo.FlagFlip -> {
                toastJob?.cancel(); toast.value = null
                viewModelScope.launch { pending.reverse() }
            }
            is PendingUndo.PendingDelete -> {
                pending.job.cancel()
                pendingDeleteIds.update { it - pending.id }
                toastJob?.cancel(); toast.value = null
            }
            null -> return
        }
        pendingUndo = null
    }

    fun onRefresh() {
        viewModelScope.launch {
            refreshing.value = true
            val s = repository.refreshAll(force = true)
            refreshing.value = false
            if (s.failed > 0) flash(failureMessage(s.firstFailureReason))
        }
    }

    fun onForeground() {
        viewModelScope.launch { clipboard.checkClipboard() }
        coordinator.onAppForeground()
    }

    private fun failureMessage(reason: FailureReason?) = when (reason) {
        FailureReason.AUTH -> "Couldn't refresh — check your source API keys"
        FailureReason.RATE_LIMITED -> "Couldn't refresh — rate limited, try later"
        else -> "Couldn't refresh — network error"
    }

    private fun flash(message: String, undo: Boolean = false, ms: Long = 2_600) {
        toastJob?.cancel()
        toast.value = ToastUi(message, undo)
        toastJob = viewModelScope.launch { delay(ms); toast.value = null }
    }
}
```

- [ ] **Step 5: Commit** (module won't fully build/test yet — `ListScreen.kt` still references the removed fields; that's Task 4)

```bash
git add ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListUiState.kt \
        ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListViewModel.kt \
        ui/src/androidHostTest/kotlin/com/shiphappens/ui/list/ListViewModelTest.kt
git commit -m "$(cat <<'EOF'
[ui] Generalize archive/restore undo and add optimistic-hide delete

Archive/restore are unchanged immediate flag-flips with undo; delete is
a new optimistic client-side hide (pendingDeleteIds) with a 3.8s grace
period before the real DB delete, matching the archive-undo timing.
EOF
)"
```

---

### Task 4: Compose UI — SwipeActionRow, ParcelRow, ListContent wiring, Previews

**Files:**
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/SwipeAction.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt`

**Interfaces:**
- Consumes: `Res.drawable.ic_trash_outline`, `Res.drawable.ic_trash_outline_open`, `Res.drawable.ic_archive_outline`, `Res.drawable.ic_archive_outline_open`, `ShipColors.archiveAccent`, `ShipColors.urgent` (Task 2); `ListViewModel.onDelete` (Task 3).
- Produces: `SwipeAction` data class, `archiveAction()`/`restoreAction()`/`deleteAction()` factories, `SwipeActionRow` composable — used only within this file's `ParcelRow`.

- [ ] **Step 1: Create `SwipeAction.kt`**

```kotlin
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
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
    val positionalThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    var rowWidth by remember { mutableIntStateOf(0) }
    var hasReachedThreshold by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it != SwipeToDismissBoxValue.Settled && hasReachedThreshold) {
                if (it == SwipeToDismissBoxValue.StartToEnd) startToEnd.onTrigger() else endToStart.onTrigger()
                true
            } else {
                false
            }
        },
        positionalThreshold = { positionalThreshold },
    )

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
```

- [ ] **Step 2: Update `ParcelRow` in `ListScreen.kt`** (replace the existing `ParcelRow` function, lines 177-231)

```kotlin
@Composable
private fun ParcelRow(
    card: ParcelCardUi, tab: ListTab, onClick: () -> Unit,
    onArchive: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ShipColors.card)
                .border(1.dp, ShipColors.hairline, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CarrierBadge(card.accentHex)
            Column(Modifier.weight(1f)) {
                Text(card.name, color = ShipColors.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = hankenFamily())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(card.carrierName, color = colorFromHex(card.accentHex), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box(Modifier.size(3.dp).clip(CircleShape).background(ShipColors.hairlineStrong))
                    Text(card.statusText, color = ShipColors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                card.delivered -> Text(
                    "Delivered", color = ShipColors.delivered, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(ShipColors.deliveredBg)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
                card.ring != null -> DaysRing(card.ring, colorFromHex(card.accentHex), card.urgent)
            }
        }
    }

    val (startToEnd, endToStart) = if (tab == ListTab.ACTIVE) {
        archiveAction(onArchive) to deleteAction(onDelete)
    } else {
        deleteAction(onDelete) to restoreAction(onRestore)
    }

    Box(Modifier.padding(vertical = 9.dp)) {
        SwipeActionRow(startToEnd, endToStart) { content() }
    }
}
```

- [ ] **Step 3: Wire `onDelete` through `ListContent` and `ListScreen`**

In `ListScreen.kt`, add `onDelete: (String) -> Unit = {}` to `ListContent`'s parameter list (right after `onRestore`):

```kotlin
    onArchive: (String) -> Unit = {},
    onRestore: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onUndo: () -> Unit = {},
) {
```

Update the `items(...)` call inside `ListContent`'s `LazyColumn`:

```kotlin
                    items(state.cards, key = { it.id }) { card ->
                        ParcelRow(card, tab = state.tab, onClick = { onOpenDetail(card.id) },
                            onArchive = { onArchive(card.id) }, onRestore = { onRestore(card.id) },
                            onDelete = { onDelete(card.id) })
                    }
```

Update the `ListScreen` composable's call into `ListContent`:

```kotlin
        onArchive = vm::onArchive,
        onRestore = vm::onRestore,
        onDelete = vm::onDelete,
        onUndo = vm::onUndo,
    )
```

- [ ] **Step 4: Update the Preview functions** — remove the now-nonexistent `swipeable =`/`showRestore =` arguments from every `ParcelCardUi(...)` call in `ListScreen.kt`'s four `@Preview` functions (`Preview_ListContent_PopulatedWithRings`, `Preview_ListContent_Delivered`, `Preview_ListContent_ArchivedTab`, `Preview_ListContent_PendingImport`). For example, in `Preview_ListContent_Delivered`:

```kotlin
                cards = listOf(
                    ParcelCardUi("6", "Oat-blend coffee beans", "USPS", "#1E3A8F", "Delivered",
                        delivered = true, ring = null, urgent = false),
                    ParcelCardUi("7", "Paperback — The Overstory", "FedEx", "#5A1B9A", "Delivered",
                        delivered = true, ring = null, urgent = false),
                ),
```

Apply the same removal (just dropping `swipeable = ..., showRestore = ...,`) to the other three preview functions' `ParcelCardUi(...)` calls.

- [ ] **Step 5: Build the module**

Run: `./gradlew :ui:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the full ViewModel test suite (this is the deferred check from Task 3)**

Run: `./gradlew :ui:testAndroidHostTest --tests "com.shiphappens.ui.list.ListViewModelTest"`
Expected: BUILD SUCCESSFUL, all tests pass (including the new ones from Task 3, Step 2).

- [ ] **Step 7: Commit**

```bash
git add ui/src/commonMain/kotlin/com/shiphappens/ui/list/SwipeAction.kt \
        ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt
git commit -m "$(cat <<'EOF'
[ui] Wire tab-aware two-directional swipe with threshold/haptic/icon-swap

Active tab: left=archive, right=delete. Archived tab: left=delete,
right=restore. SwipeActionRow ports WorldClock's threshold/haptic/icon
-swap gesture treatment via Compose Multiplatform drawable resources.
EOF
)"
```

---

### Task 5: Manual verification

**Files:** none (verification only)

- [ ] **Step 1: Launch the app**

Use the `run` skill (or `./gradlew :composeApp:installDebug` + launch on an emulator/device, whichever this project's existing app-run path is) to install and open Ship Happens.

- [ ] **Step 2: Verify Active tab gestures**
  - Add or seed a package that is NOT delivered (e.g. via manual add). Swipe it left→right: confirm it turns blue with an archive icon that pops open past ~96dp of drag, a haptic tick fires at that point, and releasing past the threshold archives it (undo toast appears, package count is correct).
  - Swipe a package right→left: confirm it turns red with a trash icon that pops open past the threshold, releasing deletes it (toast says "Package deleted", the row disappears immediately).
  - Tap Undo on the delete toast before it expires: confirm the row reappears and is still present after the toast would have expired (i.e. it wasn't actually deleted).
  - Let a delete's toast expire without tapping Undo: confirm the package is gone even after restarting the tab (fully deleted, not just hidden).

- [ ] **Step 3: Verify Archived tab gestures**
  - Switch to the Archived tab. Swipe an archived package left→right: confirm it shows the red "Delete" background and deletes it (with undo).
  - Swipe an archived package right→left: confirm it shows the blue "Restore" background and moves it back to Active (with undo).

- [ ] **Step 4: Report results to the user** — summarize what was verified and flag anything that didn't match expectations (e.g. icon rotation direction looks off, haptic timing feels early/late) so it can be adjusted before merging.
