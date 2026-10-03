package com.maanit.stableshare.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) treats private-range hosts — the emulator's 10.0.2.2 and any LAN server —
 * as "local network", gated by the runtime ACCESS_LOCAL_NETWORK permission. Without it, sockets
 * to the mock server silently time out. Older versions need nothing.
 */
object LocalNetworkPermission {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val FIRST_ENFORCED_SDK = 37

    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= FIRST_ENFORCED_SDK

    fun isGranted(context: Context): Boolean =
        !isRequired() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
