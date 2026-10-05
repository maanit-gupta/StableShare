package com.maanit.stableshare.ui.history

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.FileIntents
import com.maanit.stableshare.ui.LocalSnackbar
import com.maanit.stableshare.ui.components.CardShape
import com.maanit.stableshare.ui.components.CheckBadge
import com.maanit.stableshare.ui.components.FileIcon
import com.maanit.stableshare.ui.components.DemoPill
import com.maanit.stableshare.ui.components.InstantPill
import com.maanit.stableshare.ui.components.NeutralDialog
import com.maanit.stableshare.ui.components.NeutralTextButton
import com.maanit.stableshare.ui.components.SelectChip
import com.maanit.stableshare.ui.components.neutralCard
import com.maanit.stableshare.ui.mascot.MascotIllustration
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import kotlinx.coroutines.launch
import java.time.LocalDate

/** History (UI-SPEC §5.9, §12.7). */
@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpenTransfers: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    /** The one row showing its details (UI-SPEC §12.3). */
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val snackbar = LocalSnackbar.current
    val resources = LocalContext.current.resources
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Newest first: a transfer that just finished is inserted above the first visible row, so
    // keep the list at the top when it was already there.
    LaunchedEffect(ui.items.firstOrNull()?.row?.id) {
        if (listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0)
    }

    fun show(message: String) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(message) }
    }

    /** Hides [ids] now; deletes them unless "Undo" is tapped before the snackbar goes (§12.7). */
    fun removeWithUndo(ids: List<String>, message: Int) {
        if (ids.isEmpty()) return
        vm.hide(ids)
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar(
                    resources.getString(message),
                    actionLabel = resources.getString(R.string.history_undo),
                    duration = SnackbarDuration.Short,
                ) == SnackbarResult.ActionPerformed
            } finally {
                // Replaced, timed out or the screen left: the removal stands.
                if (undone) vm.undo(ids) else vm.commit(ids)
            }
        }
    }

    val actions = HistoryRowActions(
        onCopyHash = { sha ->
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SHA-256", sha))) }
            show(resources.getString(R.string.history_hash_copied))
        },
        canRepeat = vm::canRepeat,
        onRepeat = { row ->
            scope.launch {
                val failure = vm.repeat(row)
                if (failure == null) onOpenTransfers() else show(failure.resolve(resources))
            }
        },
    )

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(Neutral.colors.page).padding(top = top + 24.dp)) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history_title), style = Neutral.type.title, modifier = Modifier.weight(1f))
            if (ui.hasHistory) {
                NeutralTextButton(stringResource(R.string.history_clear_all), onClick = { confirmClear = true }, color = Neutral.colors.danger)
            }
        }
        if (ui.hasHistory) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HistoryFilter.ALL to R.string.history_filter_all,
                    HistoryFilter.COMPLETED to R.string.history_filter_completed,
                    HistoryFilter.CANCELLED to R.string.history_filter_cancelled,
                ).forEach { (f, label) ->
                    SelectChip(
                        stringResource(R.string.history_filter_count, stringResource(label), ui.counts[f] ?: 0),
                        selected = ui.filter == f,
                        onClick = { vm.setFilter(f) },
                    )
                }
            }
        }
        if (ui.loaded && ui.items.isEmpty()) {
            EmptyHistory(Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            ) {
                ui.days.forEachIndexed { index, day ->
                    item(key = "day-${day.date}") {
                        DayHeader(day.date, Modifier.animateItem().padding(top = if (index == 0) 0.dp else 4.dp, bottom = 8.dp))
                    }
                    items(day.items, key = { it.row.id }) { item ->
                        SwipeToRemove(
                            onRemove = { removeWithUndo(listOf(item.row.id), R.string.history_removed) },
                            modifier = Modifier.animateItem().padding(bottom = 12.dp),
                        ) {
                            HistoryRow(
                                item,
                                onToggle = { expandedId = item.row.id.takeIf { it != expandedId } },
                                onRemove = { removeWithUndo(listOf(item.row.id), R.string.history_removed) },
                                expanded = item.row.id == expandedId,
                                actions = actions,
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        NeutralDialog(
            title = stringResource(R.string.history_clear_title),
            body = stringResource(R.string.history_clear_body),
            dismissLabel = stringResource(R.string.history_clear_cancel),
            confirmLabel = stringResource(R.string.history_clear_confirm),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                removeWithUndo(ui.allIds, R.string.history_cleared)
            },
        )
    }
}

/** "Today", "Yesterday", then "Oct 4" (UI-SPEC §12.7). */
@Composable
private fun DayHeader(date: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> Format.monthDay(date)
    }
    Text(label, style = Neutral.type.rowTitle, color = Neutral.colors.inkSecondary, modifier = modifier.fillMaxWidth())
}

/** Swipe towards the start to remove (UI-SPEC §12.7); the other direction does nothing. */
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(Modifier.fillMaxSize().background(Neutral.colors.danger, CardShape), contentAlignment = Alignment.CenterEnd) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = Neutral.colors.white, modifier = Modifier.padding(end = 24.dp).size(24.dp))
                }
            }
        },
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() },
    ) { content() }
}

