package com.maanit.stableshare.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** One-shot "is there a usable network?" check, used to tell WAITING from RETRYABLE. */
fun interface ConnectivityChecker {
    fun isNetworkAvailable(): Boolean
}

/**
 * Requires an active network with INTERNET capability. VALIDATED is deliberately not required:
 * the mock server lives on a LAN/emulator host, reachable on networks that never pass Android's
 * internet validation.
 */
class AndroidConnectivityChecker(context: Context) : ConnectivityChecker {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun isNetworkAvailable(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
