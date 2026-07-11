package com.shiphappens.source.demo

import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod

private data class DemoEntry(
    val name: String, val tracking: String, val carrier: Carrier,
    val step: Int,           // 0..4 as in the design (4 = delivered)
    val etaOffsetDays: Int,  // relative to "today"
    val time: LocalTime,
)

// The design's seven sample parcels, dates made relative to today.
private fun entries(): List<DemoEntry> = listOf(
    DemoEntry("Baseball cap", "1ZW463200377332024", WellKnownCarriers.USPS, 2, 2, LocalTime(20, 0)),
    DemoEntry("Trail running shoes", "FX 8823 0199 4422", WellKnownCarriers.FEDEX, 3, 1, LocalTime(21, 0)),
    DemoEntry("Mechanical keyboard", "1Z 999 AA1 01 2345 6784", WellKnownCarriers.UPS, 2, 5, LocalTime(20, 0)),
    DemoEntry("Ceramic desk lamp", "1Z 88E 033 03 9876 5432", WellKnownCarriers.UPS, 3, 0, LocalTime(20, 0)),
    DemoEntry("Clear phone case", "9400 1118 9922 3300 1122", WellKnownCarriers.USPS, 1, 4, LocalTime(20, 0)),
    DemoEntry("Oat-blend coffee beans", "9400 1118 9922 3197 4284", WellKnownCarriers.USPS, 4, -2, LocalTime(14, 14)),
    DemoEntry("Paperback — The Overstory", "FX 7711 2058 3366", WellKnownCarriers.FEDEX, 4, -1, LocalTime(11, 42)),
)

private val STEP_STATUS = listOf(
    TrackingStatus.LABEL_CREATED, TrackingStatus.SHIPPED, TrackingStatus.IN_TRANSIT,
    TrackingStatus.OUT_FOR_DELIVERY, TrackingStatus.DELIVERED,
)
private val STEP_LABEL = listOf("Label created", "Shipped", "In transit", "Out for delivery", "Delivered")
private val STEP_LOCATION = listOf("Origin facility", "Departed origin", "Memphis, TN", "On vehicle for delivery", "Front porch")

class DemoSource(
    private val today: () -> LocalDate,
    private val now: () -> Instant,
) : TrackingSource, SeedingSource {

    override val descriptor = SourceDescriptor(
        id = "demo", displayName = "Demo data", kind = SourceKind.UNIVERSAL,
        accentColorHex = "#17150F", configSpec = emptyList(),
    )

    override fun seeds(): List<SeedParcel> = entries().map { SeedParcel(it.name, it.tracking, it.carrier) }

    override fun detectCarrier(trackingNumber: String): Carrier? =
        entries().firstOrNull { normalizeTracking(it.tracking) == normalizeTracking(trackingNumber) }?.carrier

    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val e = entries().firstOrNull { normalizeTracking(it.tracking) == normalizeTracking(trackingNumber) }
            ?: return SourceResult.Failure(FailureReason.NOT_FOUND, "Not a demo parcel")
        val events = (0..e.step).map { i ->
            TrackingEvent(
                timestamp = now() - (e.step - i).days,
                description = STEP_LABEL[i],
                location = STEP_LOCATION[i],
                status = STEP_STATUS[i],
            )
        }
        return SourceResult.Success(
            TrackingSnapshot(
                status = STEP_STATUS[e.step],
                events = events,
                etaDate = today().plus(DatePeriod(days = e.etaOffsetDays)),
                etaTime = e.time,
                latestLocation = STEP_LOCATION[e.step],
            )
        )
    }

    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> = SourceResult.Success(Unit)
}
