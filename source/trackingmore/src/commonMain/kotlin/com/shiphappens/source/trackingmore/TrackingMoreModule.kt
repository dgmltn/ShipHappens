package com.shiphappens.source.trackingmore

import com.shiphappens.source.api.TrackingSource
import io.ktor.client.engine.HttpClientEngine
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

expect fun trackingMoreEngine(): HttpClientEngine

val trackingMoreSourceModule: Module = module {
    single { TrackingMoreSource(trackingMoreEngine(), get()) } bind TrackingSource::class
}
