package com.maanit.stableshare.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.maanit.stableshare.data.net.AndroidConnectivityChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live "is there a usable network?" signal. Rows waiting for the network resume on an offline → online edge. */
interface ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>
}

/**
 * Tracks the default network through a NetworkCallback. "Online" uses the same rule as
 * [AndroidConnectivityChecker] (INTERNET capability, VALIDATED not required), so the monitor and
 * the error classifier agree about when a failure means "wait for the network".
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val checker = AndroidConnectivityChecker(context)
    private val online = MutableStateFlow(checker.isNetworkAvailable())
    override val isOnline: StateFlow<Boolean> = online.asStateFlow()

    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun onLost(network: Network) {
            online.value = false
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        online.value = checker.isNetworkAvailable()
        cm.registerDefaultNetworkCallback(callback)
    }
}
