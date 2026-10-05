package com.maanit.stableshare.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.data.net.UnreachableReason
import com.maanit.stableshare.data.settings.ServerChoice
import com.maanit.stableshare.data.settings.ServerProfile
import com.maanit.stableshare.data.settings.ServerProfiles
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.ui.components.NeutralDialog
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.data.net.FaultSettings
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.ui.LocalSnackbar
import com.maanit.stableshare.ui.components.NeutralTextButton
import com.maanit.stableshare.ui.components.OutlinedNeutralButton
import com.maanit.stableshare.ui.components.PrimaryButton
import com.maanit.stableshare.ui.components.SegmentedControl
import com.maanit.stableshare.ui.components.ServerDot
import com.maanit.stableshare.ui.components.StatusDot
import com.maanit.stableshare.ui.components.dot
import com.maanit.stableshare.ui.components.labelColor
import com.maanit.stableshare.ui.nav.Routes
import com.maanit.stableshare.ui.components.SelectChip
import com.maanit.stableshare.ui.components.StatusBarScrim
import com.maanit.stableshare.ui.components.neutralCard
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val MIB = 1024 * 1024
private const val STATS_INTERVAL_MS = 2_000L

/** Settings (UI-SPEC §5.10). [section] ([Routes.SECTION_SERVER] or [Routes.SECTION_TRANSFERS]) opens it scrolled there. */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    section: String?,
    onShowIntro: () -> Unit,
    onOpenLicences: () -> Unit,
    /** The "Try a demo" sheet. */
    demoSheet: @Composable (onDismiss: () -> Unit) -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var demo by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val resources = LocalContext.current.resources
    LaunchedEffect(vm) { vm.snackbarMessages.collect { snackbar.showSnackbar(it.resolve(resources)) } }
    val serverSection = remember { BringIntoViewRequester() }
    val transfersSection = remember { BringIntoViewRequester() }
    LaunchedEffect(section, settings != null) {
        if (settings == null) return@LaunchedEffect
        when (section) {
            Routes.SECTION_SERVER -> serverSection.bringIntoView()
            Routes.SECTION_TRANSFERS -> transfersSection.bringIntoView()
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val s = settings ?: return Box(Modifier.fillMaxSize().background(Neutral.colors.page))

    Box(Modifier.fillMaxSize().background(Neutral.colors.page)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = top + 24.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = Neutral.type.title)

            Section(stringResource(R.string.settings_section_server), Modifier.bringIntoViewRequester(serverSection), card = false) {
                ServerOptions(vm, s)
            }
            Section(stringResource(R.string.settings_section_transfers), Modifier.bringIntoViewRequester(transfersSection)) {
                Label(stringResource(R.string.settings_concurrency))
                SegmentedControl(
                    options = (SettingsRepository.MIN_CONCURRENT..SettingsRepository.MAX_CONCURRENT).toList(),
                    selected = s.maxConcurrent,
                    label = { it.toString() },
                    onSelect = { vm.setMaxConcurrent(it) },
                    modifier = Modifier.testTag("concurrency"),
                )
                Spacer(Modifier.height(16.dp))
                Label(stringResource(R.string.settings_parallel_chunks))
                SegmentedControl(
                    options = SettingsRepository.ALLOWED_PARALLEL_CHUNKS,
                    selected = s.parallelChunks,
                    label = { it.toString() },
                    onSelect = { vm.setParallelChunks(it) },
                    modifier = Modifier.testTag("parallelChunks"),
                )
                Text(stringResource(R.string.settings_parallel_chunks_helper), style = Neutral.type.meta)
                Spacer(Modifier.height(16.dp))
                Label(stringResource(R.string.settings_piece_size))
                SegmentedControl(
                    options = SettingsRepository.ALLOWED_CHUNK_SIZES,
                    selected = s.uploadChunkSizeBytes,
                    label = {
                        stringResource(
                            when (it) {
                                MIB -> R.string.settings_piece_1
                                2 * MIB -> R.string.settings_piece_2
                                else -> R.string.settings_piece_5
                            },
                        )
                    },
                    onSelect = { vm.setPieceSize(it) },
                )
                Text(stringResource(R.string.settings_piece_helper), style = Neutral.type.meta)
                Spacer(Modifier.height(16.dp))
                SettingSwitch(
                    label = stringResource(R.string.settings_auto_retry),
                    helper = stringResource(R.string.settings_auto_retry_helper),
                    checked = s.autoRetryEnabled,
                    onCheckedChange = { vm.setAutoRetry(it) },
                    tag = "autoRetry",
                )
                Spacer(Modifier.height(16.dp))
                SettingSwitch(
                    label = stringResource(R.string.settings_wifi_only),
                    helper = stringResource(R.string.settings_wifi_only_helper),
                    checked = s.wifiOnly,
                    onCheckedChange = { vm.setWifiOnly(it) },
                    tag = "wifiOnly",
                )
            }
            Section(stringResource(R.string.settings_section_simulator)) { SimulatorCard(vm) }
            Section(stringResource(R.string.settings_section_about)) { AboutCard(onShowIntro, onOpenLicences) }
            Section(stringResource(R.string.settings_section_demo)) {
                NeutralTextButton(stringResource(R.string.demo_try), color = Neutral.colors.inkPrimary, onClick = { demo = true })
            }
        }
        StatusBarScrim(Neutral.colors.page)
    }
    if (demo) demoSheet { demo = false }
}

