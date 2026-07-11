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
    private var lastArchivedId: String? = null
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
        combine(content, pendingName, toast, refreshing) { c, pName, t, r ->
            val parcels = if (c.tab == ListTab.ACTIVE) c.active else c.archived
            val arriving = c.active.count { it.status != TrackingStatus.DELIVERED }
            ListUiState(
                dateLabel = clock.today().designFormat(),
                headerSub = if (c.tab == ListTab.ACTIVE) "$arriving arriving soon"
                    else "${c.archived.size} package${if (c.archived.size == 1) "" else "s"} archived",
                tab = c.tab,
                cards = parcels.map { it.toCard(c.tab) },
                emptyText = if (parcels.isNotEmpty()) null
                    else if (c.tab == ListTab.ACTIVE) "No active deliveries right now."
                    else "Nothing archived yet. Swipe a delivered package left to archive it.",
                pendingImport = if (c.tab == ListTab.ACTIVE) c.pending?.let {
                    PendingImportUi(it.carrier.displayName, it.carrier.accentHex(), it.trackingNumber, pName)
                } else null,
                manualAdd = c.manual,
                toast = t,
                isRefreshing = r,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private fun Parcel.toCard(tab: ListTab): ParcelCardUi {
        val delivered = status == TrackingStatus.DELIVERED
        val days = etaDate?.let { clock.today().daysUntil(it) }
        val urgent = !delivered && days != null && days <= 1
        val statusText = if (!delivered && days != null && days <= 0 && status != TrackingStatus.EXCEPTION)
            "Out for delivery today" else STATUS_TEXT.getValue(status)
        return ParcelCardUi(
            id = id, name = name, carrierName = carrier.displayName, accentHex = carrier.accentHex(),
            statusText = statusText, delivered = delivered,
            swipeable = delivered && tab == ListTab.ACTIVE,
            showRestore = tab == ListTab.ARCHIVED,
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
        lastArchivedId = id
        viewModelScope.launch { repository.archive(id); flash("Package archived", undo = true, ms = 3_800) }
    }
    fun onUndo() {
        val id = lastArchivedId ?: return
        toastJob?.cancel(); toast.value = null
        viewModelScope.launch { repository.restore(id) }
    }
    fun onRestore(id: String) { viewModelScope.launch { repository.restore(id) } }

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
