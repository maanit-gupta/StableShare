package com.maanit.stableshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.maanit.stableshare.engine.ensureRunning
import com.maanit.stableshare.ui.LocalNetworkPermission
import com.maanit.stableshare.ui.LocalPermissionRequester
import com.maanit.stableshare.ui.PermissionRequester
import com.maanit.stableshare.ui.StableShareRoot
import com.maanit.stableshare.ui.theme.StableShareTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single activity. Installs the system splash (dismissed on the first Compose frame), draws
 * edge to edge with dark system-bar icons, owns the runtime-permission launcher and forwards
 * notification taps to the navigation graph.
 */
class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<Intent?>(null)
    private var permissionCallback: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[LocalNetworkPermission.PERMISSION] == true) {
            // Anything that failed or waited for want of the permission can try again.
            (application as StableShareApp).container.scheduler.ensureRunning()
        }
        permissionCallback?.invoke()
        permissionCallback = null
    }

    private val permissionRequester = PermissionRequester { includeNotifications, onDone ->
        val missing = buildList {
            if (!LocalNetworkPermission.isGranted(this@MainActivity)) add(LocalNetworkPermission.PERMISSION)
            if (includeNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) {
            onDone()
        } else {
            permissionCallback = onDone
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val container = (application as StableShareApp).container
        val fromLauncher = intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        val showSplash = savedInstanceState == null && fromLauncher && !container.session.splashShown
        if (savedInstanceState == null) openRequests.value = intent
        setContent {
            StableShareTheme {
                CompositionLocalProvider(LocalPermissionRequester provides permissionRequester) {
                    StableShareRoot(container, showSplash, openRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequests.value = intent
    }
}