/** A labelled switch with its helper line below (UI-SPEC §5.10: checked track `neutral.inkPrimary`). */
@Composable
private fun SettingSwitch(label: String, helper: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Neutral.colors.inkPrimary,
                checkedThumbColor = Neutral.colors.white,
                uncheckedTrackColor = Neutral.colors.pill,
                uncheckedThumbColor = Neutral.colors.inkTertiary,
                uncheckedBorderColor = Neutral.colors.border,
            ),
        )
    }
    Text(helper, style = Neutral.type.meta)
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, card: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        Spacer(Modifier.height(24.dp))
        Text(title, style = Neutral.type.rowTitle, color = Neutral.colors.inkSecondary)
        Spacer(Modifier.height(8.dp))
        Column(if (card) Modifier.fillMaxWidth().neutralCard().padding(16.dp) else Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Neutral.type.body, modifier = modifier.padding(bottom = 4.dp))
}

private enum class ServerOption { HOSTED, LOCAL, CUSTOM }

private fun ServerProfile.option() = when (this) {
    ServerProfile.HOSTED -> ServerOption.HOSTED
    ServerProfile.EMULATOR, ServerProfile.LAN -> ServerOption.LOCAL
    ServerProfile.CUSTOM -> ServerOption.CUSTOM
}

/** Emulator unless the saved choice is, or last was, a phone on Wi-Fi. */
private fun Settings.localMode() =
    if (serverProfile == ServerProfile.LAN || (serverProfile != ServerProfile.EMULATOR && lanHost.isNotEmpty())) ServerProfile.LAN else ServerProfile.EMULATOR

/**
 * Server option cards (UI-SPEC §12.2). Picking a card saves it and checks it at once; a local
 * address or custom URL that is still blank is saved when typed (Done or leaving the field).
 */
