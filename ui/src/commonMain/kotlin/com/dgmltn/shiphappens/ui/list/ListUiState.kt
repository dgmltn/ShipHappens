package com.dgmltn.shiphappens.ui.list

enum class ListTab { ACTIVE, ARCHIVED }

data class RingUi(val number: Int, val fraction: Float)

data class ParcelCardUi(
    val id: String,
    val name: String,
    val carrierName: String,
    val accentHex: String,
    val statusText: String,
    val delivered: Boolean,
    val ring: RingUi?,
    val urgent: Boolean,
    val refreshing: Boolean = false,
    /** True when no enabled source will refresh this parcel (its source is toggled off). */
    val sourceless: Boolean = false,
)

data class PendingImportUi(
    val carrierName: String,
    val accentHex: String,
    val tracking: String,
    val name: String,
)

data class CarrierOption(val code: String?, val label: String, val accentHex: String?)

data class ManualAddUi(
    val name: String = "",
    val tracking: String = "",
    val pickedCarrierCode: String? = null,
    val pickerOpen: Boolean = false,
    val effectiveCarrierName: String? = null,
    val effectiveAccentHex: String? = null,
    val options: List<CarrierOption> = emptyList(),
)

data class ToastUi(val message: String, val showUndo: Boolean = false)

data class ListUiState(
    val dateLabel: String = "",
    val headerSub: String = "",
    val tab: ListTab = ListTab.ACTIVE,
    val cards: List<ParcelCardUi> = emptyList(),
    val emptyText: String? = null,
    val pendingImport: PendingImportUi? = null,
    val manualAdd: ManualAddUi = ManualAddUi(),
    val toast: ToastUi? = null,
    val isRefreshing: Boolean = false,
)
