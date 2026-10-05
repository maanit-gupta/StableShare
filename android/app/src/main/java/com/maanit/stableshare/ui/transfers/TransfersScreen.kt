package com.maanit.stableshare.ui.transfers

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.WifiOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.data.settings.ServerProfile
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.engine.NetworkState
import com.maanit.stableshare.ui.components.Banner
import com.maanit.stableshare.ui.components.CancelTransferDialog
import com.maanit.stableshare.ui.components.CountPill
import com.maanit.stableshare.ui.components.DemoTip
import com.maanit.stableshare.ui.components.NeutralTextButton
import com.maanit.stableshare.ui.components.OutlinedNeutralButton
import com.maanit.stableshare.ui.components.PrimaryButton
import com.maanit.stableshare.ui.components.SectionHeader
import com.maanit.stableshare.ui.components.SegmentedControl
import com.maanit.stableshare.ui.components.ServerPill
import com.maanit.stableshare.ui.components.dot
import com.maanit.stableshare.ui.components.StatusBarScrim
import com.maanit.stableshare.ui.components.TransferRow
import com.maanit.stableshare.ui.mascot.CloudPlane
import com.maanit.stableshare.ui.mascot.MascotIllustration
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.nav.Routes
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
    /** Opens Settings, scrolled to [Routes.SECTION_SERVER] when given. */
    onOpenSettings: (section: String?) -> Unit,
    downloadSheet: @Composable (onDismiss: () -> Unit) -> Unit,
    /** The "Try a demo" sheet. */
    demoSheet: @Composable (onDismiss: () -> Unit) -> Unit,
    /** The first-run "Where should files go?" sheet; [onDone] runs once it has saved and slid away. */
    serverSheet: @Composable (onDone: () -> Unit) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val network by vm.networkState.collectAsStateWithLifecycle()
    val reachable by vm.serverReachable.collectAsStateWithLifecycle()
    val health by vm.serverHealth.collectAsStateWithLifecycle()
    var chooser by rememberSaveable { mutableStateOf(false) }
    var download by rememberSaveable { mutableStateOf(false) }
    var limitSheet by rememberSaveable { mutableStateOf(false) }
    var serverChoice by rememberSaveable { mutableStateOf(false) }
    var demo by rememberSaveable { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<TransferItem?>(null) }
    /** The one Active card showing its details (UI-SPEC §12.6). */
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val view = LocalView.current

    // Health on resume (at most once per 30 s, the ViewModel throttles) and while visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshHealth()
                delay(TransfersViewModel.HEALTH_INTERVAL_MS)
            }
        }
    }
    // A new server (or settings just loaded) is checked at once.
    LaunchedEffect(ui.loaded, ui.serverUrl) { vm.refreshHealth() }
    LaunchedEffect(ui.loaded, ui.serverChosen) { if (ui.loaded && !ui.serverChosen) serverChoice = true }
    val waking = health == ServerHealth.Waking
    val header: @Composable () -> Unit = {
        Header(ui, health, onOpenServer = { onOpenSettings(Routes.SECTION_SERVER) }, onOpenLimit = { limitSheet = true })
    }

    val onAction: (TransferItem, TransferAction) -> Unit = { item, action ->
        if (action == TransferAction.PAUSE || action == TransferAction.RESUME) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (action == TransferAction.CANCEL) cancelTarget = item else vm.perform(item.id, action)
    }
    val onToggle: (String) -> Unit = { id -> expandedId = id.takeIf { it != expandedId } }

    // Success haptic once per transfer that reaches Verified on this screen (only those linger in Active).
    val verified = ui.active.filter { it.state == TransferState.COMPLETED }.map { it.id }
    val celebrated = remember { HashSet<String>() }
    LaunchedEffect(verified) {
        if (celebrated.addAll(verified)) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Neutral.colors.page)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        if (ui.isEmpty) {
            Column(Modifier.fillMaxSize().padding(top = top + 24.dp, start = 24.dp, end = 24.dp)) {
                header()
                BannerSlot(network, ui.wifiOnly, reachable, ui.serverUrl, onOpenSettings)
                EmptyState(
                    waking = waking,
                    onUpload = onOpenUpload,
                    onDownload = { download = true },
                    onDemo = { demo = true },
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
                        header()
                        BannerSlot(network, ui.wifiOnly, reachable, ui.serverUrl, onOpenSettings)
                    }
                }
                section("active", R.string.section_active, ui.active, ui, onOpenDetail, onAction, vm::dismissTip, expandedId, onToggle)
                section("waiting", R.string.section_waiting, ui.waiting, ui, onOpenDetail, onAction, vm::dismissTip)
                section("attention", R.string.section_attention, ui.attention, ui, onOpenDetail, onAction, vm::dismissTip)
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
    if (demo) demoSheet { demo = false }
    if (limitSheet) LimitSheet(ui.limit, onSelect = vm::setMaxConcurrent, onDismiss = { limitSheet = false })
    if (serverChoice) serverSheet { serverChoice = false }
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
private fun Header(ui: TransfersUi, health: ServerHealth?, onOpenServer: () -> Unit, onOpenLimit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.transfers_title), style = Neutral.type.title, modifier = Modifier.weight(1f))
        ServerPill(
            label = stringResource(
                when (ui.serverProfile) {
                    ServerProfile.HOSTED -> R.string.server_pill_hosted
                    ServerProfile.EMULATOR, ServerProfile.LAN -> R.string.server_pill_local
                    ServerProfile.CUSTOM -> R.string.server_pill_custom
                },
            ),
            dot = health.dot(),
            onClick = onOpenServer,
            onClickLabel = stringResource(R.string.cd_open_server_settings),
        )
        Spacer(Modifier.width(8.dp))
        CountPill(
            stringResource(R.string.limit_pill_label),
            ui.limit,
            onClick = onOpenLimit,
            onClickLabel = stringResource(R.string.cd_change_limit),
        )
    }
    Spacer(Modifier.height(4.dp))
    val speed = Format.speed(ui.speed)
    val subtitle = when {
        ui.activeCount == 0 && ui.waitingCount == 0 -> stringResource(R.string.transfers_idle)
        speed != null -> stringResource(R.string.transfers_summary_speed, ui.activeCount, ui.waitingCount, Format.unbreakable(speed))
        else -> stringResource(R.string.transfers_summary, ui.activeCount, ui.waitingCount)
    }
    Text(subtitle, style = Neutral.type.subtitle)
}

