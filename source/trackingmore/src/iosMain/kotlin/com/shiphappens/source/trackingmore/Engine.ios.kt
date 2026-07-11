package com.shiphappens.source.trackingmore

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

actual fun trackingMoreEngine(): HttpClientEngine = Darwin.create()
