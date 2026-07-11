package com.shiphappens.core.data.di

import android.content.ClipboardManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.clipboard.ClipboardReader
import com.shiphappens.core.data.db.ShipHappensDb
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private class AndroidClipboardReader(private val context: Context) : ClipboardReader {
    override suspend fun readText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }
}

actual fun platformDataModule(): Module = module {
    single {
        PreferenceDataStoreFactory.createWithPath {
            androidContext().filesDir.resolve("shiphappens.preferences_pb").absolutePath.toPath()
        }
    }
    single {
        Room.databaseBuilder<ShipHappensDb>(
            androidContext(),
            androidContext().getDatabasePath("shiphappens.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }
    single<ClipboardReader> { AndroidClipboardReader(androidContext()) }
}
