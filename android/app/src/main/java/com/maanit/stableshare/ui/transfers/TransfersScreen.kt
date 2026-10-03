package com.maanit.stableshare.ui.transfers

import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.ui.components.Banner
import com.maanit.stableshare.ui.components.CancelTransferDialog
import com.maanit.stableshare.ui.components.CountPill
import com.maanit.stableshare.ui.components.NeutralTextButton
import com.maanit.stableshare.ui.components.OutlinedNeutralButton
import com.maanit.stableshare.ui.components.PrimaryButton
import com.maanit.stableshare.ui.components.SectionHeader
import com.maanit.stableshare.ui.components.StatusBarScrim
import com.maanit.stableshare.ui.components.TransferRow
import com.maanit.stableshare.ui.mascot.CloudPlane
import com.maanit.stableshare.ui.mascot.MascotIllustration
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import kotlinx.coroutines.delay

/** Transfers (UI-SPEC §5.4). */
@Composable
fun TransfersScreen(
    vm: TransfersViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenUpload: () -> Unit,
    onOpenSettings: (transfersSection: Boolean) -> Unit,
    downloadSheet: @Composable (onDismiss: () -> Unit) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val online by vm.isOnline.collectAsStateWithLifecycle()
    val reachable by vm.serverReachable.collectAsStateWithLifecycle()
    var chooser by rememberSaveable { mutableStateOf(false) }
    var download by rememberSaveable { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<TransferItem?>(null) }

    // GET /health when the screen resumes and every 30 s while it is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.checkHealth()
                delay(TransfersViewModel.HEALTH_INTERVAL_MS)
            }
        }
    }

    val onAction: (TransferItem, TransferAction) -> Unit = { item, action ->
        if (action == TransferAction.CANCEL) cancelTarget = item else vm.perform(item.id, action)
    }

    Box(Modifier.fillMaxSize().background(Neutral.colors.page)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        if (ui.isEmpty) {
            Column(Modifier.fillMaxSize().padding(top = top + 24.dp, start = 24.dp, end = 24.dp)) {
                Header(ui, onOpenSettings)
                BannerSlot(online, reachable, ui.serverUrl, onOpenSettings)
                EmptyState(
                    onUpload = onOpenUpload,
                    onDownload = { download = true },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = top + 24.dp, bottom = 96.dp),
            ) {
                item(key = "header") {
                    Column {
                        Header(ui, onOpenSettings)
                        BannerSlot(online, reachable, ui.serverUrl, onOpenSettings)
                    }
                }
                section("active", R.string.section_active, ui.active, ui, onOpenDetail, onAction)
                section("waiting", R.string.section_waiting, ui.waiting, ui, onOpenDetail, onAction)
                section("attention", R.string.section_attention, ui.attention, ui, onOpenDetail, onAction)
            }
            FloatingActionButton(
                onClick = { chooser = true },
                shape = RoundedCornerShape(16.dp),
                containerColor = Neutral.colors.accent,
                contentColor = Neutral.colors.inkPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp).size(56.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.fab_new_transfer), modifier = Modifier.size(24.dp))
            }
        }
        StatusBarScrim(Neutral.colors.page)
    }

    if (chooser) {
        ChooserSheet(
            onDismiss = { chooser = false },
            onUpload = {
                chooser = false
                onOpenUpload()
            },
            onDownload = {
                chooser = false
                download = true
            },
        )
    }
    if (download) downloadSheet { download = false }
    cancelTarget?.let { target ->
        CancelTransferDialog(
            fileName = target.name,
            onDismiss = { cancelTarget = null },
            onConfirm = {
                cancelTarget = null
                vm.perform(target.id, TransferAction.CANCEL)
            },
        )
    }
}

