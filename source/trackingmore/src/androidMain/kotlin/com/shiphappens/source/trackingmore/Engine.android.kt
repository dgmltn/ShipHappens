package com.shiphappens.source.trackingmore

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

actual fun trackingMoreEngine(): HttpClientEngine = OkHttp.create()
