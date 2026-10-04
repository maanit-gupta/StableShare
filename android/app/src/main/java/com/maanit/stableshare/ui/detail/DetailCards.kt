package com.maanit.stableshare.ui.detail

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maanit.stableshare.R
import com.maanit.stableshare.data.db.ChunkEntity
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.model.ActivityDot
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.model.text
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Mint
import com.maanit.stableshare.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.ceil

private val CardShape = RoundedCornerShape(10.dp)

/** Mint information card (UI-SPEC §5.8.6): surface fill, 1.5 dp outline, 10 dp radius, 16 dp padding. */
@Composable
private fun MintCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Mint.colors.surface, CardShape)
            .border(1.5.dp, Mint.colors.stroke, CardShape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Mint.type.title.copy(textAlign = TextAlign.Start), modifier = modifier)
}

private val startBody @Composable get() = Mint.type.body.copy(textAlign = TextAlign.Start)
private val startCaption @Composable get() = Mint.type.caption.copy(textAlign = TextAlign.Start)

// ---- Pieces ----

/** Largest cell from 10 dp down to 4 dp that fits every chunk in at most 12 rows (§5.8.6). */
internal fun chunkCellSize(chunks: Int, widthDp: Float, gapDp: Float = 2f): Float {
    if (chunks <= 0) return 10f
    for (cell in 10 downTo 4) {
        val cols = ((widthDp + gapDp) / (cell + gapDp)).toInt().coerceAtLeast(1)
        if (ceil(chunks / cols.toDouble()) <= 12) return cell.toFloat()
    }
    return 4f
}

@Composable
fun PiecesCard(ui: DetailUi) {
    val total = ui.chunks.size
    val summary = if (total == 0) {
        stringResource(R.string.pieces_none)
    } else {
        pluralStringResource(R.plurals.pieces_summary, total, ui.doneChunks, total) +
            if (ui.failedChunks > 0) stringResource(R.string.pieces_failed_suffix, ui.failedChunks) else ""
    }
    MintCard {
        CardTitle(stringResource(R.string.pieces_title))
        if (total > 0) {
            Spacer(Modifier.height(12.dp))
            ChunkMap(ui.chunks, ui.item.inFlightChunk.takeIf { ui.item.state == TransferState.TRANSFERRING }, summary)
        }
        Spacer(Modifier.height(12.dp))
        Text(summary, style = startBody)
        Spacer(Modifier.height(8.dp))
        Legend()
    }
}

@Composable
private fun ChunkMap(chunks: List<ChunkEntity>, inFlight: Int?, description: String) {
    val c = Mint.colors
    val reduced = LocalReducedMotion.current
    val pulse = if (inFlight != null && !reduced) {
        rememberInfiniteTransition(label = "cell").animateFloat(0.6f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "cell").value
    } else {
        1f
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value
        val cell = chunkCellSize(chunks.size, widthDp)
        val gap = 2f
        val cols = ((widthDp + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        val rows = ceil(chunks.size / cols.toDouble()).toInt()
        val height = (rows * (cell + gap) - gap).dp
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            val cellPx = cell.dp.toPx()
            val gapPx = gap.dp.toPx()
            val outline = 1.dp.toPx()
            chunks.forEachIndexed { i, chunk ->
                val topLeft = Offset((i % cols) * (cellPx + gapPx), (i / cols) * (cellPx + gapPx))
                val size = Size(cellPx, cellPx)
                val corner = CornerRadius(1.dp.toPx())
                when {
                    chunk.index == inFlight -> {
                        drawRoundRect(c.accentYellow.copy(alpha = pulse), topLeft, size, corner)
                        drawRoundRect(c.stroke, topLeft + Offset(outline / 2, outline / 2), Size(cellPx - outline, cellPx - outline), corner, style = Stroke(outline))
                    }
                    chunk.status == ChunkStatus.DONE -> drawRoundRect(c.inkPrimary, topLeft, size, corner)
                    chunk.status == ChunkStatus.FAILED -> drawRoundRect(c.danger, topLeft, size, corner)
                    else -> drawRoundRect(c.bg, topLeft + Offset(outline / 2, outline / 2), Size(cellPx - outline, cellPx - outline), corner, style = Stroke(outline))
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    val c = Mint.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(stringResource(R.string.legend_done)) { Modifier.background(c.inkPrimary) }
        LegendItem(stringResource(R.string.legend_waiting)) { Modifier.border(1.dp, c.bg) }
        LegendItem(stringResource(R.string.legend_moving)) { Modifier.background(c.accentYellow).border(1.dp, c.stroke) }
        LegendItem(stringResource(R.string.legend_failed)) { Modifier.background(c.danger) }
    }
}

@Composable
private fun LegendItem(label: String, swatch: () -> Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).then(swatch()))
        Spacer(Modifier.width(4.dp))
        Text(label, style = startCaption)
    }
}

// ---- Activity ----

private const val ACTIVITY_PAGE = 20

@Composable
fun ActivityCard(ui: DetailUi) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val entries = if (showAll) ui.activity else ui.activity.take(ACTIVITY_PAGE)
    val c = Mint.colors
    MintCard {
        CardTitle(stringResource(R.string.activity_title))
        Spacer(Modifier.height(12.dp))
        entries.forEachIndexed { i, entry ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                val dot = when (entry.dot) {
                    ActivityDot.DANGER -> c.danger
                    ActivityDot.YELLOW -> c.accentYellow
                    ActivityDot.STROKE -> c.stroke
                }
                val first = i == 0
                val last = i == entries.lastIndex
                // 8 dp dot on a 1.5 dp vertical line.
                Box(
                    Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                        .drawBehind {
                            val x = size.width / 2
                            val dotY = 10.dp.toPx()
                            val top = if (first) dotY else 0f
                            val bottom = if (last) dotY else size.height
                            drawLine(c.stroke, Offset(x, top), Offset(x, bottom), 1.5.dp.toPx())
                            drawCircle(dot, radius = 4.dp.toPx(), center = Offset(x, dotY))
                            if (dot == c.accentYellow) drawCircle(c.stroke, radius = 4.dp.toPx(), center = Offset(x, dotY), style = Stroke(1.dp.toPx()))
                        },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 12.dp)) {
                    Text(entry.message.text(), style = startBody, color = c.inkPrimary)
                    Text(Format.activityTime(entry.timestamp, ui.now), style = startCaption)
                }
            }
        }
        if (!showAll && ui.activity.size > ACTIVITY_PAGE) {
            TextButton(onClick = { showAll = true }) {
                Text(stringResource(R.string.activity_show_all, ui.activity.size), style = Mint.type.label, color = c.inkPrimary)
            }
        }
    }
}

