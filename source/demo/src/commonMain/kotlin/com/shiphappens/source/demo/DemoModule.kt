package com.shiphappens.source.demo

import com.shiphappens.source.api.TrackingSource
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

val demoSourceModule: Module = module {
    single {
        DemoSource(
            today = { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date },
            now = { Clock.System.now() },
        )
    } bind TrackingSource::class
}
