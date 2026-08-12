package com.dgmltn.shiphappens.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dgmltn.shiphappens.data.*
import com.dgmltn.shiphappens.data.clipboard.ClipboardImportManager
import com.dgmltn.shiphappens.data.clipboard.PendingImport
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.BuiltInCarrierDetection
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.FailureReason
import com.dgmltn.shiphappens.source.api.SourceConfig
import com.dgmltn.shiphappens.design.accentHex
import com.dgmltn.shiphappens.ui.util.TRACKING_STEP_LABELS
import com.dgmltn.shiphappens.ui.util.designFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.daysUntil

private sealed interface PendingUndo {
    data class FlagFlip(val reverse: suspend () -> Unit) : PendingUndo
    data class PendingDelete(val id: String, val job: Job) : PendingUndo
}

class ListViewModel(
    private val repository: ParcelRepository,
    private val clipboard: ClipboardImportManager,
    private val coordinator: RefreshCoordinator,
    private val clock: AppClock,
    private val registry: SourceRegistry,
    settingsRepository: SettingsRepository,
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
        val refreshingIds: Set<String> = emptySet(),
        val sourceConfigs: Map<String, SourceConfig> = emptyMap(),
    )

    private val content = combine(
        tab, repository.observeParcels(false), repository.observeParcels(true),
        clipboard.pending, manual,
    ) { t, act, arc, pend, man -> Content(t, act, arc, pend, man) }
        .combine(repository.refreshingIds) { c, ids -> c.copy(refreshingIds = ids) }
        // Live source-config view, so the sourceless badge clears the moment a source is enabled.
        .combine(settingsRepository.settings) { c, s -> c.copy(sourceConfigs = s.sourceConfigs) }

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
                cards = parcels.map {
                    it.toCard(
                        refreshing = it.id in c.refreshingIds,
                        sourceless = registry.sourceFor(it, c.sourceConfigs) == null,
                    )
                },
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

    private fun Parcel.toCard(refreshing: Boolean, sourceless: Boolean): ParcelCardUi {
        val delivered = status == TrackingStatus.DELIVERED
        val days = etaDate?.let { clock.today().daysUntil(it) }
        val urgent = !delivered && days != null && days <= 1
        // Same step the detail timeline marks CURRENT, so the two screens never disagree.
        val statusText = when {
            status == TrackingStatus.EXCEPTION -> "Delivery exception"
            lastRefreshedAt == null && status == TrackingStatus.UNKNOWN -> "Waiting for first update"
            else -> TRACKING_STEP_LABELS[effectiveStepIndex]
        }
        return ParcelCardUi(
            id = id, name = name, carrierName = carrier.displayName, accentHex = carrier.accentHex(),
            statusText = statusText, delivered = delivered,
            // No ring until we have an ETA — a never-refreshed parcel has nothing to count down.
            ring = if (delivered || days == null) null else RingUi(
                number = days.coerceAtLeast(0),
                fraction = effectiveStepIndex / 4f,
            ),
            urgent = urgent,
            refreshing = refreshing,
            sourceless = sourceless,
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
            when (val result = repository.addParcel(m.name, m.tracking, carrier)) {
                is AddResult.Added -> {
                    onClearManual(); flash("Delivery added")
                    repository.refresh(result.parcel.id)  // best effort; failure leaves status UNKNOWN
                }
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
            when (val result = repository.addParcel(pendingName.value, p.trackingNumber, p.carrier)) {
                is AddResult.Added -> {
                    clipboard.dismiss(); pendingName.value = ""; flash("Delivery added")
                    repository.refresh(result.parcel.id)  // best effort; failure leaves status UNKNOWN
                }
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