@Composable
private fun ServerOptions(vm: SettingsViewModel, s: Settings) {
    val focus = LocalFocusManager.current
    val check by vm.check.collectAsStateWithLifecycle()
    val pendingSwitch by vm.pendingSwitch.collectAsStateWithLifecycle()
    var picked by rememberSaveable(s.serverProfile) { mutableStateOf(s.serverProfile.option()) }
    var localMode by rememberSaveable(s.serverProfile) { mutableStateOf(s.localMode()) }
    var host by rememberSaveable(s.lanHost) { mutableStateOf(s.lanHost) }
    var port by rememberSaveable(s.lanPort) { mutableStateOf(s.lanPort.toString()) }
    var portInvalid by remember { mutableStateOf(false) }
    var custom by rememberSaveable(s.customUrl) { mutableStateOf(s.customUrl) }

    /** What the cards show; null (with the port error shown) if the port is not a number in range. */
    fun shown(): ServerChoice? = when (picked) {
        ServerOption.HOSTED -> ServerChoice(ServerProfile.HOSTED)
        ServerOption.LOCAL -> if (localMode == ServerProfile.EMULATOR) {
            ServerChoice(ServerProfile.EMULATOR)
        } else {
            val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            portInvalid = p == null
            p?.let { ServerChoice(ServerProfile.LAN, lanHost = host.trim(), lanPort = it) }
        }
        ServerOption.CUSTOM -> ServerChoice(ServerProfile.CUSTOM, customUrl = custom)
    }
    fun isSaved(c: ServerChoice) = c.profile == s.serverProfile && ServerProfiles.urlOf(c) == s.serverUrl
    fun isBlank(c: ServerChoice) = when (c.profile) {
        ServerProfile.LAN -> c.lanHost.isBlank()
        ServerProfile.CUSTOM -> c.customUrl.isBlank()
        ServerProfile.HOSTED, ServerProfile.EMULATOR -> false
    }
    /** Saves what the cards show; a blank address waits for typing unless [typed]. */
    fun save(typed: Boolean = false) {
        val c = shown() ?: return
        if (isSaved(c) || (!typed && isBlank(c))) return
        vm.selectServer(c)
    }
    fun test() {
        val c = shown() ?: return
        if (isSaved(c)) vm.testConnection() else vm.selectServer(c)
    }

    val savedOption = s.serverProfile.option()
    val status = (check as? ConnectionCheck.Result)?.health
    val badge = when (check) {
        ConnectionCheck.Idle -> null
        ConnectionCheck.Checking -> ServerDot.CHECKING
        is ConnectionCheck.Result -> status.dot()
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ServerOptionCard(
            title = stringResource(R.string.settings_server_hosted),
            subtitle = stringResource(R.string.settings_server_hosted_subtitle),
            selected = picked == ServerOption.HOSTED,
            badge = badge.takeIf { savedOption == ServerOption.HOSTED },
            onSelect = { picked = ServerOption.HOSTED; save() },
            tag = "serverHosted",
        )
        ServerOptionCard(
            title = stringResource(R.string.settings_server_local),
            subtitle = stringResource(R.string.settings_server_local_subtitle),
            selected = picked == ServerOption.LOCAL,
            badge = badge.takeIf { savedOption == ServerOption.LOCAL && s.serverProfile == localMode },
            onSelect = { picked = ServerOption.LOCAL; save() },
            tag = "serverLocal",
        ) {
            LocalServerControls(
                mode = localMode,
                onModeChange = { localMode = it; save() },
                host = host,
                onHostChange = { host = it },
                port = port,
                onPortChange = { port = it; portInvalid = false },
                error = if (portInvalid) stringResource(R.string.settings_server_port_invalid) else null,
                onDone = { save(typed = true); focus.clearFocus() },
                onLeave = { save() },
            )
        }
        ServerOptionCard(
            title = stringResource(R.string.settings_server_custom),
            subtitle = stringResource(R.string.settings_server_custom_subtitle),
            selected = picked == ServerOption.CUSTOM,
            badge = badge.takeIf { savedOption == ServerOption.CUSTOM },
            onSelect = { picked = ServerOption.CUSTOM; save() },
            tag = "serverCustom",
        ) {
            ServerField(
                value = custom,
                onValueChange = { custom = it },
                label = stringResource(R.string.settings_server_address),
                keyboardType = KeyboardType.Uri,
                onDone = { save(typed = true); focus.clearFocus() },
                onLeave = { save() },
                modifier = Modifier.fillMaxWidth().testTag("customUrl"),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedNeutralButton(
        stringResource(R.string.settings_test_connection),
        onClick = { focus.clearFocus(); test() },
        modifier = Modifier.fillMaxWidth(),
    )
    ConnectionStatusLine(check)

    if (pendingSwitch != null) {
        NeutralDialog(
            title = stringResource(R.string.switch_dialog_title),
            body = stringResource(R.string.switch_dialog_body),
            dismissLabel = stringResource(R.string.switch_dialog_cancel),
            confirmLabel = stringResource(R.string.switch_dialog_confirm),
            destructive = false,
            onDismiss = {
                vm.cancelSwitch()
                picked = savedOption
                localMode = s.localMode()
            },
            onConfirm = vm::confirmSwitch,
        )
    }
}

/**
 * The Local card's controls (UI-SPEC §12.2): Emulator or Phone on Wi-Fi, then the computer's
 * address and port for a phone. [error] shows below the fields; [onLeave] runs when a field loses focus.
 */
@Composable
internal fun LocalServerControls(
    mode: ServerProfile,
    onModeChange: (ServerProfile) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    error: String?,
    onDone: () -> Unit,
    onLeave: () -> Unit = {},
) {
    SegmentedControl(
        options = listOf(ServerProfile.EMULATOR, ServerProfile.LAN),
        selected = mode,
        label = {
            stringResource(if (it == ServerProfile.EMULATOR) R.string.settings_server_emulator else R.string.settings_server_phone)
        },
        onSelect = onModeChange,
    )
    if (mode == ServerProfile.LAN) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerField(
                value = host,
                onValueChange = onHostChange,
                label = stringResource(R.string.settings_server_host),
                keyboardType = KeyboardType.Uri,
                onDone = onDone,
                onLeave = onLeave,
                modifier = Modifier.weight(1f).testTag("lanHost"),
            )
            ServerField(
                value = port,
                onValueChange = { onPortChange(it.filter(Char::isDigit).take(5)) },
                label = stringResource(R.string.settings_server_port),
                keyboardType = KeyboardType.Number,
                onDone = onDone,
                onLeave = onLeave,
                isError = error != null,
                modifier = Modifier.width(96.dp).testTag("lanPort"),
            )
        }
        if (error != null) {
            Text(error, style = Neutral.type.meta, color = Neutral.colors.danger)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_server_local_helper), style = Neutral.type.meta)
}

/** One selectable server card (UI-SPEC §12.2); [expanded] renders below the subtitle only while selected. */
@Composable
internal fun ServerOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    badge: ServerDot?,
    onSelect: () -> Unit,
    tag: String,
    expanded: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Neutral.colors
    val reduced = LocalReducedMotion.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(c.card, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.inkPrimary else c.border, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .testTag(tag)
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.size(20.dp),
                colors = RadioButtonDefaults.colors(selectedColor = c.inkPrimary, unselectedColor = c.inkSecondary),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Neutral.type.rowTitle, modifier = Modifier.weight(1f))
                    if (selected && badge != null) {
                        StatusDot(badge)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(badge.label), style = Neutral.type.status, color = badge.labelColor())
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = Neutral.type.meta)
            }
        }
        if (expanded != null) {
            AnimatedVisibility(
                selected,
                enter = if (reduced) EnterTransition.None else expandVertically(Motion.standard()),
                exit = if (reduced) ExitTransition.None else shrinkVertically(Motion.standard()),
            ) {
                // Lines up with the title column: 16 dp padding + 20 dp radio + 12 dp gap.
                Column(Modifier.padding(start = 48.dp, end = 16.dp, bottom = 16.dp), content = expanded)
            }
        }
    }
}

