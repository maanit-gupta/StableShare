package com.maanit.stableshare.ui

import android.content.Intent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maanit.stableshare.di.AppContainer
import com.maanit.stableshare.ui.components.NeutralSnackbarHost
import com.maanit.stableshare.ui.detail.DetailScreen
import com.maanit.stableshare.ui.detail.DetailViewModel
import com.maanit.stableshare.ui.nav.AppBottomBar
import com.maanit.stableshare.ui.nav.Routes
import com.maanit.stableshare.ui.onboarding.OnboardingScreen
import com.maanit.stableshare.ui.splash.SplashScreen
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Mint
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import com.maanit.stableshare.ui.transfers.DownloadSheet
import com.maanit.stableshare.ui.transfers.DownloadSheetViewModel
import com.maanit.stableshare.ui.transfers.TransfersScreen
import com.maanit.stableshare.ui.transfers.TransfersViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Extras on the notification PendingIntents (UI-SPEC §5.12). */
object AppIntents {
    const val EXTRA_TRANSFER_ID = "com.maanit.stableshare.TRANSFER_ID"
    const val EXTRA_OPEN_TRANSFERS = "com.maanit.stableshare.OPEN_TRANSFERS"
}

/**
 * The whole app: one Scaffold (bottom bar on Transfers/History/Settings, the snackbar host above
 * it) around one NavHost. [showSplash] is true only on a cold start from the launcher; [openRequests]
 * carries notification taps (initial and onNewIntent).
 */
@Composable
fun StableShareRoot(
    container: AppContainer,
    showSplash: Boolean,
    openRequests: MutableStateFlow<Intent?>,
    navController: NavHostController = rememberNavController(),
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val reduced = LocalReducedMotion.current
    val permissions = LocalPermissionRequester.current

    fun openTransfers(clear: Boolean) = navController.navigate(Routes.TRANSFERS) {
        if (clear) popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }

    // Navigation must happen on the main thread; DataStore resumes us on its own.
    fun afterIntro() {
        scope.launch {
            val done = container.settingsRepository.current().onboardingCompleted
            withContext(Dispatchers.Main) {
                navController.navigate(if (done) Routes.TRANSFERS else Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    // Notification taps: Detail for a transfer, Transfers for the ongoing notification.
    LaunchedEffect(navController) {
        openRequests.collect { intent ->
            if (intent == null) return@collect
            openRequests.value = null
            if (navController.currentDestination?.route == Routes.ENTRY) return@collect // the entry route handles the first one
            val id = intent.getStringExtra(AppIntents.EXTRA_TRANSFER_ID)
            when {
                id != null -> navController.navigate(Routes.detail(id)) { launchSingleTop = true }
                intent.getBooleanExtra(AppIntents.EXTRA_OPEN_TRANSFERS, false) -> openTransfers(clear = false)
            }
        }
    }

    CompositionLocalProvider(LocalAppContainer provides container, LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = Neutral.colors.page,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { NeutralSnackbarHost(snackbar) },
            bottomBar = {
                if (route in Routes.withBottomBar) {
                    AppBottomBar(route) { target ->
                        navController.navigate(target) {
                            popUpTo(Routes.TRANSFERS) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController,
                startDestination = Routes.ENTRY,
                modifier = Modifier.fillMaxSize().padding(padding),
                enterTransition = { if (reduced) EnterTransition.None else fadeIn(Motion.standard()) },
                exitTransition = { if (reduced) ExitTransition.None else fadeOut(Motion.standard()) },
            ) {
                composable(Routes.ENTRY) {
                    Box(Modifier.fillMaxSize().background(if (showSplash) Mint.colors.bg else Neutral.colors.page))
                    LaunchedEffect(Unit) {
                        val pending = openRequests.value
                        openRequests.value = null
                        val id = pending?.getStringExtra(AppIntents.EXTRA_TRANSFER_ID)
                        when {
                            showSplash -> {
                                container.session.splashShown = true
                                navController.navigate(Routes.SPLASH) { popUpTo(Routes.ENTRY) { inclusive = true } }
                            }
                            id != null -> {
                                openTransfers(clear = true)
                                navController.navigate(Routes.detail(id))
                            }
                            else -> afterIntro()
                        }
                    }
                }
                composable(Routes.SPLASH) { SplashScreen(onFinished = ::afterIntro) }
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(onFinish = {
                        container.session.permissionsAsked = true
                        permissions.request(includeNotifications = true) {
                            scope.launch {
                                container.settingsRepository.setOnboardingCompleted(true)
                                withContext(Dispatchers.Main) { openTransfers(clear = true) }
                            }
                        }
                    })
                }
                composable(Routes.TRANSFERS) {
                    TransfersRoute(container, navController)
                }
                composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = requireNotNull(entry.arguments?.getString("id"))
                    val vm = appViewModel(key = "detail-$id") {
                        DetailViewModel(
                            id = id,
                            repo = transferRepository,
                            tracker = progressTracker,
                            restored = transferEngine.restoredIds,
                            settings = settingsRepository.settings,
                            controller = transferController,
                            isGenerated = { fileStore.isGeneratedFile(it.localUri) },
                            appScope = applicationScope,
                        )
                    }
                    DetailScreen(vm, onBack = { if (!navController.popBackStack()) openTransfers(clear = true) })
                }
            }
        }
    }
}

@Composable
private fun TransfersRoute(container: AppContainer, navController: NavHostController) {
    val permissions = LocalPermissionRequester.current
    // ACCESS_LOCAL_NETWORK is asked again once per process if it is still missing.
    LaunchedEffect(Unit) {
        if (!container.session.permissionsAsked) {
            container.session.permissionsAsked = true
            permissions.request(includeNotifications = false) {}
        }
    }
    val vm = appViewModel {
        TransfersViewModel(
            repo = transferRepository,
            tracker = progressTracker,
            restored = transferEngine.restoredIds,
            settings = settingsRepository.settings,
            isOnline = connectivityMonitor.isOnline,
            health = { protocolClient.health().ok },
            controller = transferController,
            isGenerated = { fileStore.isGeneratedFile(it.localUri) },
            appScope = applicationScope,
        )
    }
    TransfersScreen(
        vm = vm,
        onOpenDetail = { navController.navigate(Routes.detail(it)) },
        onOpenUpload = { navController.navigate(Routes.UPLOAD) },
        onOpenSettings = { section -> navController.navigate(Routes.settings(if (section) Routes.SECTION_TRANSFERS else null)) },
        downloadSheet = { onDismiss ->
            val sheetVm = appViewModel {
                DownloadSheetViewModel(
                    listFiles = { protocolClient.listFiles() },
                    startDownload = { transferController.download(it) },
                    repo = transferRepository,
                    settings = settingsRepository.settings,
                    classifier = errorClassifier,
                    appScope = applicationScope,
                )
            }
            DownloadSheet(sheetVm, onDismiss = onDismiss, onOpenSettings = {
                onDismiss()
                navController.navigate(Routes.settings())
            })
        },
    )
}
