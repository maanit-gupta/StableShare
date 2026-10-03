package com.maanit.stableshare.worker

import android.app.Notification
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Posts the Completed and Failed notifications (UI-SPEC §5.12) when this process sees a transfer
 * move into COMPLETED or FAILED. The first emission after process start is only a baseline, so
 * old results are never re-announced.
 */
class TransferResultNotifier(
    private val transfers: Flow<List<TransferEntity>>,
    private val completed: (TransferEntity) -> Notification,
    private val failed: (TransferEntity) -> Notification,
    private val post: (String, Notification) -> Unit,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            var previous: Map<String, TransferState>? = null
            transfers.collect { rows ->
                val before = previous
                if (before != null) {
                    rows.forEach { row ->
                        val was = before[row.id]
                        if (was != null && was != row.state) {
                            when (row.state) {
                                TransferState.COMPLETED -> post(row.id, completed(row))
                                TransferState.FAILED -> post(row.id, failed(row))
                                else -> Unit
                            }
                        }
                    }
                }
                previous = rows.associate { it.id to it.state }
            }
        }
    }
}