/** A Settings text field (UI-SPEC §5.10). [onLeave] runs when focus leaves it. */
@Composable
internal fun ServerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    onDone: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val c = Neutral.colors
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = c.border,
            focusedBorderColor = c.inkPrimary,
            focusedLabelColor = c.inkPrimary,
            cursorColor = c.inkPrimary,
            errorBorderColor = c.danger,
            errorLabelColor = c.danger,
        ),
        textStyle = Neutral.type.body.copy(color = c.inkPrimary),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) onLeave()
            focused = it.isFocused
        },
    )
}

/** The line under "Test connection": the check's result in words (UI-SPEC §12.1 colours). */
@Composable
private fun ConnectionStatusLine(check: ConnectionCheck) {
    val c = Neutral.colors
    val (dot, text) = when (check) {
        ConnectionCheck.Idle -> return
        ConnectionCheck.Checking -> ServerDot.CHECKING to stringResource(R.string.settings_checking)
        is ConnectionCheck.Result -> when (val h = check.health) {
            is ServerHealth.Online -> ServerDot.ONLINE to stringResource(R.string.settings_connected_in, h.latencyMs.toInt())
            ServerHealth.Waking -> ServerDot.WAKING to stringResource(R.string.settings_waking)
            is ServerHealth.Unreachable -> ServerDot.UNREACHABLE to stringResource(
                when (h.reason) {
                    UnreachableReason.NoInternet -> R.string.settings_no_internet
                    UnreachableReason.CleartextBlocked -> R.string.settings_cleartext
                    else -> R.string.settings_unreachable
                },
            )
            ServerHealth.Invalid -> null to stringResource(R.string.settings_server_invalid)
        }
    }
    Row(Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            StatusDot(dot)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = Neutral.type.status,
            color = dot?.labelColor() ?: c.danger,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulatorCard(vm: SettingsViewModel) {
    val c = Neutral.colors
    val faults by vm.faults.collectAsStateWithLifecycle()
    val preset by vm.preset.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current

    // GET /admin/stats every 2 s while this card is on screen and the screen is resumed.
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (bounds.bottom > 0 && bounds.top < view.height) vm.refreshStats()
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    Column(Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Science, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.simulator_notice), style = Neutral.type.meta)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Preset.entries.forEach { p ->
                SelectChip(stringResource(p.label), selected = preset == p, onClick = { vm.selectPreset(p) })
            }
        }
        val expandedText = stringResource(R.string.state_expanded)
        val collapsedText = stringResource(R.string.state_collapsed)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .semantics { stateDescription = if (expanded) expandedText else collapsedText },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.simulator_advanced), style = Neutral.type.rowTitle, modifier = Modifier.weight(1f))
            val angle by animateFloatAsState(if (expanded) 180f else 0f, Motion.standard(), label = "chevron")
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.rotate(angle))
        }
        AnimatedVisibility(
            expanded,
            enter = if (reduced) EnterTransition.None else expandVertically(Motion.standard()),
            exit = if (reduced) ExitTransition.None else shrinkVertically(Motion.standard()),
        ) {
            Sliders(faults ?: Preset.OFF.faults, vm::editFaults)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.simulator_apply), onClick = vm::apply, modifier = Modifier.weight(1f))
            OutlinedNeutralButton(stringResource(R.string.simulator_reset), onClick = vm::reset, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.page, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val st = stats
            StatRow(stringResource(R.string.stats_requests), st?.apiRequests)
            StatRow(stringResource(R.string.stats_errors), st?.faults?.error)
            StatRow(stringResource(R.string.stats_timeouts), st?.faults?.timeout)
            StatRow(stringResource(R.string.stats_dropped), st?.faults?.dropMidBody)
            StatRow(stringResource(R.string.stats_lost), st?.faults?.dropAfterProcess)
            StatRow(stringResource(R.string.stats_corrupted), st?.faults?.corrupt)
            StatRow(stringResource(R.string.stats_duplicates), st?.dedupedChunks)
        }
    }
}

