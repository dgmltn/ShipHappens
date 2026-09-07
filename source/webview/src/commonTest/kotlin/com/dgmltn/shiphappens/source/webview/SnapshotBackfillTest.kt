package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnapshotBackfillTest {
    private val coarse = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 21), etaWindowEnd = LocalTime(20, 0), delayNote = "Running late")

    @Test fun unknown_status_and_nulls_are_filled_from_coarse() {
        val filled = TrackingSnapshot(TrackingStatus.UNKNOWN).backfilledFrom(coarse)
        assertEquals(TrackingStatus.IN_TRANSIT, filled.status)
        assertEquals(LocalDate(2026, 7, 21), filled.etaDate)
        assertEquals(LocalTime(20, 0), filled.etaWindowEnd)
        assertEquals("Running late", filled.delayNote)
    }

    @Test fun rich_fields_win() {
        val rich = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, etaDate = LocalDate(2026, 7, 20)).backfilledFrom(coarse)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, rich.status)
        assertEquals(LocalDate(2026, 7, 20), rich.etaDate)
    }

    @Test fun no_coarse_is_a_no_op() {
        val rich = TrackingSnapshot(TrackingStatus.UNKNOWN)
        assertEquals(rich, rich.backfilledFrom(null))
        assertNull(rich.etaDate)
    }
}
