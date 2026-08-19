package com.dgmltn.shiphappens.data.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.clipboard.ClipboardReader
import com.dgmltn.shiphappens.data.daily.*
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformDataModule(): Module = module {
    single {
        val dir = System.getProperty("java.io.tmpdir")
        PreferenceDataStoreFactory.createWithPath { "$dir/shiphappens.preferences_pb".toPath() }
    }
    single {
        Room.inMemoryDatabaseBuilder<ShipHappensDb>()
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }
    single<ClipboardReader> { object : ClipboardReader { override suspend fun readText(): String? = null } }
    single<StatusNotifier> { NoOpStatusNotifier }
    single<DailyRefreshScheduler> { NoOpDailyRefreshScheduler }
}
