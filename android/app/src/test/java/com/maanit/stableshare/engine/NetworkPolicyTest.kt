package com.maanit.stableshare.engine

import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.engine.NetworkState.Metered
import com.maanit.stableshare.engine.NetworkState.Offline
import com.maanit.stableshare.engine.NetworkState.Unmetered
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Wi-Fi only rule: which networks transfers may use, and the code they wait with. */
class NetworkPolicyTest {

    @Test
    fun networkStateFromCapabilities() {
        assertEquals(Offline, NetworkPolicy.stateOf(hasInternet = false, notMetered = false))
        assertEquals(Offline, NetworkPolicy.stateOf(hasInternet = false, notMetered = true))
        assertEquals(Metered, NetworkPolicy.stateOf(hasInternet = true, notMetered = false))
        assertEquals(Unmetered, NetworkPolicy.stateOf(hasInternet = true, notMetered = true))
    }

    @Test
    fun usableTruthTable() {
        val table = mapOf(
            (Offline to false) to false, (Offline to true) to false,
            (Metered to false) to true, (Metered to true) to false,
            (Unmetered to false) to true, (Unmetered to true) to true,
        )
        table.forEach { (input, usable) ->
            assertEquals("$input", usable, NetworkPolicy.usable(input.first, wifiOnly = input.second))
        }
    }

    @Test
    fun blockReasonNamesWhyTheNetworkIsUnusable() {
        assertEquals(null, NetworkPolicy.blockReason(Unmetered, usable = true))
        assertEquals(null, NetworkPolicy.blockReason(Metered, usable = true))
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, NetworkPolicy.blockReason(Offline, usable = false))
        assertEquals(ErrorCode.METERED_NETWORK, NetworkPolicy.blockReason(Metered, usable = false))
    }
}