// ---- Details ----

@Composable
fun DetailsCard(ui: DetailUi) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandedText = stringResource(R.string.state_expanded)
    val collapsedText = stringResource(R.string.state_collapsed)
    val reduced = LocalReducedMotion.current
    MintCard {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .semantics { stateDescription = if (expanded) expandedText else collapsedText },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardTitle(stringResource(R.string.details_title), Modifier.weight(1f))
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = Mint.colors.stroke,
                modifier = Modifier.rotate(chevronRotation(expanded)),
            )
        }
        AnimatedVisibility(
            expanded,
            enter = if (reduced) androidx.compose.animation.EnterTransition.None else expandVertically(Motion.standard()),
            exit = if (reduced) androidx.compose.animation.ExitTransition.None else shrinkVertically(Motion.standard()),
        ) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRows(ui)
            }
        }
    }
}

@Composable
private fun DetailRows(ui: DetailUi) {
    val row = ui.item.row
    val notYet = stringResource(R.string.details_not_yet)
    val isDownload = row.type == TransferType.DOWNLOAD
    val terminal = row.state == TransferState.COMPLETED || row.state == TransferState.CANCELLED
    val finishedAt = when (row.state) {
        TransferState.COMPLETED -> row.completedAt ?: row.updatedAt
        TransferState.CANCELLED -> row.updatedAt
        else -> null
    }
    val end = finishedAt ?: ui.now
    val durationMs = (end - row.createdAt).coerceAtLeast(0)
    val bytes = if (row.state == TransferState.COMPLETED) row.fileSize else ui.item.bytes

    Field(stringResource(R.string.details_direction), stringResource(if (isDownload) R.string.details_direction_download else R.string.details_direction_upload))
    Field(stringResource(R.string.details_size), stringResource(R.string.details_size_bytes, Format.exactBytes(row.fileSize)))
    Field(stringResource(R.string.details_pieces), stringResource(R.string.details_pieces_value, row.totalChunks, Format.size(row.chunkSize.toLong())))
    if (ui.item.instant) {
        Field(stringResource(R.string.details_data_sent), stringResource(R.string.details_data_sent_none))
    }
    Field(stringResource(R.string.details_transfer_id), row.id, mono = true, copy = true)
    Field(stringResource(R.string.details_server_id), row.remoteId ?: notYet, mono = row.remoteId != null, copy = row.remoteId != null)
    Field(
        stringResource(R.string.details_saved_to),
        when {
            isDownload -> android.net.Uri.parse(row.localUri).path ?: row.localUri
            ui.generated -> stringResource(R.string.details_generated_file)
            else -> stringResource(R.string.details_picked_file)
        },
    )
    Field(stringResource(R.string.details_expected_sha), row.sha256 ?: notYet, mono = row.sha256 != null)
    val verified = ui.verifiedSha ?: row.sha256.takeIf { row.state == TransferState.COMPLETED }
    Field(stringResource(R.string.details_verified_sha), verified ?: notYet, mono = verified != null)
    if (isDownload) Field(stringResource(R.string.details_etag), row.etag ?: notYet, mono = row.etag != null)
    Field(stringResource(R.string.details_attempts), row.attemptCount.toString())
    Field(stringResource(R.string.details_started), Format.dateTime(row.createdAt))
    if (terminal && finishedAt != null) Field(stringResource(R.string.details_finished), Format.dateTime(finishedAt))
    Field(stringResource(R.string.details_duration), Format.duration(durationMs))
    val avg = if (durationMs > 0) Format.speed(bytes * 1_000.0 / durationMs) else null
    Field(stringResource(R.string.details_average_speed), avg ?: notYet)
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false, copy: Boolean = false) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = startCaption)
            Text(
                value,
                style = if (mono) startBody.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp) else startBody,
                color = Mint.colors.inkPrimary,
            )
        }
        if (copy) {
            IconButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) }
                copiedToast(context)
            }) {
                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy, label), tint = Mint.colors.stroke, modifier = Modifier.size(20.dp))
            }
        }
    }
}
