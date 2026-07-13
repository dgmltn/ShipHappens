package com.shiphappens.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ListRoute : NavKey
@Serializable data class DetailRoute(val parcelId: String) : NavKey
@Serializable data class WebDetailRoute(val parcelId: String) : NavKey
@Serializable data object SettingsRoute : NavKey