@Composable
private fun Header(ui: TransfersUi, onOpenSettings: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.transfers_title), style = Neutral.type.title, modifier = Modifier.weight(1f))
        CountPill(
            stringResource(R.string.limit_pill_label),
            ui.limit,
            onClick = { onOpenSettings(true) },
            onClickLabel = stringResource(R.string.cd_open_transfer_settings),
        )
    }
    Spacer(Modifier.height(4.dp))
    val speed = Format.speed(ui.speed)
    val subtitle = when {
        ui.activeCount == 0 && ui.waitingCount == 0 -> stringResource(R.string.transfers_idle)
        speed != null -> stringResource(R.string.transfers_summary_speed, ui.activeCount, ui.waitingCount, speed)
        else -> stringResource(R.string.transfers_summary, ui.activeCount, ui.waitingCount)
    }
    Text(subtitle, style = Neutral.type.subtitle)
}

/** At most one banner: offline first, then server unreachable (UI-SPEC §5.4). */
@Composable
private fun BannerSlot(online: Boolean, reachable: Boolean, serverUrl: String, onOpenSettings: (Boolean) -> Unit) {
    when {
        !online -> {
            Spacer(Modifier.height(16.dp))
            Banner(Icons.Outlined.CloudOff, stringResource(R.string.banner_offline))
        }
        !reachable -> {
            Spacer(Modifier.height(16.dp))
            Banner(Icons.Outlined.Dns, stringResource(R.string.banner_server_unreachable, serverUrl)) {
                NeutralTextButton(
                    stringResource(R.string.banner_server_action),
                    onClick = { onOpenSettings(false) },
                    color = Neutral.colors.inkPrimary,
                    underline = true,
                )
            }
        }
    }
}

private fun LazyListScope.section(
    key: String,
    label: Int,
    items: List<TransferItem>,
    ui: TransfersUi,
    onOpen: (String) -> Unit,
    onAction: (TransferItem, TransferAction) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "section-$key") {
        SectionHeader(
            stringResource(label),
            items.size,
            Modifier
                .animateItem()
                .padding(top = 24.dp, bottom = 8.dp),
        )
    }
    items(items, key = { it.id }) { item ->
        val reduced = LocalReducedMotion.current
        TransferRow(
            item = item,
            generated = item.id in ui.generated,
            onOpen = { onOpen(item.id) },
            onAction = { onAction(item, it) },
            modifier = Modifier
                .animateItem(
                    fadeInSpec = if (reduced) null else tween(Motion.STANDARD_MS),
                    placementSpec = if (reduced) null else tween(Motion.STANDARD_MS),
                    fadeOutSpec = if (reduced) null else tween(Motion.STANDARD_MS),
                )
                .padding(bottom = 12.dp),
        )
    }
}

/** Empty state (UI-SPEC §5.4.3). */
@Composable
private fun EmptyState(onUpload: () -> Unit, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 24.dp)) {
                MascotIllustration(MascotMood.IDLE, 140.dp, plane = CloudPlane.Perched)
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.empty_title), style = Neutral.type.heading, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.empty_body),
                style = Neutral.type.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.empty_upload), onClick = onUpload)
                OutlinedNeutralButton(stringResource(R.string.empty_download), onClick = onDownload)
            }
        }
    }
}

/** "New transfer" chooser (UI-SPEC §5.4.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooserSheet(onDismiss: () -> Unit, onUpload: () -> Unit, onDownload: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Neutral.colors.card,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.chooser_title), style = Neutral.type.heading, modifier = Modifier.padding(24.dp))
            ChooserOption(
                Icons.Outlined.Upload,
                stringResource(R.string.chooser_upload_title),
                stringResource(R.string.chooser_upload_subtitle),
                onUpload,
            )
            ChooserOption(
                Icons.Outlined.Download,
                stringResource(R.string.chooser_download_title),
                stringResource(R.string.chooser_download_subtitle),
                onDownload,
            )
        }
    }
}

@Composable
private fun ChooserOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(Neutral.colors.accentTint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Neutral.colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Neutral.type.rowTitle)
            Text(subtitle, style = Neutral.type.meta)
        }
    }
}
