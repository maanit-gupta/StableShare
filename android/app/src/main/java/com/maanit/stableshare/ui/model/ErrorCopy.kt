package com.maanit.stableshare.ui.model

import com.maanit.stableshare.R
import com.maanit.stableshare.domain.ErrorCode

/** Error copy (UI-SPEC §7). [maxTries] fills RETRIES_EXHAUSTED. */
object ErrorCopy {
    fun short(code: ErrorCode?, maxTries: Int): UiText = when (code ?: ErrorCode.UNKNOWN) {
        ErrorCode.NETWORK_UNAVAILABLE -> UiText.res(R.string.err_short_network_unavailable)
        ErrorCode.TIMEOUT -> UiText.res(R.string.err_short_timeout)
        ErrorCode.CONNECTION_LOST -> UiText.res(R.string.err_short_connection_lost)
        ErrorCode.SERVER_ERROR -> UiText.res(R.string.err_short_server_error)
        ErrorCode.RATE_LIMITED -> UiText.res(R.string.err_short_rate_limited)
        ErrorCode.SESSION_NOT_FOUND -> UiText.res(R.string.err_short_session_not_found)
        ErrorCode.SESSION_CONFLICT -> UiText.res(R.string.err_short_session_conflict)
        ErrorCode.CHUNK_HASH_MISMATCH -> UiText.res(R.string.err_short_chunk_hash_mismatch)
        ErrorCode.FILE_HASH_MISMATCH -> UiText.res(R.string.err_short_file_hash_mismatch)
        ErrorCode.REMOTE_FILE_CHANGED -> UiText.res(R.string.err_short_remote_file_changed)
        ErrorCode.SOURCE_CHANGED -> UiText.res(R.string.err_short_source_changed)
        ErrorCode.SOURCE_MISSING -> UiText.res(R.string.err_short_source_missing)
        ErrorCode.DISK_FULL -> UiText.res(R.string.err_short_disk_full)
        ErrorCode.RETRIES_EXHAUSTED -> UiText.plural(R.plurals.err_short_retries_exhausted, maxTries, maxTries)
        ErrorCode.UNKNOWN -> UiText.res(R.string.err_short_unknown)
    }

    fun long(code: ErrorCode?, maxTries: Int): UiText = when (code ?: ErrorCode.UNKNOWN) {
        ErrorCode.NETWORK_UNAVAILABLE -> UiText.res(R.string.err_long_network_unavailable)
        ErrorCode.TIMEOUT -> UiText.res(R.string.err_long_timeout)
        ErrorCode.CONNECTION_LOST -> UiText.res(R.string.err_long_connection_lost)
        ErrorCode.SERVER_ERROR -> UiText.res(R.string.err_long_server_error)
        ErrorCode.RATE_LIMITED -> UiText.res(R.string.err_long_rate_limited)
        ErrorCode.SESSION_NOT_FOUND -> UiText.res(R.string.err_long_session_not_found)
        ErrorCode.SESSION_CONFLICT -> UiText.res(R.string.err_long_session_conflict)
        ErrorCode.CHUNK_HASH_MISMATCH -> UiText.res(R.string.err_long_chunk_hash_mismatch)
        ErrorCode.FILE_HASH_MISMATCH -> UiText.res(R.string.err_long_file_hash_mismatch)
        ErrorCode.REMOTE_FILE_CHANGED -> UiText.res(R.string.err_long_remote_file_changed)
        ErrorCode.SOURCE_CHANGED -> UiText.res(R.string.err_long_source_changed)
        ErrorCode.SOURCE_MISSING -> UiText.res(R.string.err_long_source_missing)
        ErrorCode.DISK_FULL -> UiText.res(R.string.err_long_disk_full)
        ErrorCode.RETRIES_EXHAUSTED -> UiText.plural(R.plurals.err_long_retries_exhausted, maxTries, maxTries)
        ErrorCode.UNKNOWN -> UiText.res(R.string.err_long_unknown)
    }
}