/** At most one banner: offline, then Wi-Fi only over a metered network, then server unreachable (UI-SPEC §5.4). */
@Composable
internal fun BannerSlot(
    network: NetworkState,
    wifiOnly: Boolean,
    reachable: Boolean,
    serverUrl: String,
    onOpenSettings: (section: String?) -> Unit,
) {
    when {
        network == NetworkState.Offline -> {
            Spacer(Modifier.height(16.dp))
            Banner(Icons.Outlined.CloudOff, stringResource(R.string.banner_offline))
        }
        wifiOnly && network == NetworkState.Metered -> {
            Spacer(Modifier.height(16.dp))
            Banner(Icons.Outlined.WifiOff, stringResource(R.string.banner_wifi_only))
        }
        !reachable -> {
            Spacer(Modifier.height(16.dp))
            Banner(Icons.Outlined.Dns, stringResource(R.string.banner_server_unreachable, serverUrl)) {
                NeutralTextButton(
                    stringResource(R.string.banner_server_action),
                    onClick = { onOpenSettings(null) },
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
    onDismissTip: () -> Unit,
    expandedId: String? = null,
    /** Set for Active: tapping a card toggles its details instead of opening Detail. */
    onToggle: ((String) -> Unit)? = null,
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
            onOpen = { if (onToggle != null) onToggle(item.id) else onOpen(item.id) },
            onAction = { onAction(item, it) },
            demo = item.id in ui.demo,
            tip = if (item.id == ui.tipFor) {
                { DemoTip(onDismiss = onDismissTip) }
            } else {
                null
            },
            details = onToggle?.let { { ActiveDetails(item, ui, onViewDetails = { onOpen(item.id) }) } },
            expanded = item.id == expandedId,
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

/** The Active card's details block (UI-SPEC §12.6): only facts the engine already tracks, then "View details". */
@Composable
private fun ActiveDetails(item: TransferItem, ui: TransfersUi, onViewDetails: () -> Unit) {
    val total = item.row.totalChunks
    val retries = item.row.attemptCount
    val resumes = ui.resumes[item.id] ?: 0
    val facts = buildList {
        if (total > 0) add(pluralStringResource(R.plurals.row_details_pieces, total, ui.donePieces[item.id] ?: 0, total))
        add(pluralStringResource(R.plurals.row_details_retries, retries, retries))
        add(pluralStringResource(R.plurals.row_details_resumed, resumes, resumes))
        Format.speed(item.liveSpeed)?.let(::add)
    }
    Column(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            facts.forEach { Text(it, style = Neutral.type.meta) }
        }
        NeutralTextButton(
            stringResource(R.string.row_view_details),
            onClick = onViewDetails,
            color = Neutral.colors.inkPrimary,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

/** The happy mood's arrival wave (three 1.2 s waves, see Mascot). */
private const val NIMBUS_CHEER_MS = 3 * 1_200L

/**
 * Empty state (UI-SPEC §5.4.3); while the hosted server wakes, Nimbus searches (§12.4).
 * Tapping the idle Nimbus waves once (§12.6); taps during the wave are ignored.
 */
@Composable
private fun EmptyState(waking: Boolean, onUpload: () -> Unit, onDownload: () -> Unit, onDemo: () -> Unit, modifier: Modifier = Modifier) {
    var cheering by remember { mutableStateOf(false) }
    LaunchedEffect(cheering) {
        if (cheering) {
            delay(NIMBUS_CHEER_MS)
            cheering = false
        }
    }
    val mood = when {
        waking -> MascotMood.SEARCHING
        cheering -> MascotMood.HAPPY
        else -> MascotMood.IDLE
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 24.dp)) {
                MascotIllustration(
                    mood,
                    140.dp,
                    plane = CloudPlane.Perched,
                    modifier = Modifier.pointerInput(waking) { detectTapGestures { if (!waking) cheering = true } },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.empty_title), style = Neutral.type.heading, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (waking) R.string.empty_waking else R.string.empty_body),
                style = Neutral.type.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.empty_upload), onClick = onUpload)
                OutlinedNeutralButton(stringResource(R.string.empty_download), onClick = onDownload)
            }
            Spacer(Modifier.height(8.dp))
            NeutralTextButton(stringResource(R.string.demo_try), onClick = onDemo, color = Neutral.colors.inkPrimary)
        }
    }
}

/** "Transfers at the same time" from the Limit pill: the same 1–4 control as Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitSheet(limit: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Neutral.colors.card,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.settings_concurrency), style = Neutral.type.heading, modifier = Modifier.padding(vertical = 24.dp))
            SegmentedControl(
                options = (SettingsRepository.MIN_CONCURRENT..SettingsRepository.MAX_CONCURRENT).toList(),
                selected = limit,
                label = { it.toString() },
                onSelect = onSelect,
                modifier = Modifier.testTag("limitSheet"),
            )
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

/** A 72 dp sheet option: accent icon circle, title and subtitle; [footer] sits under the subtitle. */
@Composable
internal fun ChooserOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(Neutral.colors.accentTint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Neutral.colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Neutral.type.rowTitle, color = if (enabled) Color.Unspecified else Neutral.colors.inkTertiary)
            Text(subtitle, style = Neutral.type.meta)
            footer?.invoke()
        }
    }
}
