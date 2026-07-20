package com.dgmltn.shiphappens.android

import android.app.Application
import com.dgmltn.shiphappens.ui.di.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ShipHappensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ShipHappensApplication)
            modules(appModules())
        }
    }
}
