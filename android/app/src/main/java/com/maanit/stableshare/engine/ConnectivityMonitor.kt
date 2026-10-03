package com.maanit.stableshare.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.maanit.stableshare.data.net.NetworkBlocker
import com.maanit.stableshare.domain.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The default network as far as transfers care (DESIGN.md §7). */
enum class NetworkState { Offline, Metered, Unmetered }

/** The Wi-Fi only rule. Pure. */
object NetworkPolicy {
    /**
     * Offline = no network with INTERNET. VALIDATED is deliberately not required: the mock server
     * lives on a LAN/emulator host, reachable on networks that never pass Android's validation.
     */
    fun stateOf(hasInternet: Boolean, notMetered: Boolean): NetworkState = when {
        !hasInternet -> NetworkState.Offline
        notMetered -> NetworkState.Unmetered
        else -> NetworkState.Metered
    }

    /** Offline → false, Metered → !wifiOnly, Unmetered → true. */
    fun usable(state: NetworkState, wifiOnly: Boolean): Boolean = when (state) {
        NetworkState.Offline -> false
        NetworkState.Metered -> !wifiOnly
        NetworkState.Unmetered -> true
    }

    /** Null when [usable]; otherwise the code a waiting transfer carries. */
    fun blockReason(state: NetworkState, usable: Boolean): ErrorCode? = when {
        usable -> null
        state == NetworkState.Offline -> ErrorCode.NETWORK_UNAVAILABLE
        else -> ErrorCode.METERED_NETWORK
    }
}

/**
 * Live network signals. Rows waiting for the network resume on a false → true edge of
 * [usableNetwork]; [NetworkBlocker.blockReason] is what the error classifier asks after a failure.
 */
interface ConnectivityMonitor : NetworkBlocker {
    val networkState: StateFlow<NetworkState>

    /** Offline → false, Metered → !wifiOnly, Unmetered → true. */
    val usableNetwork: StateFlow<Boolean>
}

/**
 * Tracks the default network through a NetworkCallback and combines it with the Wi-Fi only
 * setting. Until DataStore has answered, Wi-Fi only counts as on, so nothing moves over a metered
 * network before the setting is known.
 */
class AndroidConnectivityMonitor(
    context: Context,
    private val wifiOnly: Flow<Boolean>,
    private val scope: CoroutineScope,
) : ConnectivityMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(query())
    private val usable = MutableStateFlow(NetworkPolicy.usable(state.value, wifiOnly = true))
    private var wifiOnlySetting: Boolean? = null
    private var defaultNetwork: Network? = null
    private var started = false

    override val networkState: StateFlow<NetworkState> = state.asStateFlow()
    override val usableNetwork: StateFlow<Boolean> = usable.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            publish(network, stateOf(caps))
        }

        override fun onLost(network: Network) {
            // A default-network callback reports a switch as onAvailable/onCapabilitiesChanged of
            // the new network; only losing the current default means offline.
            synchronized(this@AndroidConnectivityMonitor) {
                if (defaultNetwork != null && network != defaultNetwork) return
            }
            publish(null, NetworkState.Offline)
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        publish(null, query())
        cm.registerDefaultNetworkCallback(callback)
        scope.launch {
            wifiOnly.collect { on ->
                synchronized(this@AndroidConnectivityMonitor) {
                    wifiOnlySetting = on
                    usable.value = NetworkPolicy.usable(state.value, on)
                }
            }
        }
    }

    /**
     * The flows' verdict, or else a live query of the active network: a failing request can beat
     * the callback, and an offline failure must not burn an attempt. The live answer is not
     * published, so a stale query can never overwrite a newer callback.
     */
    override fun blockReason(): ErrorCode? {
        NetworkPolicy.blockReason(state.value, usable.value)?.let { return it }
        val live = query()
        return NetworkPolicy.blockReason(live, NetworkPolicy.usable(live, wifiOnlySetting ?: true))
    }

    @Synchronized
    private fun publish(network: Network?, newState: NetworkState) {
        defaultNetwork = network
        state.value = newState
        usable.value = NetworkPolicy.usable(newState, wifiOnlySetting ?: true)
    }

    private fun query(): NetworkState = stateOf(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })

    private fun stateOf(caps: NetworkCapabilities?): NetworkState = NetworkPolicy.stateOf(
        hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        notMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
    )
}
