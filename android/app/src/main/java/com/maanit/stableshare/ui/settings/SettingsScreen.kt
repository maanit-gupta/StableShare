package com.maanit.stableshare.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.maanit.stableshare.ui.components.SelectChip
import com.maanit.stableshare.ui.components.neutralCard
import com.maanit.stableshare.ui.model.text
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MIB = 1024 * 1024
private const val STATS_INTERVAL_MS = 2_000L

/** Settings (UI-SPEC §5.10). [scrollToTransfers] opens it at the Transfers section (from the Limit pill). */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    scrollToTransfers: Boolean,
    onShowIntro: () -> Unit,
    onOpenLicences: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val resources = LocalContext.current.resources
    LaunchedEffect(vm) { vm.snackbarMessages.collect { snackbar.showSnackbar(it.resolve(resources)) } }
    val transfersSection = remember { BringIntoViewRequester() }
    LaunchedEffect(scrollToTransfers, settings != null) {
        if (scrollToTransfers && settings != null) transfersSection.bringIntoView()
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val s = settings ?: return Box(Modifier.fillMaxSize().background(Neutral.colors.page))

    Column(
        Modifier
            .fillMaxSize()
            .background(Neutral.colors.page)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = top + 24.dp, bottom = 24.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = Neutral.type.title)

        Section(stringResource(R.string.settings_section_server)) { ServerCard(vm, s.serverUrl) }
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
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.settings_auto_retry), Modifier.weight(1f))
                Switch(
                    checked = s.autoRetryEnabled,
                    onCheckedChange = { vm.setAutoRetry(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Neutral.colors.inkPrimary,
                        checkedThumbColor = Neutral.colors.white,
                        uncheckedTrackColor = Neutral.colors.pill,
                        uncheckedThumbColor = Neutral.colors.inkTertiary,
                        uncheckedBorderColor = Neutral.colors.border,
                    ),
                )
            }
            Text(stringResource(R.string.settings_auto_retry_helper), style = Neutral.type.meta)
        }
        Section(stringResource(R.string.settings_section_simulator)) { SimulatorCard(vm) }
        Section(stringResource(R.string.settings_section_about)) { AboutCard(onShowIntro, onOpenLicences) }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        Spacer(Modifier.height(24.dp))
        Text(title, style = Neutral.type.rowTitle, color = Neutral.colors.inkSecondary)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().neutralCard().padding(16.dp), content = content)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Neutral.type.body, modifier = modifier.padding(bottom = 4.dp))
}

@Composable
private fun ServerCard(vm: SettingsViewModel, saved: String) {
    val c = Neutral.colors
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    var invalid by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    fun save(then: () -> Unit = {}) {
        scope.launch {
            invalid = !vm.saveServerUrl(text)
            if (!invalid) then()
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            invalid = false
        },
        label = { Text(stringResource(R.string.settings_server_address)) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.settings_server_invalid), color = c.danger) }
        } else {
            null
        },
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            save()
            focus.clearFocus()
        }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = c.border,
            focusedBorderColor = c.inkPrimary,
            focusedLabelColor = c.inkPrimary,
            cursorColor = c.inkPrimary,
            errorBorderColor = c.danger,
            errorLabelColor = c.danger,
        ),
        textStyle = Neutral.type.body.copy(color = c.inkPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (focused && !it.isFocused) save()
                focused = it.isFocused
            },
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_server_helper), style = Neutral.type.meta)
    Spacer(Modifier.height(12.dp))
    OutlinedNeutralButton(
        stringResource(R.string.settings_test_connection),
        onClick = { save { vm.testConnection() } },
        modifier = Modifier.fillMaxWidth(),
    )
    val check by vm.check.collectAsStateWithLifecycle()
    when (val result = check) {
        ConnectionCheck.Idle -> Unit
        ConnectionCheck.Checking -> ResultLine {
            CircularProgressIndicator(Modifier.size(16.dp), color = c.inkSecondary, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_checking), style = Neutral.type.body)
        }
        ConnectionCheck.Connected -> ResultLine {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = c.success, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_connected), style = Neutral.type.body)
        }
        is ConnectionCheck.Failed -> ResultLine {
            Icon(Icons.Outlined.Error, contentDescription = null, tint = c.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_couldnt_connect, result.reason.text()), style = Neutral.type.body)
        }
    }
}

@Composable
private fun ResultLine(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
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
