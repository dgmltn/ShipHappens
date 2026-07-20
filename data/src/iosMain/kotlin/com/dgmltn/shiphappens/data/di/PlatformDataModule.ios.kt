package com.dgmltn.shiphappens.data.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.clipboard.ClipboardReader
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
// Note: kotlinx.coroutines 1.11.0 keeps `Dispatchers.IO` internal on Kotlin/Native
// targets (unlike JVM/Android), so this actual uses `Dispatchers.Default` instead.
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIPasteboard

@OptIn(ExperimentalForeignApi::class)
private fun documentsDir(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory, inDomain = NSUserDomainMask,
        appropriateForURL = null, create = true, error = null,
    )
    return requireNotNull(url?.path)
}

private class IosClipboardReader : ClipboardReader {
    override suspend fun readText(): String? {
        val pb = UIPasteboard.generalPasteboard
        if (!pb.hasStrings) return null  // avoids the paste prompt when there's no text
        return pb.string
    }
}

actual fun platformDataModule(): Module = module {
    single { PreferenceDataStoreFactory.createWithPath { "${documentsDir()}/shiphappens.preferences_pb".toPath() } }
    single {
        Room.databaseBuilder<ShipHappensDb>("${documentsDir()}/shiphappens.db")
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()
    }
    single<ClipboardReader> { IosClipboardReader() }
}
