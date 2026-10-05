package com.maanit.stableshare.engine

import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.ServerChoice
import com.maanit.stableshare.data.settings.ServerProfiles
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transfers are not tied to a server: every request goes to the one selected at that moment. So a
 * switch pauses the transfers that would carry on (the new server has never seen them) and parks
 * them under the server they belong to; selecting that server again resumes them.
 */
class ServerSwitch(
    private val settings: SettingsRepository,
    private val repo: TransferRepository,
    private val controller: TransferController,
) {
    private val mutex = Mutex()

    /** Whether switching away from the current server would pause anything. */
    suspend fun hasActiveTransfers(): Boolean = repo.getInStates(*ACTIVE).isNotEmpty()

    /** Selects [choice]. Returns false (nothing paused or stored) if it is not usable. */
    suspend fun switchTo(choice: ServerChoice): Boolean = mutex.withLock {
        val next = ServerProfiles.urlOf(choice) ?: return false
        val previous = settings.current().serverUrl
        if (next == previous) return settings.setServerChoice(choice)
        val paused = repo.getInStates(*ACTIVE).map { it.id }.filter { controller.pause(it) }
        settings.parkTransfers(previous, paused)
        settings.setServerChoice(choice)
        settings.takeParkedTransfers(next).forEach { controller.resume(it) }
        true
    }

    private companion object {
        /** What [TransferController.pauseAll] pauses; VERIFYING has no edge to PAUSED and finishes on its own. */
        val ACTIVE = arrayOf(TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.RETRYING)
    }
}
