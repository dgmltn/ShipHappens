# Secondary Carrier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A parcel whose carrier hands it to a second carrier (Amazon → Amazon Logistics, DHL eCommerce → USPS) is refreshed through both, and shows one merged history, the second carrier's number, and a "via" label.

**Architecture:** Sources only *report* a handoff (`TrackingSnapshot.handoff`). `ParcelRepository.refreshRow` tracks the primary, resolves the handoff number to an enabled source, tracks it, merges both snapshots with a pure `mergeSnapshots`, and persists once. Events carry `carrierCode` (null = the parcel's own carrier) so rows replace per carrier. The detail timeline lists each step's scans beneath it, projected from `Parcel.events`.

**Tech Stack:** Kotlin Multiplatform, Room 3 (auto-migration v4 → v5), kotlinx-serialization, Compose Multiplatform, kotlin.test on jvmTest/androidHostTest.

**Spec:** `docs/superpowers/specs/2026-10-23-secondary-carrier-design.md`

## Global Constraints

- Branch `feat/secondary-carrier` off `main`; WIP commits; local squash merge at the end; never push.
- Commit subjects start with a bracketed module tag; describe the problem, not the mechanics. No AI attribution trailers.
- Comments sparse and describing the code as it stands; no ticket or session references.
- Time values are `kotlin.time.Instant` / `Duration`; primitives only at DB and JSON boundaries.
- Carrier-specific strings stay in the carrier module; `:source:webview` holds only mechanisms.
- TDD: every production change follows a test that was watched to fail. Before claiming done: `./gradlew jvmTest testAndroidHostTest --continue` green.
- Nothing platform-specific is needed; all code is commonMain.

## Review Focus

1. A handoff whose carrier is the parcel's own carrier (a bug or a self-referential page) must be dropped, never fetched. Test in Task 7.
2. The secondary source failing (NOT_FOUND before the parcel reaches it) must leave the stored secondary rows intact and not count as a failed refresh. Test in Task 7.
3. The "More details" web page applying a primary-only snapshot must not delete the secondary's rows. Test in Task 7.
4. A stored `handoffCarrierCode` that no longer resolves in `WellKnownCarriers` must read as no handoff rather than crash. Test in Task 2.
5. DELIVERED from the primary with a stale IN_TRANSIT from the secondary (or vice versa) must read DELIVERED. Test in Task 5.

---

### Task 1: Scan verb vocabulary

**Files:**
- Create: `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/ScanVerb.kt`
- Test: `domain/src/commonTest/kotlin/com/dgmltn/shiphappens/domain/ScanVerbTest.kt`

**Interfaces:**
- Produces: `fun scanVerb(description: String): String?` — "Arrived", "Departed", "Processed", or null when the wording names no movement.

- [ ] **Step 1: Write the failing tests**

```kotlin
class ScanVerbTest {
    @Test fun arrival_wordings() {
        assertEquals("Arrived", scanVerb("Arrived at USPS Regional Facility"))
        assertEquals("Arrived", scanVerb("Package arrived at a carrier facility."))
        assertEquals("Arrived", scanVerb("ARRIVAL DHL ECOMMERCE FACILITY"))
        assertEquals("Arrived", scanVerb("At FedEx destination facility"))
    }
    @Test fun departure_wordings() {
        assertEquals("Departed", scanVerb("Departed USPS Regional Facility"))
        assertEquals("Departed", scanVerb("Package left the shipper facility"))
        assertEquals("Departed", scanVerb("Left FedEx origin facility"))
        assertEquals("Departed", scanVerb("Shipment has departed"))
    }
    @Test fun processing_wordings() {
        assertEquals("Processed", scanVerb("Processed Through Facility"))
        assertEquals("Processed", scanVerb("Package being processed at carrier facility."))
        assertEquals("Processed", scanVerb("Processing at UPS Facility"))
    }
    @Test fun unrecognized_wording_has_no_verb() {
        assertNull(scanVerb("Out for delivery"))
        assertNull(scanVerb("Delivered, In/At Mailbox"))
        assertNull(scanVerb(""))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :domain:jvmTest --tests '*ScanVerbTest*'`
Expected: compilation failure, `scanVerb` unresolved.

- [ ] **Step 3: Implement `scanVerb` in `ScanVerb.kt`**

Lowercase the wording; match "arriv" or a leading "at " → "Arrived"; "depart" or "left" → "Departed"; "process" → "Processed"; else null. Check arrival before departure, departure before processing.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :domain:jvmTest --tests '*ScanVerbTest*'`
Expected: PASS

- [ ] **Step 5: Commit** — `[domain] Name the movement a scan reports`

---

### Task 2: Domain fields and DB v5

**Files:**
- Modify: `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Tracking.kt`
- Modify: `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Parcel.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/db/Entities.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/db/ShipHappensDb.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/db/Mappers.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/db/ParcelDao.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/db/ParcelDaoTest.kt`
- Generated: `data/schemas/com.dgmltn.shiphappens.data.db.ShipHappensDb/5.json` (KSP writes it on build; commit it)

**Interfaces:**
- Produces (domain, in `Tracking.kt`):
  - `data class Handoff(val carrier: Carrier, val trackingNumber: String)`
  - `TrackingSnapshot.handoff: Handoff? = null`
  - `TrackingEvent.carrierCode: String? = null`
  - `Parcel.handoff: Handoff? = null`
- Produces (data):
  - `ParcelEntity.handoffCarrierCode: String?`, `ParcelEntity.handoffTrackingNumber: String?`
  - `TrackingEventEntity.carrierCode: String?`
  - `ParcelDao.deleteEventsOf(parcelId: String, carrierCode: String?)` — `WHERE parcelId = :parcelId AND carrierCode IS :carrierCode`
  - `ShipHappensDb` version 5 with `AutoMigration(from = 4, to = 5)`

- [ ] **Step 1: Write the failing tests in `ParcelDaoTest`**

```kotlin
@Test fun handoff_and_event_carrier_roundtrip() = runTest {
    val dao = db().parcelDao()
    val original = parcel("a").copy(handoff = Handoff(WellKnownCarriers.AMAZON_LOGISTICS, "TBA335777783614"))
    dao.upsertParcel(original.toEntity())
    val ev = TrackingEvent(Instant.fromEpochMilliseconds(2000), "Out for delivery", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY, carrierCode = "amzl")
    dao.replaceEvents("a", listOf(ev.toEntity("a")))
    val read = dao.getById("a")!!.toDomain()
    assertEquals(original.handoff, read.handoff)
    assertEquals("amzl", read.events.single().carrierCode)
}

@Test fun an_unknown_handoff_carrier_reads_as_no_handoff() = runTest {
    val dao = db().parcelDao()
    dao.upsertParcel(parcel("a").toEntity().copy(handoffCarrierCode = "pigeon", handoffTrackingNumber = "X1"))
    assertNull(dao.getById("a")!!.toDomain().handoff)
}

@Test fun events_delete_per_carrier() = runTest {
    val dao = db().parcelDao()
    dao.upsertParcel(parcel("a").toEntity())
    dao.replaceEvents("a", listOf(
        TrackingEvent(Instant.fromEpochMilliseconds(1000), "Shipped", null, TrackingStatus.SHIPPED).toEntity("a"),
        TrackingEvent(Instant.fromEpochMilliseconds(2000), "Out for delivery", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY, carrierCode = "amzl").toEntity("a"),
    ))
    dao.deleteEventsOf("a", null)
    assertEquals(listOf("amzl"), dao.getById("a")!!.events.map { it.carrierCode })
    dao.deleteEventsOf("a", "amzl")
    assertTrue(dao.getById("a")!!.events.isEmpty())
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :data:jvmTest --tests '*ParcelDaoTest*'`
Expected: compilation failure on `Handoff` / `carrierCode` / `deleteEventsOf`.

- [ ] **Step 3: Add the domain fields, entity columns, mapper lines, DAO query, and bump the DB**

Mapper: `handoff = parcel.handoffCarrierCode?.let(WellKnownCarriers::byCode)?.let { c -> parcel.handoffTrackingNumber?.let { Handoff(c, it) } }`. The DB comment for v4 → v5 says: two nullable columns on `parcels`, one on `tracking_events`, plain column adds.

- [ ] **Step 4: Run to verify pass, and confirm `5.json` exists**

Run: `./gradlew :data:jvmTest --tests '*ParcelDaoTest*' && ls data/schemas/com.dgmltn.shiphappens.data.db.ShipHappensDb/5.json`
Expected: PASS; file listed.

- [ ] **Step 5: Run every module that constructs these types**

Run: `./gradlew jvmTest testAndroidHostTest --continue`
Expected: green (positional constructor calls elsewhere are unaffected; the new fields have defaults).

- [ ] **Step 6: Commit** — `[data] Remember which carrier a parcel was handed to and which carrier reported each scan`

---

### Task 3: Amazon reports the handoff

**Files:**
- Modify: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/PayloadRouter.kt` (`DomCard`)
- Modify: `source/amazon/src/commonMain/kotlin/com/dgmltn/shiphappens/source/amazon/AmazonPageLogic.kt`
- Modify: `source/amazon/src/commonMain/kotlin/com/dgmltn/shiphappens/source/amazon/AmazonWebSpec.kt` (JS blob)
- Test: `source/amazon/src/commonTest/kotlin/com/dgmltn/shiphappens/source/amazon/AmazonPageLogicTest.kt`
- Test: `source/amazon/src/commonTest/kotlin/com/dgmltn/shiphappens/source/amazon/AmazonWebSpecTest.kt`

**Interfaces:**
- Produces: `DomCard.handoffText: String? = null` — verbatim text of the sheet's "Tracking ID" row.
- Produces: `internal fun amazonHandoff(text: String?): Handoff?`.
- `resolveAmazonCards` sets `handoff` on the snapshot it builds (both the Goto coarse and the Tracking outcome).

- [ ] **Step 1: Write the failing tests**

In `AmazonPageLogicTest`:

```kotlin
@Test fun the_sheets_tracking_id_row_names_the_delivering_carrier() {
    assertEquals(Handoff(WellKnownCarriers.AMAZON_LOGISTICS, "TBA335777783614"), amazonHandoff("Tracking ID: TBA335777783614"))
    assertEquals(Handoff(WellKnownCarriers.USPS, "9400111899223197428490"), amazonHandoff("Tracking ID:9400111899223197428490"))
}

@Test fun an_unrecognized_or_amazon_tracking_id_is_no_handoff() {
    assertNull(amazonHandoff("Tracking ID: 112-0087174-7649828"))
    assertNull(amazonHandoff("Tracking ID: ABC"))
    assertNull(amazonHandoff(null))
}

@Test fun the_order_page_snapshot_carries_the_handoff() {
    val card = DomCard(head = "Arriving today", stage = "Out for delivery", events = sheetEvents, handoffText = "Tracking ID: TBA335777783614")
    val s = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(card), todayIso = "2026-10-23"), pacific).snapshotOrNull()!!
    assertEquals(Handoff(WellKnownCarriers.AMAZON_LOGISTICS, "TBA335777783614"), s.handoff)
}
```

In `AmazonWebSpecTest`:

```kotlin
@Test fun extraction_js_reads_the_sheets_tracking_id_row() {
    assertTrue(AmazonWebSpec.extractionJs.contains("handoffText: sheetTrackingId("))
    assertTrue(AmazonWebSpec.extractionJs.contains("Tracking ID"))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :source:amazon:jvmTest`
Expected: compilation failure (`handoffText`, `amazonHandoff`).

- [ ] **Step 3: Implement**

`amazonHandoff`: regex `Tracking ID:?\s*([A-Za-z0-9]+)` (ignore case) → `WellKnownCarriers.detect(token)` → keep when the carrier is not `AMAZON`. In `resolveAmazonCards`, `.copy(handoff = amazonHandoff(pick.handoffText))` on the assembled snapshot. JS: add `function sheetTrackingId(sheet)` next to `sheetEvents` that returns `page.clean` of the first `.a-row` whose text contains "Tracking ID", else null; each card gains `handoffText: sheetTrackingId(trackingSheet(cards[k], k))`. Update the header comment of the sheet reader to mention the row.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :source:amazon:jvmTest :source:webview:jvmTest`
Expected: PASS

- [ ] **Step 5: Commit** — `[amazon] Report the Amazon Logistics number an order page prints`

---

### Task 4: DHL eCommerce reports the handoff

**Files:**
- Modify: `source/dhlecs/src/commonMain/kotlin/com/dgmltn/shiphappens/source/dhlecs/DhlEcsApiParser.kt`
- Test: `source/dhlecs/src/commonTest/kotlin/com/dgmltn/shiphappens/source/dhlecs/DhlEcsApiParserTest.kt`

**Interfaces:**
- `DhlEcsPackage` gains `dspName: String? = null`, `deliveryConfirmationNumber: String? = null`.
- `DhlEcsApiParser.parse` sets `handoff` when `dspName` names a well-known carrier other than DHL_ECOMMERCE (match code or display name, case-insensitive) and that carrier claims the normalized confirmation number.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun the_postal_partner_and_its_number_become_the_handoff() {
    assertEquals(Handoff(WellKnownCarriers.USPS, "9261234500000000000042"), DhlEcsApiParser.parse(envelope(enRoutePackage))!!.handoff)
}

@Test fun a_partner_that_does_not_claim_the_number_is_no_handoff() {
    val body = envelope("""{"status":"En Route","dspName":"USPS","deliveryConfirmationNumber":"12345","events":[]}""")
    assertNull(DhlEcsApiParser.parse(body)!!.handoff)
}

@Test fun an_unknown_or_dhl_partner_is_no_handoff() {
    assertNull(DhlEcsApiParser.parse(envelope("""{"status":"En Route","dspName":"Pigeon Post","deliveryConfirmationNumber":"9261234500000000000042","events":[]}"""))!!.handoff)
    assertNull(DhlEcsApiParser.parse(envelope("""{"status":"En Route","dspName":"DHL eCommerce","deliveryConfirmationNumber":"9261234500000000000042","events":[]}"""))!!.handoff)
    assertNull(DhlEcsApiParser.parse(envelope("""{"status":"En Route","events":[]}"""))!!.handoff)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :source:dhlecs:jvmTest` → compilation failure on `handoff`.

- [ ] **Step 3: Implement** — a private `partnerHandoff(dspName, number): Handoff?` in the parser; `assembleSnapshot(...).copy(handoff = ...)`.

- [ ] **Step 4: Run to verify pass** — `./gradlew :source:dhlecs:jvmTest` → PASS.

- [ ] **Step 5: Commit** — `[dhlecs] Report the USPS number DHL eCommerce hands a parcel to`

---

### Task 5: `mergeSnapshots`

**Files:**
- Create: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/SnapshotMerge.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/SnapshotMergeTest.kt`

**Interfaces:**
- Produces: `fun mergeSnapshots(primary: TrackingSnapshot, secondary: TrackingSnapshot, secondaryCarrier: Carrier): TrackingSnapshot`
  - secondary events tagged `carrierCode = secondaryCarrier.code`; primary events keep theirs (null).
  - duplicate = same minute and same non-null stage, or descriptions equal after lowercasing and stripping non-alphanumerics; keep the secondary's row unless only the primary's has a location.
  - "newest side" = the side with the later newest event; one side empty → the other; both empty → primary.
  - status: DELIVERED if either is DELIVERED; else newest side's non-UNKNOWN; else other's.
  - latestLocation: newest side's, else other's, else newest merged event with a location.
  - delayNote: newest side's, else other's.
  - etaDate/window: secondary's date with its window when the date is non-null, else primary's date and window.
  - handoff: primary's.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SnapshotMergeTest {
    private val amzl = WellKnownCarriers.AMAZON_LOGISTICS
    private fun ev(iso: String, desc: String, loc: String? = null, st: TrackingStatus? = null, code: String? = null) =
        TrackingEvent(Instant.parse(iso), desc, loc, st, code)

    @Test fun secondary_events_are_tagged_and_interleaved_by_time() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-22T10:00:00Z", "Shipped", st = TrackingStatus.SHIPPED)))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T10:00:00Z", "Arrived at facility", "Vista, US", TrackingStatus.IN_TRANSIT)))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(listOf(null, "amzl"), m.events.map { it.carrierCode })
        assertEquals(listOf("Shipped", "Arrived at facility"), m.events.map { it.description })
    }

    @Test fun same_minute_same_stage_rows_keep_the_secondarys_wording() {
        val p = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, events = listOf(ev("2026-10-23T10:57:20Z", "Package is out for delivery.", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY)))
        val s = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, events = listOf(ev("2026-10-23T10:57:00Z", "Out for delivery", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY)))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(listOf("Out for delivery"), m.events.map { it.description })
        assertEquals("amzl", m.events.single().carrierCode)
    }

    @Test fun a_duplicate_keeps_the_primarys_row_when_only_it_has_a_location() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T03:03:00Z", "Package arrived at a carrier facility.", "Vista, US", TrackingStatus.IN_TRANSIT)))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T03:03:40Z", "Arrived at carrier facility", null, TrackingStatus.IN_TRANSIT)))
        assertEquals("Vista, US", mergeSnapshots(p, s, amzl).events.single().location)
    }

    @Test fun equal_descriptions_ignoring_case_and_punctuation_are_duplicates() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T03:00:00Z", "Package left the shipper facility.")))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T09:00:00Z", "package left the shipper facility")))
        assertEquals(1, mergeSnapshots(p, s, amzl).events.size)
    }

    @Test fun the_side_with_the_newest_scan_wins_status_location_and_delay() {
        val p = TrackingSnapshot(TrackingStatus.SHIPPED, latestLocation = "Seattle, WA", delayNote = "Running late",
            events = listOf(ev("2026-10-22T10:00:00Z", "Shipped", st = TrackingStatus.SHIPPED)))
        val s = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, latestLocation = "Vista, US",
            events = listOf(ev("2026-10-23T10:00:00Z", "Out for delivery", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY)))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, m.status)
        assertEquals("Vista, US", m.latestLocation)
        assertNull(m.delayNote)
    }

    @Test fun an_unknown_status_on_the_newest_side_defers_to_the_other() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-22T10:00:00Z", "Shipped")))
        val s = TrackingSnapshot(TrackingStatus.UNKNOWN, events = listOf(ev("2026-10-23T10:00:00Z", "Scan")))
        assertEquals(TrackingStatus.IN_TRANSIT, mergeSnapshots(p, s, amzl).status)
    }

    @Test fun delivered_on_either_side_always_wins() {
        val p = TrackingSnapshot(TrackingStatus.DELIVERED, events = listOf(ev("2026-10-22T10:00:00Z", "Delivered", st = TrackingStatus.DELIVERED)))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T10:00:00Z", "In transit", st = TrackingStatus.IN_TRANSIT)))
        assertEquals(TrackingStatus.DELIVERED, mergeSnapshots(p, s, amzl).status)
        assertEquals(TrackingStatus.DELIVERED, mergeSnapshots(s, p, amzl).status)
    }

    @Test fun no_events_on_either_side_means_the_primary_wins() {
        val m = mergeSnapshots(TrackingSnapshot(TrackingStatus.SHIPPED, latestLocation = "A"), TrackingSnapshot(TrackingStatus.IN_TRANSIT, latestLocation = "B"), amzl)
        assertEquals(TrackingStatus.SHIPPED, m.status)
        assertEquals("A", m.latestLocation)
    }

    @Test fun the_secondarys_eta_and_window_win_when_it_has_a_date() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 10, 24), etaWindowEnd = LocalTime(20, 0))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 10, 23), etaWindowStart = LocalTime(12, 45), etaWindowEnd = LocalTime(16, 45))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(LocalDate(2026, 10, 23), m.etaDate)
        assertEquals(LocalTime(12, 45), m.etaWindowStart)
        assertEquals(LocalTime(16, 45), m.etaWindowEnd)
    }

    @Test fun without_a_secondary_date_the_primarys_eta_and_window_are_kept_together() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 10, 24), etaWindowEnd = LocalTime(20, 0))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaWindowStart = LocalTime(9, 0), etaWindowEnd = LocalTime(12, 0))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(LocalDate(2026, 10, 24), m.etaDate)
        assertNull(m.etaWindowStart)
        assertEquals(LocalTime(20, 0), m.etaWindowEnd)
    }

    @Test fun the_handoff_is_the_primarys() {
        val h = Handoff(amzl, "TBA1")
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, handoff = h)
        assertEquals(h, mergeSnapshots(p, TrackingSnapshot(TrackingStatus.IN_TRANSIT), amzl).handoff)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :data:jvmTest --tests '*SnapshotMergeTest*'` → `mergeSnapshots` unresolved.

- [ ] **Step 3: Implement `mergeSnapshots` in `SnapshotMerge.kt`**

Dedup walks the primary's rows and drops each one that has a match in the secondary's set (then takes the secondary's row, or swaps in the primary's when only it has a location); minute = `timestamp.toEpochMilliseconds() / 60_000`.

- [ ] **Step 4: Run to verify pass** — `./gradlew :data:jvmTest --tests '*SnapshotMergeTest*'` → PASS.

- [ ] **Step 5: Commit** — `[data] Combine two carriers' reports of one parcel into one snapshot`

---

### Task 6: Number-based source lookup

**Files:**
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/source/SourceRegistry.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/source/SourceRegistryTest.kt`

**Interfaces:**
- Produces: `suspend fun sourceForNumber(trackingNumber: String): TrackingSource?` — first enabled, implemented source whose `detectCarrier` claims the number; no pin.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun sourceForNumber_picks_the_enabled_source_that_claims_it() = runTest {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    val s = settings(scope)
    val amzl = FakeSource("amzl", detects = WellKnownCarriers.AMAZON_LOGISTICS)
    val ups = FakeSource("ups", detects = null)
    s.setSourceConfig("amzl", SourceConfig(enabled = true))
    s.setSourceConfig("ups", SourceConfig(enabled = true))
    assertEquals("amzl", SourceRegistry(listOf(ups, amzl), s).sourceForNumber("TBA335777783614")!!.descriptor.id)
    scope.cancel()
}

@Test fun sourceForNumber_ignores_disabled_and_stub_sources() = runTest {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    val s = settings(scope)
    val disabled = FakeSource("off", detects = WellKnownCarriers.AMAZON_LOGISTICS)
    val stub = FakeSource("stub", detects = WellKnownCarriers.AMAZON_LOGISTICS, implemented = false)
    s.setSourceConfig("stub", SourceConfig(enabled = true))
    assertNull(SourceRegistry(listOf(disabled, stub), s).sourceForNumber("TBA335777783614"))
    scope.cancel()
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :data:jvmTest --tests '*SourceRegistryTest*'` → unresolved.

- [ ] **Step 3: Implement** — share the enabled+implemented filter with `sourceFor`.

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `[data] Find the source for a number a page reported, not just a parcel`

---

### Task 7: Repository refresh through both carriers

**Files:**
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/ParcelRepository.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/ParcelRepositoryTest.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/ApplySnapshotTest.kt`

**Interfaces:**
- Consumes: `mergeSnapshots`, `SourceRegistry.sourceForNumber`, `ParcelDao.deleteEventsOf`.
- `applyAndDiff` persists `snapshot.handoff ?: row's stored handoff` (dropped when its carrier equals the parcel's); replaces event rows per carrier code present in `snapshot.events`.
- `refreshRow`: primary → handoff (snapshot else stored, drop self-handoff) → `sourceForNumber` → secondary track → on failure, secondary = `TrackingSnapshot(UNKNOWN, events = stored rows with that carrierCode)` → `mergeSnapshots` → `applyAndDiff`. Secondary failures never produce `RefreshOutcome.Failed`.

- [ ] **Step 1: Write the failing tests**

In `ParcelRepositoryTest` (FakeSource ids "amazon" and "amzl"; `amazon.detects = WellKnownCarriers.AMAZON`, `amzl.detects = AMAZON_LOGISTICS`; order number `"112-0087174-7649828"`; `h = Handoff(WellKnownCarriers.AMAZON_LOGISTICS, "TBA335777783614")`):

```kotlin
@Test fun refresh_follows_the_handoff_through_its_own_source_and_merges() = runTest {
    // primary: SHIPPED with one event + handoff; secondary: OUT_FOR_DELIVERY with one later event, eta 2026-10-23
    // assert: amzl.trackedNumbers == ["TBA335777783614"]; parcel.handoff == h; status OUT_FOR_DELIVERY;
    //         etaDate 2026-10-23; events carrierCodes == [null, "amzl"]; sourceId == "amazon"
}

@Test fun a_secondary_failure_is_quiet_and_keeps_its_stored_rows() = runTest {
    // first refresh succeeds on both (seeds one amzl row); then amzl.trackResult = Failure(NOT_FOUND)
    // assert: refreshAll(force = true).failed == 0; parcel still has the amzl row; status from the primary
}

@Test fun a_disabled_secondary_source_still_records_the_handoff() = runTest {
    // amzl not enabled; assert parcel.handoff == h, amzl.trackedNumbers empty, refresh returns true
}

@Test fun a_handoff_to_the_parcels_own_carrier_is_dropped() = runTest {
    // primary snapshot handoff = Handoff(WellKnownCarriers.AMAZON, "112-0087174-7649828"); assert parcel.handoff == null, no second track
}

@Test fun a_page_that_stops_printing_the_number_keeps_the_stored_handoff() = runTest {
    // refresh with handoff, then primary.trackResult without handoff; assert handoff still h and amzl tracked twice
}
```

In `ApplySnapshotTest`:

```kotlin
@Test fun a_primary_only_snapshot_keeps_the_secondarys_rows() = runTest {
    // seed: parcel + rows [primary "Shipped" (null code), "Out for delivery" (code "amzl")]
    // applySnapshot with events = [primary "In transit"] → rows == ["In transit"(null), "Out for delivery"("amzl")]
}

@Test fun a_snapshot_that_reports_a_handoff_persists_it() = runTest {
    // applySnapshot(handoff = h) → parcel.handoff == h
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :data:jvmTest` → the new tests fail (secondary never tracked / rows wiped / handoff null).

- [ ] **Step 3: Implement in `ParcelRepository`**

Keep `applyAndDiff`'s signature; add the per-carrier replace: `snapshot.events.groupBy { it.carrierCode }.forEach { (code, rows) -> dao.deleteEventsOf(id, code); dao.insertEvents(rows.map { it.toEntity(id) }) }` inside one `@Transaction` DAO method `replaceEventsOf(parcelId, carrierCode, events)`. Add `private suspend fun trackSecondary(parcel: Parcel, handoff: Handoff, storedEvents: List<TrackingEvent>): TrackingSnapshot?` (null when no enabled source). Update the KDoc of `applySnapshot` (events replace per carrier now).

- [ ] **Step 4: Run to verify pass** — `./gradlew :data:jvmTest` → PASS, including the pre-existing tests.

- [ ] **Step 5: Commit** — `[data] Refresh a handed-off parcel through both carriers`

---

### Task 8: UI — labels, second number, scans under the rail

**Files:**
- Modify: `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Display.kt` (`carrierLabel`)
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/list/ListViewModel.kt`
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/detail/DetailViewModel.kt`
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/detail/DetailScreen.kt`
- Test: `domain/src/commonTest/kotlin/com/dgmltn/shiphappens/domain/DisplayTest.kt`
- Test: `ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/detail/DetailViewModelTest.kt`
- Test: `ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/list/ListViewModelTest.kt`

**Interfaces:**
- Produces (domain): `val Parcel.carrierLabel: String` — `"Amazon via Amazon Logistics"` when `handoff != null`, else `carrier.displayName`.
- Produces (ui):
  - `data class ScanUi(val text: String, val carrier: String?)`
  - `TimelineStepUi(label, time, state, scans: List<ScanUi> = emptyList())`
  - `data class SecondaryNumberUi(val carrierName: String, val trackingNumber: String)`
  - `DetailUiState.carrierLabel: String = ""`, `DetailUiState.secondary: SecondaryNumberUi? = null`; `carrierName` stays the primary's (the number card label).
  - `ParcelCardUi.carrierName` becomes `parcel.carrierLabel`.
  - `internal fun scanRows(events: List<TrackingEvent>, handoff: Handoff?, tz: TimeZone): List<List<ScanUi>>` — five lists, one per step, oldest first. Text is `"$lead · ${date.designFormat()}"` where `lead` is: verb and location → `"$verb $location"`; verb, no location → the description; no verb, location → `"$description, $location"`; neither → the description. Unclassified (`status?.stepIndex` null or < 0) → step of the latest classified scan before it, else 0. `carrier` = `handoff.carrier.displayName` when `handoff != null && carrierCode != null`, else null.

- [ ] **Step 1: Write the failing tests**

`DisplayTest`:

```kotlin
@Test fun carrier_label_names_the_handoff() {
    val p = Parcel(id = "p", name = "n", trackingNumber = "1", carrier = WellKnownCarriers.AMAZON, createdAt = Instant.fromEpochMilliseconds(0))
    assertEquals("Amazon", p.carrierLabel)
    assertEquals("Amazon via Amazon Logistics", p.copy(handoff = Handoff(WellKnownCarriers.AMAZON_LOGISTICS, "TBA1")).carrierLabel)
}
```

`DetailViewModelTest` (pure, no VM, `scanRows` is internal in the same module):

```kotlin
@Test fun scans_sit_under_their_step_oldest_first_with_verb_city_and_day() {
    val tz = TimeZone.UTC
    val rows = scanRows(listOf(
        TrackingEvent(Instant.parse("2026-10-22T10:00:00Z"), "Shipped", null, TrackingStatus.SHIPPED),
        TrackingEvent(Instant.parse("2026-10-22T18:00:00Z"), "Arrived at USPS Regional Facility", "San Francisco, CA", TrackingStatus.IN_TRANSIT),
        TrackingEvent(Instant.parse("2026-10-23T02:00:00Z"), "Departed USPS Regional Facility", "San Francisco, CA", TrackingStatus.IN_TRANSIT),
        TrackingEvent(Instant.parse("2026-10-23T09:00:00Z"), "Arrived at USPS Regional Facility", "San Diego, CA", TrackingStatus.IN_TRANSIT),
    ), handoff = null, tz = tz)
    assertEquals(listOf("Shipped · Thu, Oct 22"), rows[1].map { it.text })
    assertEquals(listOf("Arrived San Francisco, CA · Thu, Oct 22", "Departed San Francisco, CA · Fri, Oct 23", "Arrived San Diego, CA · Fri, Oct 23"), rows[2].map { it.text })
    assertTrue(rows[0].isEmpty() && rows[3].isEmpty() && rows[4].isEmpty())
    assertTrue(rows.flatten().all { it.carrier == null })
}

@Test fun an_unclassified_scan_follows_the_last_classified_one() {
    val rows = scanRows(listOf(
        TrackingEvent(Instant.parse("2026-10-22T10:00:00Z"), "Mystery scan", "Reno, NV", null),
        TrackingEvent(Instant.parse("2026-10-22T12:00:00Z"), "In transit", "Reno, NV", TrackingStatus.IN_TRANSIT),
        TrackingEvent(Instant.parse("2026-10-22T13:00:00Z"), "Weather delay", "Reno, NV", null),
    ), handoff = null, tz = TimeZone.UTC)
    assertEquals(listOf("Mystery scan, Reno, NV · Thu, Oct 22"), rows[0].map { it.text })
    assertEquals(listOf("In transit, Reno, NV · Thu, Oct 22", "Weather delay, Reno, NV · Thu, Oct 22"), rows[2].map { it.text })
}

@Test fun secondary_scans_carry_a_carrier_tag_only_when_the_parcel_has_a_handoff() {
    val ev = TrackingEvent(Instant.parse("2026-10-23T10:00:00Z"), "Out for delivery", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY, carrierCode = "amzl")
    val h = Handoff(WellKnownCarriers.AMAZON_LOGISTICS, "TBA1")
    assertEquals("Amazon Logistics", scanRows(listOf(ev), h, TimeZone.UTC)[3].single().carrier)
    assertNull(scanRows(listOf(ev), null, TimeZone.UTC)[3].single().carrier)
}

@Test fun a_handed_off_parcel_shows_the_label_and_the_second_number() = runTest {
    val p = base(TrackingStatus.IN_TRANSIT, LocalDate(2026, 7, 14)).copy(
        carrier = WellKnownCarriers.AMAZON, trackingNumber = "112-0087174-7649828",
        handoff = Handoff(WellKnownCarriers.AMAZON_LOGISTICS, "TBA335777783614"))
    vm(p)
    db.parcelDao().upsertParcel(p.toEntity())
    val s = awaitState { it.loaded && it.secondary != null }
    assertEquals("Amazon via Amazon Logistics", s.carrierLabel)
    assertEquals("Amazon", s.carrierName)
    assertEquals(SecondaryNumberUi("Amazon Logistics", "TBA335777783614"), s.secondary)
}
```

`ListViewModelTest`: one test that a parcel with a handoff yields `carrierName == "Amazon via Amazon Logistics"` on its card, in the file's existing style.

- [ ] **Step 2: Run to verify failure** — `./gradlew :domain:jvmTest :ui:testAndroidHostTest --tests '*DetailViewModelTest*' --tests '*ListViewModelTest*' --tests '*DisplayTest*'` → compilation failures.

- [ ] **Step 3: Implement**

`DetailViewModel.toDetail`: build `scanRows(events, handoff, tz)` once and pass `scans = rows[i]` into each `TimelineStepUi`; set `carrierLabel`, `secondary`. `DetailScreen`: header uses `state.carrierLabel`; under each step's label/time, `step.scans.forEach { Row { Text(it.text, 12.sp, ShipColors.muted); it.carrier?.let { Text(it, 10.sp, ShipColors.faint) } } }`; after the tracking-number card, `state.secondary?.let { SecondaryNumberUi -> DetailCard { CardLabel("${it.carrierName} tracking number"); Text(it.trackingNumber, mono) } }` (extract the number text + long-press copy into a private `TrackingNumberText(number)` composable used by both cards). Add one preview `Preview_DetailContent_Handoff` with scans and a secondary number. `ListViewModel.toCard`: `carrierName = carrierLabel`.

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `[detail] Show every scan under its step, the delivering carrier, and its number`

---

### Task 9: Full suite, device QA, wrap-up

- [ ] **Step 1:** `./gradlew jvmTest testAndroidHostTest --continue` → green; report any failure by name.
- [ ] **Step 2:** Install the debug build on the Pixel (`./gradlew :androidApp:installDebug` or the project's equivalent) and force a refresh (`adb shell cmd jobscheduler run -f -u 0 -n androidx.work.systemjobscheduler com.dgmltn.shiphappens <jobId>`; job id via `dumpsys jobscheduler | grep DailyRefreshWorker`). Watch `adb logcat -s ShipScrape` for the AMZL fetch of the TBA number. Screenshot the light-bulbs detail: label, second number, scans under the rail.
- [ ] **Step 3:** Reinstall clean `main` on every touched device.
- [ ] **Step 4:** Update memory (`amazon-webview-source-status.md` or a new `secondary-carrier-status.md`, plus MEMORY.md).
- [ ] **Step 5:** Squash-merge into `main` with a fresh message, delete the branch, stop before pushing.
