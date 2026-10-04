package com.maanit.stableshare.ui.model

import com.maanit.stableshare.R
import com.maanit.stableshare.data.db.TransferEventEntity
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState
import kotlin.math.ceil

enum class ActivityDot { DANGER, YELLOW, STROKE }

data class ActivityEntry(val timestamp: Long, val message: UiText, val dot: ActivityDot)

/**
 * Turns the stored event log into the Activity list (UI-SPEC §8), newest first. Runs of
 * consecutive CHUNK_DONE events collapse into one entry. Error codes and attempt numbers are read
 * from the messages TransferRepository and RetryRunner write ("[CODE]", "(CODE)", "attempt N").
 */
object ActivityMapper {

    fun map(events: List<TransferEventEntity>, maxTries: Int): List<ActivityEntry> {
        val out = ArrayList<ActivityEntry>()
        var i = 0
        val sorted = events.sortedBy { it.id }
        while (i < sorted.size) {
            val e = sorted[i]
            if (e.type == EventType.CHUNK_DONE) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].type == EventType.CHUNK_DONE) j++
                val first = sorted[i].chunkIndex ?: 0
                val last = sorted[j].chunkIndex ?: first
                val text = if (i == j) UiText.res(R.string.act_piece_done, first + 1)
                else UiText.res(R.string.act_pieces_done, first + 1, last + 1)
                out += ActivityEntry(sorted[j].timestamp, text, ActivityDot.STROKE)
                i = j + 1
                continue
            }
            entryFor(e, maxTries)?.let { out += it }
            i++
        }
        return out.asReversed()
    }

    private fun entryFor(e: TransferEventEntity, maxTries: Int): ActivityEntry? {
        val text: UiText = when (e.type) {
            EventType.STATE_CHANGE -> stateChange(e, maxTries) ?: return null
            EventType.CHUNK_DONE -> error("collapsed by map()")
            EventType.CHUNK_FAILED ->
                UiText.res(R.string.act_piece_failed, (e.chunkIndex ?: 0) + 1, ErrorCopy.short(chunkFailureCode(e.message), maxTries))
            EventType.CHUNK_CONFIRMED_AFTER_LOST_RESPONSE ->
                UiText.res(R.string.act_piece_confirmed, (e.chunkIndex ?: 0) + 1)
            EventType.RETRY_SCHEDULED -> UiText.res(
                R.string.act_retry_scheduled,
                retryDelaySeconds(e.message),
                retryAttempt(e.message) ?: 1,
                maxTries,
            )
            EventType.VERIFIED -> UiText.res(R.string.act_verified)
            EventType.INSTANT_UPLOAD -> UiText.res(R.string.act_instant_upload)
            EventType.ERROR -> ErrorCopy.long(bracketCode(e.message), maxTries)
            EventType.INFO ->
                if (e.message.startsWith(RESTORED_PREFIX)) UiText.res(R.string.act_restored) else UiText.Raw(e.message)
        }
        val dot = when (e.type) {
            EventType.ERROR, EventType.CHUNK_FAILED -> ActivityDot.DANGER
            EventType.RETRY_SCHEDULED -> ActivityDot.YELLOW
            else -> ActivityDot.STROKE
        }
        return ActivityEntry(e.timestamp, text, dot)
    }

    /** Null for transitions §8 does not list (requeues by promotion, reconciliation or a system stop). */
    private fun stateChange(e: TransferEventEntity, maxTries: Int): UiText? = when (e.toState) {
        TransferState.QUEUED -> when (e.fromState) {
            null -> UiText.res(R.string.act_added)
            TransferState.FAILED -> UiText.res(R.string.act_retry_requested)
            TransferState.PAUSED -> UiText.res(R.string.act_resumed)
            else -> null
        }
        TransferState.TRANSFERRING -> UiText.res(R.string.act_started)
        TransferState.PAUSED -> UiText.res(R.string.act_paused)
        TransferState.VERIFYING -> UiText.res(R.string.act_verifying)
        TransferState.RETRYING -> when (val code = bracketCode(e.message)) {
            ErrorCode.METERED_NETWORK -> UiText.res(R.string.act_waiting_wifi)
            else -> UiText.res(R.string.act_problem, ErrorCopy.short(code, maxTries))
        }
        TransferState.FAILED -> UiText.res(R.string.act_stopped, ErrorCopy.short(bracketCode(e.message), maxTries))
        TransferState.COMPLETED -> UiText.res(R.string.act_completed)
        TransferState.CANCELLED -> UiText.res(R.string.act_cancelled)
        null -> null
    }

    /** Attempt number of a RETRY_SCHEDULED message ("Chunk 3 attempt 2 failed (TIMEOUT); retrying in 1200 ms"). */
    fun retryAttempt(message: String): Int? = ATTEMPT.find(message)?.groupValues?.get(1)?.toIntOrNull()

    /** The latest scheduled retry's attempt number, for the detail stats line. */
    fun latestRetryAttempt(events: List<TransferEventEntity>): Int? =
        events.filter { it.type == EventType.RETRY_SCHEDULED }.maxByOrNull { it.id }?.let { retryAttempt(it.message) }

    /** A full SHA-256 named in the VERIFIED event, if any. */
    fun verifiedSha(events: List<TransferEventEntity>): String? =
        events.filter { it.type == EventType.VERIFIED }.maxByOrNull { it.id }?.let { SHA.find(it.message)?.value }

    private fun retryDelaySeconds(message: String): Int =
        DELAY.find(message)?.groupValues?.get(1)?.toLongOrNull()?.let { ceil(it / 1_000.0).toInt() } ?: 0

    private fun bracketCode(message: String): ErrorCode? = BRACKET.find(message)?.groupValues?.get(1)?.let(::codeOrNull)

    /** "Chunk 3 failed: TIMEOUT: …" */
    private fun chunkFailureCode(message: String): ErrorCode? =
        CHUNK_FAILED.find(message)?.groupValues?.get(1)?.let(::codeOrNull)

    private fun codeOrNull(name: String): ErrorCode? = ErrorCode.entries.firstOrNull { it.name == name }

    private const val RESTORED_PREFIX = "Reconciled after process start"
    private val ATTEMPT = Regex("""attempt (\d+)""")
    private val DELAY = Regex("""retrying in (\d+) ms""")
    private val BRACKET = Regex("""\[([A-Z_]+)]""")
    private val CHUNK_FAILED = Regex("""failed: ([A-Z_]+):""")
    private val SHA = Regex("""\b[0-9a-f]{64}\b""")
}
