package com.maanit.stableshare.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maanit.stableshare.di.AppContainer

/** A ViewModel scoped to the current destination, built from the [AppContainer] (manual DI). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: AppContainer.() -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { container.create() } })
}
