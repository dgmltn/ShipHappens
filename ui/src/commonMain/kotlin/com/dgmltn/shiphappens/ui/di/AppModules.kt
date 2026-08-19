package com.dgmltn.shiphappens.ui.di

import com.dgmltn.shiphappens.data.di.dataModule
import com.dgmltn.shiphappens.data.di.platformDataModule
import com.dgmltn.shiphappens.source.amazon.amazonSourceModule
import com.dgmltn.shiphappens.source.amzl.amzlSourceModule
import com.dgmltn.shiphappens.source.fedex.fedexSourceModule
import com.dgmltn.shiphappens.source.ups.upsSourceModule
import com.dgmltn.shiphappens.source.usps.uspsSourceModule
import com.dgmltn.shiphappens.source.webview.di.platformWebModule
import com.dgmltn.shiphappens.ui.detail.DetailViewModel
import com.dgmltn.shiphappens.ui.list.ListViewModel
import com.dgmltn.shiphappens.ui.settings.SettingsViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
// Koin 4.2 moved the viewModel DSL builders here (the brief cites
// org.koin.core.module.dsl.viewModelOf / .viewModel; both live in this package).
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val uiModule = module {
    viewModelOf(::ListViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel { params -> DetailViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> com.dgmltn.shiphappens.ui.web.WebDetailViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> com.dgmltn.shiphappens.ui.web.WebLoginViewModel(params.get(), get(), get(), get()) }
}

/** Adding a tracking source = implement TrackingSource in a new module + add its Koin module here. */
fun appModules(): List<Module> = listOf(
    platformDataModule(),
    platformWebModule(),
    dataModule,
    upsSourceModule,
    uspsSourceModule,
    amazonSourceModule,
    amzlSourceModule,
    fedexSourceModule,
    uiModule,
)

/** iOS bootstrap (Android calls startKoin itself to attach androidContext). */
fun initKoin() {
    startKoin { modules(appModules()) }
}
