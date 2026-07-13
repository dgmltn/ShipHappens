package com.shiphappens.ui.di

import com.shiphappens.data.di.dataModule
import com.shiphappens.data.di.platformDataModule
import com.shiphappens.source.demo.demoSourceModule
import com.shiphappens.source.fedex.fedexSourceModule
import com.shiphappens.source.ups.upsSourceModule
import com.shiphappens.source.usps.uspsSourceModule
import com.shiphappens.source.webview.di.platformWebModule
import com.shiphappens.ui.detail.DetailViewModel
import com.shiphappens.ui.list.ListViewModel
import com.shiphappens.ui.settings.SettingsViewModel
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
    viewModel { params -> com.shiphappens.ui.web.WebDetailViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> com.shiphappens.ui.web.WebLoginViewModel(params.get(), get(), get(), get()) }
}

/** Adding a tracking source = implement TrackingSource in a new module + add its Koin module here. */
fun appModules(): List<Module> = listOf(
    platformDataModule(),
    platformWebModule(),
    dataModule,
    demoSourceModule,
    upsSourceModule,
    uspsSourceModule,
    fedexSourceModule,
    uiModule,
)

/** iOS bootstrap (Android calls startKoin itself to attach androidContext). */
fun initKoin() {
    startKoin { modules(appModules()) }
}
