package com.maanit.stableshare

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maanit.stableshare.ui.LocalNetworkPermission
import com.maanit.stableshare.ui.debug.DebugScreen
import com.maanit.stableshare.ui.debug.DebugViewModel
import com.maanit.stableshare.ui.theme.StableShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StableShareApp).container
        setContent {
            StableShareTheme {
                val vm: DebugViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            DebugViewModel(
                                repository = container.transferRepository,
                                settings = container.settingsRepository,
                                tracker = container.progressTracker,
                                api = container.protocolClient,
                                health = { container.protocolClient.health().ok },
                                controller = container.transferController,
                                scheduler = container.scheduler,
                                appScope = container.applicationScope,
                            )
                        }
                    },
                )
                var localNetworkDenied by remember { mutableStateOf(!LocalNetworkPermission.isGranted(this)) }
                val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    if (result[LocalNetworkPermission.PERMISSION] == true) {
                        localNetworkDenied = false
                        vm.onNetworkPermissionGranted()
                    }
                }
                LaunchedEffect(Unit) {
                    val missing = buildList {
                        if (!LocalNetworkPermission.isGranted(this@MainActivity)) add(LocalNetworkPermission.PERMISSION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
                }
                DebugScreen(
                    viewModel = vm,
                    localNetworkDenied = localNetworkDenied,
                    onRequestLocalNetwork = { permissions.launch(arrayOf(LocalNetworkPermission.PERMISSION)) },
                )
            }
        }
    }
}
