package com.maanit.stableshare.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.R
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.data.settings.ServerChoice
import com.maanit.stableshare.data.settings.ServerProfile
import com.maanit.stableshare.data.settings.ServerProfiles
import com.maanit.stableshare.ui.components.PrimaryButton
import com.maanit.stableshare.ui.components.dot
import com.maanit.stableshare.ui.components.mood
import com.maanit.stableshare.ui.mascot.CloudPlane
import com.maanit.stableshare.ui.mascot.MascotIllustration
import com.maanit.stableshare.ui.settings.LocalServerControls
import com.maanit.stableshare.ui.settings.ServerOptionCard
import com.maanit.stableshare.ui.theme.Neutral
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** First-run server choice: checks the hosted server once, then saves the pick (which marks the server chosen). */
class ServerChoiceViewModel(
    checkHealth: (ServerProfile, String) -> Flow<ServerHealth>,
    private val switchServer: suspend (ServerChoice) -> Boolean,
) : ViewModel() {

    private val _hostedHealth = MutableStateFlow<ServerHealth?>(null)

    /** The hosted server's health; null until the check answers or reports Waking. */
    val hostedHealth: StateFlow<ServerHealth?> = _hostedHealth.asStateFlow()

    init {
        viewModelScope.launch {
            checkHealth(ServerProfile.HOSTED, ServerProfiles.HOSTED_URL).collect { _hostedHealth.value = it }
        }
    }

    /** Saves [choice]; false (nothing saved) if its address is not usable. */
    suspend fun confirm(choice: ServerChoice): Boolean = switchServer(choice)
}

/**
 * "Where should files go?" (UI-SPEC §12.2, §12.4): full height over Transfers until a server is
 * chosen. It can't be swiped, tapped or backed away; Continue saves and then slides it down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerChoiceSheet(vm: ServerChoiceViewModel, onDone: () -> Unit) {
    val health by vm.hostedHealth.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var closing by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || closing },
    )
    var local by rememberSaveable { mutableStateOf(false) }
    var localMode by rememberSaveable { mutableStateOf(ServerProfile.EMULATOR) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(ServerProfiles.DEFAULT_LAN_PORT.toString()) }
    var error by remember { mutableStateOf<Int?>(null) }

    /** Null (with the error shown) if a phone's port or address is not usable. */
    fun choice(): ServerChoice? = when {
        !local -> ServerChoice(ServerProfile.HOSTED)
        localMode == ServerProfile.EMULATOR -> ServerChoice(ServerProfile.EMULATOR)
        else -> {
            val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            val c = p?.let { ServerChoice(ServerProfile.LAN, lanHost = host.trim(), lanPort = it) }
            error = when {
                p == null -> R.string.settings_server_port_invalid
                ServerProfiles.urlOf(c!!) == null -> R.string.settings_server_invalid
                else -> null
            }
            c.takeIf { error == null }
        }
    }

    fun submit() {
        focus.clearFocus()
        val c = choice() ?: return
        scope.launch {
            if (!vm.confirm(c)) {
                error = R.string.settings_server_invalid
                return@launch
            }
            closing = true
            sheetState.hide()
            onDone()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (closing) onDone() },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = Neutral.colors.card,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        Column(Modifier.fillMaxHeight().navigationBarsPadding().imePadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(32.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MascotIllustration(health.mood(), 140.dp, plane = CloudPlane.Perched, rain = false)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.server_sheet_title),
                    style = Neutral.type.heading,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ServerOptionCard(
                        title = stringResource(R.string.settings_server_hosted),
                        subtitle = stringResource(R.string.settings_server_hosted_subtitle),
                        selected = !local,
                        badge = health.dot(),
                        onSelect = { local = false; error = null },
                        tag = "sheetHosted",
                    )
                    ServerOptionCard(
                        title = stringResource(R.string.settings_server_local),
                        subtitle = stringResource(R.string.server_sheet_local_subtitle),
                        selected = local,
                        badge = null,
                        onSelect = { local = true },
                        tag = "sheetLocal",
                    ) {
                        LocalServerControls(
                            mode = localMode,
                            onModeChange = { localMode = it; error = null },
                            host = host,
                            onHostChange = { host = it; error = null },
                            port = port,
                            onPortChange = { port = it; error = null },
                            error = error?.let { stringResource(it) },
                            onDone = { focus.clearFocus() },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                PrimaryButton(
                    stringResource(R.string.server_sheet_continue),
                    onClick = ::submit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.server_sheet_footer),
                    style = Neutral.type.meta,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
