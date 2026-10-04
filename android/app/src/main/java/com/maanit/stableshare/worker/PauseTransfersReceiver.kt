package com.maanit.stableshare.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.maanit.stableshare.StableShareApp
import kotlinx.coroutines.launch

/**
 * The ongoing notification's "Pause transfers" action (UI-SPEC §5.12): pauses every queued or
 * running transfer through the controller. The coordinator sees the rows leave the active states,
 * stops their pipelines and exits when idle, which ends the job or worker and its notification.
 */
class PauseTransfersReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val container = (context.applicationContext as StableShareApp).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val paused = container.transferController.pauseAll()
                Log.i(TAG, "notification: paused $paused transfer(s)")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.maanit.stableshare.action.PAUSE_TRANSFERS"
        private const val TAG = "StableShare"
    }
}