/** Menu and details callbacks for [HistoryRow]; the defaults do nothing (previews, tests). */
internal class HistoryRowActions(
    val onCopyHash: (String) -> Unit = {},
    val canRepeat: suspend (TransferEntity) -> Boolean = { false },
    val onRepeat: (TransferEntity) -> Unit = {},
)

@Composable
internal fun HistoryRow(
    item: HistoryItem,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    actions: HistoryRowActions = HistoryRowActions(),
) {
    val c = Neutral.colors
    val row = item.row
    val context = LocalContext.current
    val completed = row.state == TransferState.COMPLETED
    var menu by remember { mutableStateOf(false) }
    // Rechecked each time the menu opens: the original file may have gone since.
    val canRepeat by produceState(false, row.id, menu) { value = actions.canRepeat(row) }
    val reduced = LocalReducedMotion.current
    val expansion = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val clickLabel = stringResource(if (expanded) R.string.history_hide_details else R.string.history_show_details)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (reduced) Modifier else Modifier.animateContentSize(Motion.standard()))
            .neutralCard()
            .clickable(onClickLabel = clickLabel, onClick = onToggle)
            .semantics { stateDescription = expansion }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            FileIcon(row.fileName, row.type, item.generated)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.fileName, style = Neutral.type.rowTitle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    stringResource(
                        R.string.history_line,
                        Format.unbreakable(Format.size(row.fileSize)),
                        Format.unbreakable(Format.duration(item.durationMs)),
                        Format.unbreakable(Format.size(item.bytesPerSecond.toLong()) + "/s"),
                    ),
                    style = Neutral.type.meta,
                )
                Text(Format.dateTime(item.finishedAt), style = Neutral.type.meta)
                if (item.instant) {
                    Spacer(Modifier.height(4.dp))
                    InstantPill()
                }
                if (item.demo) {
                    Spacer(Modifier.height(4.dp))
                    DemoPill()
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more_options), tint = c.inkSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                    if (completed && row.type == TransferType.DOWNLOAD) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_open_file)) }, onClick = {
                            menu = false
                            FileIntents.open(context, row)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_share_file)) }, onClick = {
                            menu = false
                            FileIntents.share(context, row)
                        })
                    }
                    row.sha256?.let { sha ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_copy_hash)) }, onClick = {
                            menu = false
                            actions.onCopyHash(sha)
                        })
                    }
                    if (canRepeat) {
                        val again = if (row.type == TransferType.UPLOAD) R.string.menu_upload_again else R.string.menu_download_again
                        DropdownMenuItem(text = { Text(stringResource(again)) }, onClick = {
                            menu = false
                            actions.onRepeat(row)
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_remove_from_history)) }, onClick = {
                        menu = false
                        onRemove()
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (completed) {
                Text(stringResource(R.string.history_verified), style = Neutral.type.status, color = c.success)
                row.sha256?.takeIf { it.length >= 12 }?.let { sha ->
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.history_hash, sha.take(8), sha.takeLast(4)), style = Neutral.type.hash)
                }
                Spacer(Modifier.weight(1f))
                CheckBadge(animate = false)
            } else {
                Text(stringResource(R.string.history_cancelled), style = Neutral.type.status, color = c.inkTertiary)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(24.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (expanded) HistoryDetails(item, onCopyHash = actions.onCopyHash)
    }
}

/** The expanded block (UI-SPEC §12.3, §12.7): full hash, then only the facts the row tracks. */
@Composable
private fun HistoryDetails(item: HistoryItem, onCopyHash: (String) -> Unit) {
    val c = Neutral.colors
    val row = item.row
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Spacer(Modifier.height(12.dp))
        row.sha256?.let { sha ->
            Text(sha, style = Neutral.type.hash)
            NeutralTextButton(stringResource(R.string.history_copy), onClick = { onCopyHash(sha) }, color = c.inkPrimary, underline = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.history_started, Format.dateTime(row.createdAt)), style = Neutral.type.meta)
            Text(stringResource(R.string.history_finished, Format.dateTime(item.finishedAt)), style = Neutral.type.meta)
            if (row.totalChunks > 0) {
                Text(pluralStringResource(R.plurals.history_pieces, row.totalChunks, row.totalChunks), style = Neutral.type.meta)
            }
            Text(pluralStringResource(R.plurals.history_retries, row.attemptCount, row.attemptCount), style = Neutral.type.meta)
        }
        if (item.instant) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.history_instant_note), style = Neutral.type.body)
        }
    }
}

/** Empty History (UI-SPEC §5.9): calm cloud, no plane. */
@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            MascotIllustration(MascotMood.CALM, 120.dp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.history_empty_title), style = Neutral.type.heading, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.history_empty_body),
                style = Neutral.type.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
    }
}
