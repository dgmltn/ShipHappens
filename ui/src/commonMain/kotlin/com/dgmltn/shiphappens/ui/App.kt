package com.dgmltn.shiphappens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.dgmltn.shiphappens.ui.detail.DetailScreen
import com.dgmltn.shiphappens.ui.list.ListScreen
import com.dgmltn.shiphappens.ui.navigation.*
import com.dgmltn.shiphappens.ui.settings.SettingsScreen
import com.dgmltn.shiphappens.ui.web.WebDetailScreen
import com.dgmltn.shiphappens.ui.web.WebLoginScreen
import com.dgmltn.shiphappens.design.ShipTheme

// Navigation 3 API-drift note: the installed runtime (1.1.4) only exposes an unconfigured
// `rememberNavBackStack(vararg NavKey)` overload from androidMain (RememberNavBackStack.android.kt,
// reflection-based); the overload visible from commonMain requires a SavedStateConfiguration with
// a SerializersModule registering every NavKey subtype. Rather than wire that up, this uses
// NavBackStack's public `vararg` constructor directly under `remember` — the same
// Compose-state-backed (SnapshotStateList) back stack, just without save/restore across process
// death. `entry<T>` is a reified member of EntryProviderScope<T>, not a top-level function, so it
// is used unqualified inside the `entryProvider { }` builder block (no separate import exists).
@Composable
fun App(deepLink: DeepLink? = null, onDeepLinkHandled: () -> Unit = {}) {
    ShipTheme {
        StatusBarIconsEffect()
        val backStack = remember { NavBackStack<NavKey>(ListRoute) }
        LaunchedEffect(deepLink) {
            when (deepLink) {
                // Pushed onto whatever is showing: a tapped notification should reveal the
                // parcel without discarding where the user already was.
                is DeepLink.Parcel -> backStack.add(DetailRoute(deepLink.parcelId))
                is DeepLink.SignIn -> backStack.add(WebLoginRoute(deepLink.sourceId))
                null -> return@LaunchedEffect
            }
            onDeepLinkHandled()
        }
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<ListRoute> {
                    ListScreen(
                        onOpenDetail = { id -> backStack.add(DetailRoute(id)) },
                        onOpenSettings = { backStack.add(SettingsRoute) },
                    )
                }
                entry<DetailRoute> { route ->
                    DetailScreen(
                        route.parcelId,
                        onBack = { backStack.removeLastOrNull() },
                        onOpenWeb = { backStack.add(WebDetailRoute(route.parcelId)) },
                    )
                }
                entry<WebDetailRoute> { route -> WebDetailScreen(route.parcelId, onBack = { backStack.removeLastOrNull() }) }
                entry<SettingsRoute> {
                    SettingsScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onOpenLogin = { sourceId -> backStack.add(WebLoginRoute(sourceId)) },
                    )
                }
                entry<WebLoginRoute> { route -> WebLoginScreen(route.sourceId, onBack = { backStack.removeLastOrNull() }) }
            },
        )
    }
}
