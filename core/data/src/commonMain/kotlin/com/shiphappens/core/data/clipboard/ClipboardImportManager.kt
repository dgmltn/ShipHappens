package com.shiphappens.core.data.clipboard

import com.shiphappens.core.data.db.ParcelDao
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.normalizeTracking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

interface ClipboardReader {
    suspend fun readText(): String?
}

data class PendingImport(val carrier: Carrier, val trackingNumber: String)

class ClipboardImportManager(
    private val reader: ClipboardReader,
    private val registry: SourceRegistry,
    private val dao: ParcelDao,
    private val settings: SettingsRepository,
) {
    private val _pending = MutableStateFlow<PendingImport?>(null)
    val pending: StateFlow<PendingImport?> = _pending
    private var dismissedNorm: String? = null

    suspend fun checkClipboard() {
        if (!settings.settings.first().autoClipboardImport) return
        val text = reader.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val carrier = registry.detectCarrier(text) ?: return
        val norm = normalizeTracking(text)
        if (norm == dismissedNorm) return
        if (dao.normalizedNumbers().contains(norm)) return
        _pending.value = PendingImport(carrier, text)
    }

    fun dismiss() {
        dismissedNorm = _pending.value?.let { normalizeTracking(it.trackingNumber) } ?: dismissedNorm
        _pending.value = null
    }
}
