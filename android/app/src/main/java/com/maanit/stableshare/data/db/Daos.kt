package com.maanit.stableshare.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.flow.Flow

/*
 * Observation queries are public. Every write is `internal` and called only from
 * TransferRepository, which wraps multi-row changes in a transaction.
 */

@Dao
abstract class TransferDao {

    @Query("SELECT * FROM transfers ORDER BY createdAt DESC, id")
    abstract fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    abstract fun observe(id: String): Flow<TransferEntity?>

    @Query(
        "SELECT * FROM transfers WHERE state IN ('COMPLETED', 'CANCELLED') " +
            "ORDER BY updatedAt DESC, id",
    )
    abstract fun observeHistory(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    abstract suspend fun get(id: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE state IN (:states) ORDER BY createdAt, id")
    abstract suspend fun getInStates(states: List<TransferState>): List<TransferEntity>

    @Insert
    internal abstract suspend fun insert(transfer: TransferEntity)

    /** Compare-and-set: matches 0 rows if someone else changed the state first. */
    @Query(
        """UPDATE transfers SET state = :to, errorCode = :errorCode, errorMessage = :errorMessage,
           nextRetryAt = :nextRetryAt, attemptCount = :attemptCount, completedAt = :completedAt,
           updatedAt = :now
           WHERE id = :id AND state = :from""",
    )
    internal abstract suspend fun compareAndSetState(
        id: String,
        from: TransferState,
        to: TransferState,
        errorCode: ErrorCode?,
        errorMessage: String?,
        nextRetryAt: Long?,
        attemptCount: Int,
        completedAt: Long?,
        now: Long,
    ): Int

    @Query(
        """SELECT * FROM transfers
           WHERE (state = 'QUEUED'
                  OR (state = 'RETRYING' AND nextRetryAt IS NOT NULL AND nextRetryAt <= :now))
             AND id NOT IN (:exclude)
           ORDER BY createdAt, id LIMIT :limit""",
    )
    internal abstract suspend fun claimable(now: Long, limit: Int, exclude: List<String>): List<TransferEntity>

    @Query(
        """UPDATE transfers SET
             bytesDone = (SELECT COALESCE(SUM(length), 0) FROM chunks
                          WHERE transferId = :id AND status = 'DONE'),
             updatedAt = :now
           WHERE id = :id""",
    )
    internal abstract suspend fun recomputeBytesDone(id: String, now: Long)

    @Query("UPDATE transfers SET attemptCount = attemptCount + 1, updatedAt = :now WHERE id = :id")
    internal abstract suspend fun incrementAttemptCount(id: String, now: Long)

    @Query("UPDATE transfers SET sessionCreated = :created, updatedAt = :now WHERE id = :id")
    internal abstract suspend fun setSessionCreated(id: String, created: Boolean, now: Long): Int

    @Query("UPDATE transfers SET sha256 = :sha256, updatedAt = :now WHERE id = :id")
    internal abstract suspend fun setSha256(id: String, sha256: String, now: Long): Int

    @Query(
        """UPDATE transfers SET sha256 = :sha256, sourceLastModified = :lastModified, updatedAt = :now
           WHERE id = :id""",
    )
    internal abstract suspend fun setSourceInfo(id: String, sha256: String, lastModified: Long?, now: Long): Int

    @Query("UPDATE transfers SET etag = :etag, updatedAt = :now WHERE id = :id")
    internal abstract suspend fun setEtag(id: String, etag: String, now: Long): Int

    @Query("UPDATE transfers SET localUri = :localUri, updatedAt = :now WHERE id = :id")
    internal abstract suspend fun setLocalUri(id: String, localUri: String, now: Long): Int

    @Query("DELETE FROM transfers WHERE id = :id AND state IN ('COMPLETED', 'CANCELLED')")
    internal abstract suspend fun deleteIfTerminal(id: String): Int

    @Query("DELETE FROM transfers WHERE state IN ('COMPLETED', 'CANCELLED')")
    internal abstract suspend fun deleteAllTerminal(): Int
}

@Dao
abstract class ChunkDao {

    @Query("SELECT * FROM chunks WHERE transferId = :id ORDER BY `index`")
    abstract fun observe(id: String): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE transferId = :id ORDER BY `index`")
    abstract suspend fun getAll(id: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE transferId = :id AND `index` = :index")
    abstract suspend fun get(id: String, index: Int): ChunkEntity?

    @Insert
    internal abstract suspend fun insertAll(chunks: List<ChunkEntity>)

    // Guarded writes (DESIGN.md §5.3, rule 5): match 0 rows unless the transfer is TRANSFERRING.

    @Query(
        """UPDATE chunks SET status = 'DONE', sha256 = COALESCE(:sha256, sha256), lastError = NULL
           WHERE transferId = :id AND `index` = :index
             AND (SELECT state FROM transfers WHERE id = :id) = 'TRANSFERRING'""",
    )
    internal abstract suspend fun markDoneIfTransferring(id: String, index: Int, sha256: String?): Int

    @Query(
        """UPDATE chunks SET status = 'FAILED', lastError = :error
           WHERE transferId = :id AND `index` = :index
             AND (SELECT state FROM transfers WHERE id = :id) = 'TRANSFERRING'""",
    )
    internal abstract suspend fun markFailedIfTransferring(id: String, index: Int, error: String): Int

    // Re-sync writes, allowed while TRANSFERRING or VERIFYING (complete → MISSING_CHUNKS re-sync).

    @Query(
        """UPDATE chunks SET status = 'PENDING'
           WHERE transferId = :id
             AND (SELECT state FROM transfers WHERE id = :id) IN ('TRANSFERRING', 'VERIFYING')""",
    )
    internal abstract suspend fun resetAllIfSyncable(id: String): Int

    @Query(
        """UPDATE chunks SET status = 'PENDING'
           WHERE transferId = :id AND `index` IN (:indices)
             AND (SELECT state FROM transfers WHERE id = :id) IN ('TRANSFERRING', 'VERIFYING')""",
    )
    internal abstract suspend fun resetIfSyncable(id: String, indices: List<Int>): Int

    @Query(
        """UPDATE chunks SET status = 'DONE', lastError = NULL
           WHERE transferId = :id AND `index` IN (:indices)
             AND (SELECT state FROM transfers WHERE id = :id) IN ('TRANSFERRING', 'VERIFYING')""",
    )
    internal abstract suspend fun markDoneIfSyncable(id: String, indices: List<Int>): Int

    @Query("UPDATE chunks SET attempts = attempts + 1 WHERE transferId = :id AND `index` = :index")
    internal abstract suspend fun incrementAttempts(id: String, index: Int): Int

    @Query("UPDATE chunks SET attempts = 0 WHERE transferId = :id")
    internal abstract suspend fun resetAttempts(id: String)
}

@Dao
abstract class EventDao {

    @Query("SELECT * FROM transfer_events WHERE transferId = :id ORDER BY id")
    abstract fun observe(id: String): Flow<List<TransferEventEntity>>

    @Query("SELECT * FROM transfer_events WHERE transferId = :id ORDER BY id")
    abstract suspend fun getAll(id: String): List<TransferEventEntity>

    @Insert
    internal abstract suspend fun insert(event: TransferEventEntity): Long
}
