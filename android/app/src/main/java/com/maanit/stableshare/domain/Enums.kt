package com.maanit.stableshare.domain

enum class TransferType { UPLOAD, DOWNLOAD }

/** Lifecycle of a transfer. Only TransferRepository.transition() may change it (rule 1). */
enum class TransferState {
    QUEUED,
    TRANSFERRING,
    RETRYING,
    VERIFYING,
    PAUSED,
    FAILED,
    COMPLETED,
    CANCELLED,
}

enum class ChunkStatus { PENDING, DONE, FAILED }

enum class ErrorCode {
    NETWORK_UNAVAILABLE,
    /** Wi-Fi only is on and the network is metered: waiting for an unmetered one. */
    METERED_NETWORK,
    TIMEOUT,
    CONNECTION_LOST,
    SERVER_ERROR,
    RATE_LIMITED,
    SESSION_NOT_FOUND,
    SESSION_CONFLICT,
    CHUNK_HASH_MISMATCH,
    FILE_HASH_MISMATCH,
    REMOTE_FILE_CHANGED,
    SOURCE_CHANGED,
    SOURCE_MISSING,
    DISK_FULL,
    RETRIES_EXHAUSTED,
    UNKNOWN,
}

enum class EventType {
    STATE_CHANGE,
    CHUNK_DONE,
    CHUNK_FAILED,
    CHUNK_CONFIRMED_AFTER_LOST_RESPONSE,
    RETRY_SCHEDULED,
    VERIFIED,
    ERROR,
    INFO,
}

/** User actions the UI may offer for a given state (see StateMachine.allowedActions). */
enum class TransferAction { PAUSE, RESUME, CANCEL, RETRY, REMOVE }
