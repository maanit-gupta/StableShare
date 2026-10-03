package com.maanit.stableshare.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import com.maanit.stableshare.di.AppContainer

/** The process's dependency container, provided by MainActivity (and by tests). */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("no AppContainer provided") }

/** The one snackbar host, shown above the bottom bar where there is one (UI-SPEC §5.11). */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no SnackbarHostState provided") }

/**
 * Asks for the runtime permissions the app needs (POST_NOTIFICATIONS on 13+, ACCESS_LOCAL_NETWORK
 * on 17+) and calls back whatever the answer. Tests substitute an immediate callback.
 */
fun interface PermissionRequester {
    /** [includeNotifications] = false asks only for the local-network permission. */
    fun request(includeNotifications: Boolean, onDone: () -> Unit)
}

val LocalPermissionRequester = staticCompositionLocalOf<PermissionRequester> { PermissionRequester { _, done -> done() } }
