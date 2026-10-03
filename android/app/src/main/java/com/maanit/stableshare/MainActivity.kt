package com.maanit.stableshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maanit.stableshare.ui.home.HomeViewModel
import com.maanit.stableshare.ui.home.TransfersPlaceholderScreen
import com.maanit.stableshare.ui.theme.StableShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StableShareApp).container
        setContent {
            StableShareTheme {
                val vm: HomeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HomeViewModel(container.transferRepository, container.settingsRepository, container.protocolClient)
                        }
                    },
                )
                TransfersPlaceholderScreen(vm)
            }
        }
    }
}