@Composable
private fun StatRow(label: String, value: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Neutral.type.body, modifier = Modifier.weight(1f))
        Text((value ?: 0).toString(), style = Neutral.type.rowTitle.copy(fontSize = 14.sp))
    }
}

@Composable
private fun Sliders(f: FaultSettings, edit: ((FaultSettings) -> FaultSettings) -> Unit) {
    Column {
        FaultSlider(stringResource(R.string.slider_latency), f.latencyMs.toFloat(), 0f..3_000f, 50f, stringResource(R.string.value_ms, f.latencyMs)) { v ->
            edit { it.copy(latencyMs = v.roundToInt()) }
        }
        FaultSlider(stringResource(R.string.slider_jitter), f.latencyJitterMs.toFloat(), 0f..1_500f, 50f, stringResource(R.string.value_ms, f.latencyJitterMs)) { v ->
            edit { it.copy(latencyJitterMs = v.roundToInt()) }
        }
        val bandwidth = if (f.bandwidthKbps == 0) stringResource(R.string.value_unlimited) else stringResource(R.string.value_kbps, f.bandwidthKbps)
        FaultSlider(stringResource(R.string.slider_bandwidth), f.bandwidthKbps.toFloat(), 0f..10_240f, 128f, bandwidth) { v ->
            edit { it.copy(bandwidthKbps = v.roundToInt()) }
        }
        RateSlider(stringResource(R.string.slider_error_rate), f.errorRate) { r -> edit { it.copy(errorRate = r) } }
        RateSlider(stringResource(R.string.slider_timeout_rate), f.timeoutRate) { r -> edit { it.copy(timeoutRate = r) } }
        RateSlider(stringResource(R.string.slider_drop_mid_rate), f.dropMidBodyRate) { r -> edit { it.copy(dropMidBodyRate = r) } }
        RateSlider(stringResource(R.string.slider_lost_response_rate), f.dropAfterProcessRate) { r -> edit { it.copy(dropAfterProcessRate = r) } }
        RateSlider(stringResource(R.string.slider_corruption_rate), f.corruptRate) { r -> edit { it.copy(corruptRate = r) } }
    }
}

@Composable
private fun RateSlider(label: String, rate: Double, onChange: (Double) -> Unit) {
    val percent = (rate * 100).roundToInt()
    FaultSlider(label, percent.toFloat(), 0f..100f, 1f, stringResource(R.string.value_percent, percent)) { v ->
        onChange(v.roundToInt() / 100.0)
    }
}

@Composable
private fun FaultSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, shown: String, onChange: (Float) -> Unit) {
    val c = Neutral.colors
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text(label, style = Neutral.type.body, modifier = Modifier.weight(1f))
            Text(shown, style = Neutral.type.meta)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = c.inkPrimary,
                activeTrackColor = c.inkPrimary,
                inactiveTrackColor = c.track,
                activeTickColor = Neutral.colors.inkPrimary.copy(alpha = 0f),
                inactiveTickColor = Neutral.colors.track.copy(alpha = 0f),
            ),
        )
    }
}

@Composable
private fun AboutCard(onShowIntro: () -> Unit, onOpenLicences: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val sourceUrl = stringResource(R.string.about_source_url)
    val ink = Neutral.colors.inkPrimary
    Text(stringResource(R.string.about_version, version), style = Neutral.type.body)
    NeutralTextButton(stringResource(R.string.about_source), color = ink, onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl))) }
    })
    NeutralTextButton(stringResource(R.string.about_intro), color = ink, onClick = onShowIntro)
    NeutralTextButton(stringResource(R.string.about_licences), color = ink, onClick = onOpenLicences)
}
