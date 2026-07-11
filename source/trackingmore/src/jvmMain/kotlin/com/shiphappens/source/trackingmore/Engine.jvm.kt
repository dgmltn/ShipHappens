package com.shiphappens.source.trackingmore

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java

actual fun trackingMoreEngine(): HttpClientEngine = Java.create()
