package com.maanit.stableshare.ui.model

import com.maanit.stableshare.R
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.mascot.MascotMood

/** Colour roles of the list status label (UI-SPEC §6), resolved to Neutral tokens by the row. */
enum class LabelTone { TERTIARY, SECONDARY, WARNING, DANGER, SUCCESS }

/** Progress bar fill roles (UI-SPEC §6). */
enum class BarFill { MUTED, ACCENT, ACCENT_PULSE, WARNING, PAUSED, DANGER, SUCCESS }

enum class RingStroke { DASHED, SOLID, DOTTED }
enum class RingTone { STROKE, SECONDARY_60, DANGER }

/** Ring look and motion (UI-SPEC §5.8.3); [turnMs] is the dash rotation period, null = static. */
data class RingStyle(val stroke: RingStroke, val tone: RingTone, val turnMs: Int?)

/** Where Dart sits on the detail hero (UI-SPEC §4.1). */
enum class PlaneSpot {
    PERCHED,
    HOVER,
    RING_PROGRESS,
    RING_WOBBLE,
    RING_LAP,
    RING_FAILED,
    RING_TOP,
    HIDDEN,
}

/** The single state presentation table (UI-SPEC §6) plus the mood table (§4.1). Pure. */
object StatePresentation {

    fun listLabel(item: TransferItem, maxTries: Int): UiText = when (item.condition) {
        Condition.QUEUED -> UiText.res(R.string.label_queued, item.queuePosition ?: 1)
        Condition.PREPARING -> UiText.res(R.string.label_preparing, item.preparingPercent)
        Condition.TRANSFERRING ->
            UiText.res(if (item.type == TransferType.UPLOAD) R.string.label_uploading else R.string.label_downloading)
        Condition.VERIFYING -> UiText.res(R.string.label_verifying)
        Condition.RETRYING -> UiText.res(R.string.label_retrying, item.retryInSeconds)
        Condition.WAITING_NETWORK -> UiText.res(R.string.label_waiting_network)
        Condition.QUEUED_WIFI, Condition.WAITING_WIFI -> UiText.res(R.string.label_waiting_wifi)
        Condition.PAUSED -> UiText.res(R.string.label_paused)
        Condition.FAILED -> UiText.res(R.string.label_failed, ErrorCopy.short(item.row.errorCode, maxTries))
        Condition.COMPLETED ->
            UiText.res(if (item.type == TransferType.UPLOAD) R.string.label_uploaded else R.string.label_downloaded)
        Condition.CANCELLED -> UiText.res(R.string.label_cancelled)
    }

    fun labelTone(condition: Condition): LabelTone = when (condition) {
        Condition.QUEUED, Condition.PREPARING, Condition.TRANSFERRING -> LabelTone.TERTIARY
        Condition.VERIFYING, Condition.WAITING_NETWORK, Condition.QUEUED_WIFI, Condition.WAITING_WIFI, Condition.PAUSED ->
            LabelTone.SECONDARY
        Condition.RETRYING -> LabelTone.WARNING
        Condition.FAILED -> LabelTone.DANGER
        Condition.COMPLETED -> LabelTone.SUCCESS
        Condition.CANCELLED -> LabelTone.TERTIARY
    }

    fun barFill(condition: Condition): BarFill = when (condition) {
        Condition.QUEUED, Condition.QUEUED_WIFI, Condition.WAITING_NETWORK, Condition.WAITING_WIFI, Condition.CANCELLED ->
            BarFill.MUTED
        Condition.PREPARING, Condition.TRANSFERRING -> BarFill.ACCENT
        Condition.VERIFYING -> BarFill.ACCENT_PULSE
        Condition.RETRYING -> BarFill.WARNING
        Condition.PAUSED -> BarFill.PAUSED
        Condition.FAILED -> BarFill.DANGER
        Condition.COMPLETED -> BarFill.SUCCESS
    }

    /** VERIFYING and COMPLETED fill the bar; everything else shows the displayed percentage. */
    fun barFraction(item: TransferItem): Float = when (item.condition) {
        Condition.VERIFYING, Condition.COMPLETED -> 1f
        else -> item.percent / 100f
    }

    /** Detail title; titles ending in "..." animate their ellipsis (§5.8.1). */
    fun detailTitle(condition: Condition, type: TransferType): UiText = UiText.res(
        when (condition) {
            Condition.QUEUED -> R.string.title_queued
            Condition.PREPARING -> R.string.title_preparing
            Condition.TRANSFERRING -> if (type == TransferType.UPLOAD) R.string.title_uploading else R.string.title_downloading
            Condition.VERIFYING -> R.string.title_verifying
            Condition.RETRYING -> R.string.title_retrying
            Condition.WAITING_NETWORK -> R.string.title_waiting_network
            Condition.QUEUED_WIFI, Condition.WAITING_WIFI -> R.string.title_waiting_wifi
            Condition.PAUSED -> R.string.title_paused
            Condition.FAILED -> R.string.title_failed
            Condition.COMPLETED -> R.string.title_completed
            Condition.CANCELLED -> R.string.title_cancelled
        },
    )

