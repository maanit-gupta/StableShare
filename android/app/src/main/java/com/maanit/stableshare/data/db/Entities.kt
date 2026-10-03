package com.maanit.stableshare.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType

/**
 * One upload or download. Enums are stored by name (Room's built-in enum mapping).
 * `state` is written only by TransferRepository.transition() and claimNextQueued() (rule 1).
 */
@Entity(
    tableName = "transfers",
    indices = [Index("state"), Index("createdAt")],
)
data class TransferEntity(
    /** UUID. For uploads it is also the server uploadId. */
    @PrimaryKey val id: String,
    val type: TransferType,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    /** Upload: source URI (content:// or file://). Download: the .part file, then the final file. */
    val localUri: String,
    /** Upload: uploadId (== id). Download: server fileId. */
    val remoteId: String?,
    val chunkSize: Int,
    val totalChunks: Int,
    /** Expected full-file SHA-256 (upload: computed from the source; download: from the manifest). */
    val sha256: String?,
    val sourceLastModified: Long?,
    /** Download only: the manifest ETag, sent as If-Range. */
    val etag: String?,
    val state: TransferState,
    /** Sum of DONE chunk lengths; recomputed in the same transaction as every chunk change. */
    val bytesDone: Long,
    val errorCode: ErrorCode?,
    val errorMessage: String?,
    val attemptCount: Int,
    val nextRetryAt: Long?,
    val sessionCreated: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "chunks",
    primaryKeys = ["transferId", "index"],
    foreignKeys = [
        ForeignKey(
            entity = TransferEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChunkEntity(
    val transferId: String,
    @ColumnInfo(name = "index") val index: Int,
    val offset: Long,
    val length: Int,
    /** Download: manifest hash. Upload: hash of the bytes actually sent (set when DONE). */
    val sha256: String?,
    val status: ChunkStatus,
    val attempts: Int,
    val lastError: String?,
)

@Entity(
    tableName = "transfer_events",
    indices = [Index("transferId")],
    foreignKeys = [
        ForeignKey(
            entity = TransferEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TransferEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transferId: String,
    val timestamp: Long,
    val type: EventType,
    val fromState: TransferState?,
    val toState: TransferState?,
    val chunkIndex: Int?,
    val message: String,
)
