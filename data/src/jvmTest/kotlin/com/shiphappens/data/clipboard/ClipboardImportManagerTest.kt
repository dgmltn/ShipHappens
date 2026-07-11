package com.shiphappens.data.clipboard

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.db.toEntity
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

class FakeClipboard(var text: String?) : ClipboardReader {
    override suspend fun readText(): String? = text
}

class ClipboardImportManagerTest {
    private lateinit var settings: SettingsRepository
    private lateinit var db: ShipHappensDb

    private fun manager(scope: CoroutineScope, clip: FakeClipboard): ClipboardImportManager {
        val dir = kotlin.io.path.createTempDirectory("clip").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ClipboardImportManager(clip, SourceRegistry(emptyList(), settings), db.parcelDao(), settings)
    }

    @Test fun detects_tracking_number_on_clipboard() = runTest {
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), FakeClipboard("1Z 999 AA1 01 2345 6784"))
        m.checkClipboard()
        assertEquals(WellKnownCarriers.UPS, m.pending.value?.carrier)
    }

    @Test fun ignores_garbage_and_respects_setting() = runTest {
        val clip = FakeClipboard("see you at 5pm!")
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), clip)
        m.checkClipboard()
        assertNull(m.pending.value)
        clip.text = "1Z999AA10123456784"
        settings.setAutoClipboardImport(false)
        m.checkClipboard()
        assertNull(m.pending.value)
    }

    @Test fun dedupes_against_existing_parcels() = runTest {
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), FakeClipboard("1Z999AA10123456784"))
        db.parcelDao().upsertParcel(
            Parcel(id = "x", name = "Existing", trackingNumber = "1Z 999AA101 23456784",
                   carrier = WellKnownCarriers.UPS, createdAt = Instant.fromEpochMilliseconds(0)).toEntity()
        )
        m.checkClipboard()
        assertNull(m.pending.value)
    }

    @Test fun dismissal_is_remembered() = runTest {
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), FakeClipboard("1Z999AA10123456784"))
        m.checkClipboard()
        assertNotNull(m.pending.value)
        m.dismiss()
        m.checkClipboard()
        assertNull(m.pending.value)
    }
}