    /**
     * Detail stats line. [retryAttempt] is the attempt number of the latest RETRY_SCHEDULED event;
     * [doneChunks] counts DONE pieces (paused copy).
     */
    fun detailStats(
        item: TransferItem,
        maxConcurrent: Int,
        retryAttempt: Int,
        maxTries: Int,
        doneChunks: Int,
    ): UiText = when (item.condition) {
        Condition.QUEUED -> UiText.res(R.string.stats_queued, item.queuePosition ?: 1, maxConcurrent)
        Condition.PREPARING -> UiText.res(R.string.stats_preparing, item.preparingPercent)
        Condition.TRANSFERRING -> {
            val speed = Format.speed(item.liveSpeed)
            val eta = item.etaSeconds
            if (speed == null || eta == null) UiText.res(R.string.stats_starting)
            else UiText.res(R.string.stats_transferring, speed, Format.eta(eta))
        }
        Condition.VERIFYING -> UiText.res(R.string.stats_verifying)
        Condition.RETRYING -> UiText.res(R.string.stats_retrying, retryAttempt, maxTries, item.retryInSeconds)
        Condition.WAITING_NETWORK -> UiText.res(R.string.stats_waiting_network)
        Condition.QUEUED_WIFI, Condition.WAITING_WIFI -> UiText.res(R.string.stats_waiting_wifi)
        Condition.PAUSED -> UiText.plural(R.plurals.stats_paused, item.row.totalChunks, doneChunks, item.row.totalChunks)
        Condition.FAILED -> ErrorCopy.long(item.row.errorCode, maxTries)
        Condition.COMPLETED -> UiText.res(R.string.stats_completed)
        Condition.CANCELLED -> UiText.res(R.string.stats_cancelled)
    }

    fun mood(condition: Condition): MascotMood = when (condition) {
        Condition.QUEUED, Condition.QUEUED_WIFI, Condition.PREPARING, Condition.TRANSFERRING, Condition.VERIFYING ->
            MascotMood.FOCUSED
        Condition.RETRYING -> MascotMood.WORRIED
        Condition.WAITING_NETWORK, Condition.WAITING_WIFI -> MascotMood.SEARCHING
        Condition.PAUSED -> MascotMood.SLEEPY
        Condition.FAILED -> MascotMood.SAD
        Condition.COMPLETED -> MascotMood.HAPPY
        Condition.CANCELLED -> MascotMood.CALM
    }

    fun ring(condition: Condition): RingStyle = when (condition) {
        Condition.QUEUED, Condition.QUEUED_WIFI -> RingStyle(RingStroke.DASHED, RingTone.SECONDARY_60, null)
        Condition.PREPARING, Condition.TRANSFERRING -> RingStyle(RingStroke.DASHED, RingTone.STROKE, 8_000)
        Condition.VERIFYING -> RingStyle(RingStroke.DASHED, RingTone.STROKE, 2_000)
        Condition.RETRYING -> RingStyle(RingStroke.DASHED, RingTone.STROKE, null)
        Condition.WAITING_NETWORK, Condition.WAITING_WIFI -> RingStyle(RingStroke.DASHED, RingTone.SECONDARY_60, null)
        Condition.PAUSED -> RingStyle(RingStroke.DASHED, RingTone.STROKE, null)
        Condition.FAILED -> RingStyle(RingStroke.DASHED, RingTone.DANGER, null)
        Condition.COMPLETED -> RingStyle(RingStroke.SOLID, RingTone.STROKE, null)
        Condition.CANCELLED -> RingStyle(RingStroke.DOTTED, RingTone.SECONDARY_60, null)
    }

    fun plane(condition: Condition): PlaneSpot = when (condition) {
        Condition.QUEUED, Condition.QUEUED_WIFI, Condition.WAITING_NETWORK, Condition.WAITING_WIFI -> PlaneSpot.PERCHED
        Condition.PREPARING -> PlaneSpot.HOVER
        Condition.TRANSFERRING, Condition.PAUSED -> PlaneSpot.RING_PROGRESS
        Condition.VERIFYING -> PlaneSpot.RING_LAP
        Condition.RETRYING -> PlaneSpot.RING_WOBBLE
        Condition.FAILED -> PlaneSpot.RING_FAILED
        Condition.COMPLETED -> PlaneSpot.RING_TOP
        Condition.CANCELLED -> PlaneSpot.HIDDEN
    }

    /** Data drops light up only while actually transferring (not while preparing). */
    fun dropsActive(condition: Condition): Boolean = condition == Condition.TRANSFERRING
}
