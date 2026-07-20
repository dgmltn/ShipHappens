package com.dgmltn.shiphappens.data.di

import com.dgmltn.shiphappens.data.*
import com.dgmltn.shiphappens.data.clipboard.ClipboardImportManager
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.source.api.TrackingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

val dataModule = module {
    single<AppClock> { SystemClock() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SettingsRepository(get()) }
    single { SourceRegistry(getAll<TrackingSource>(), get()) }
    single { get<ShipHappensDb>().parcelDao() }
    single { ParcelRepository(get(), get(), get(), get()) }
    single { RefreshCoordinator(get(), get()) }
    single { ClipboardImportManager(get(), get(), get(), get()) }
}

/** Provides DataStore<Preferences>, ShipHappensDb, ClipboardReader per platform. */
expect fun platformDataModule(): Module
