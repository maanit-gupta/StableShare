package com.maanit.stableshare.ui.history

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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.FileIntents
import com.maanit.stableshare.ui.components.CheckBadge
import com.maanit.stableshare.ui.components.FileIcon
import com.maanit.stableshare.ui.components.NeutralDialog
import com.maanit.stableshare.ui.components.NeutralTextButton
import com.maanit.stableshare.ui.components.SelectChip
import com.maanit.stableshare.ui.components.neutralCard
import com.maanit.stableshare.ui.mascot.MascotIllustration
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.theme.Neutral

/** History (UI-SPEC §5.9). */
@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpenDetail: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Newest first: a transfer that just finished is inserted above the first visible row, so
    // keep the list at the top when it was already there.
    LaunchedEffect(ui.items.firstOrNull()?.row?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
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
                    SelectChip(stringResource(label), selected = ui.filter == f, onClick = { vm.setFilter(f) })
                }
            }
        }
        if (ui.loaded && ui.items.isEmpty()) {
            EmptyHistory(Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.items, key = { it.row.id }) { item ->
                    HistoryRow(item, onOpen = { onOpenDetail(item.row.id) }, onRemove = { vm.remove(item.row.id) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
    if (confirmClear) {
        NeutralDialog(
            title = stringResource(R.string.history_clear_title),
            body = stringResource(R.string.history_clear_body),
            dismissLabel = stringResource(R.string.history_clear_keep),
            confirmLabel = stringResource(R.string.history_clear_confirm),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                vm.clearAll()
            },
        )
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = Neutral.colors
    val row = item.row
    val context = LocalContext.current
    val completed = row.state == TransferState.COMPLETED
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .neutralCard()
            .clickable(onClick = onOpen)
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
