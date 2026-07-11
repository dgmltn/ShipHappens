package com.shiphappens.core.data.di

import com.shiphappens.core.data.*
import com.shiphappens.core.data.clipboard.ClipboardImportManager
import com.shiphappens.core.data.db.ShipHappensDb
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.source.api.SourceConfigProvider
import com.shiphappens.source.api.TrackingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

val coreDataModule = module {
    single<AppClock> { SystemClock() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SettingsRepository(get()) } bind SourceConfigProvider::class
    single { SourceRegistry(getAll<TrackingSource>(), get()) }
    single { get<ShipHappensDb>().parcelDao() }
    single { ParcelRepository(get(), get(), get(), get()) }
    single { RefreshCoordinator(get(), get()) }
    single { ClipboardImportManager(get(), get(), get(), get()) }
}

/** Provides DataStore<Preferences>, ShipHappensDb, ClipboardReader per platform. */
expect fun platformDataModule(): Module
