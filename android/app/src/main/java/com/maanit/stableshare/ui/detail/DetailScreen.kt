package com.maanit.stableshare.ui.detail

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.FileIntents
import com.maanit.stableshare.ui.components.CancelTransferDialog
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.components.MintButton
import com.maanit.stableshare.ui.components.MintButtonKind
import com.maanit.stableshare.ui.components.StatusBarScrim
import com.maanit.stableshare.ui.components.progressStateDescription
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.model.StatePresentation
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.model.text
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Mint
import kotlinx.coroutines.delay

/** Transfer detail (UI-SPEC §5.8): the Mint hero, then the Pieces, Activity and Details cards. */
@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        DetailState.Loading -> Box(Modifier.fillMaxSize().background(Mint.colors.bg))
        DetailState.Gone -> {
            Box(Modifier.fillMaxSize().background(Mint.colors.bg))
            LaunchedEffect(Unit) { onBack() }
        }
        is DetailState.Ready -> Detail(s.ui, onBack, vm::perform)
    }
}

@Composable
private fun Detail(ui: DetailUi, onBack: () -> Unit, perform: (TransferAction) -> Unit) {
    val item = ui.item
    var confirmCancel by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Mint.colors.bg)) {
        val viewport = maxHeight
        val screenWidth = maxWidth
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewport),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TopBar(item, onBack, perform)
                Spacer(Modifier.height(8.dp))
                Title(item)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.detail_file_line, item.name, Format.size(item.size)),
                    style = Mint.type.body,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(20.dp))
                val label = StatePresentation.listLabel(item, MAX_TRIES).text()
                val progressText = progressStateDescription(label, item.percent)
                HeroStage(
                    item,
                    screenWidth,
                    Modifier.semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(item.percent / 100f, 0f..1f)
                        stateDescription = progressText
                    },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    StatePresentation.detailStats(item, ui.maxConcurrent, ui.retryAttempt, MAX_TRIES, ui.doneChunks).text(),
                    style = Mint.type.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(24.dp))
                Buttons(item, onBack, perform, onCancel = { confirmCancel = true })
                Spacer(Modifier.height(32.dp))
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PiecesCard(ui)
                ActivityCard(ui)
                DetailsCard(ui)
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        StatusBarScrim(Mint.colors.bg)
    }
    if (confirmCancel) {
        CancelTransferDialog(
            fileName = item.name,
            onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                perform(TransferAction.CANCEL)
            },
        )
    }
}

@Composable
private fun TopBar(item: TransferItem, onBack: () -> Unit, perform: (TransferAction) -> Unit) {
    val context = LocalContext.current
    val completedDownload = item.state == TransferState.COMPLETED && item.type == TransferType.DOWNLOAD
    val removable = TransferAction.REMOVE in StateMachine.allowedActions(item.state)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = Mint.colors.stroke, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.weight(1f))
        if (completedDownload || removable) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more_options), tint = Mint.colors.stroke)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                    if (completedDownload) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_open_file)) }, onClick = {
                            menu = false
                            FileIntents.open(context, item.row)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_share_file)) }, onClick = {
                            menu = false
                            FileIntents.share(context, item.row)
                        })
                    }
                    if (removable) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_remove_from_history)) }, onClick = {
                            menu = false
                            perform(TransferAction.REMOVE) // the row disappears and the screen goes back
                        })
                    }
                }
            }
        }
    }
}

/** Titles ending in "..." animate the dots; the full width is always reserved so nothing shifts. */
@Composable
private fun Title(item: TransferItem) {
    val full = StatePresentation.detailTitle(item.condition, item.type).text()
    val animated = full.endsWith("...")
    val reduced = LocalReducedMotion.current
    var dots by remember { mutableIntStateOf(3) }
    LaunchedEffect(animated, reduced) {
        if (!animated || reduced) {
            dots = 3
            return@LaunchedEffect
        }
        while (true) {
            for (n in 1..3) {
                dots = n
                delay(400)
            }
        }
    }
    val text = if (!animated) {
        buildAnnotatedString { append(full) }
    } else {
        buildAnnotatedString {
            append(full.dropLast(3))
            append(".".repeat(dots))
            withStyle(SpanStyle(color = Color.Transparent)) { append(".".repeat(3 - dots)) }
        }
    }
    Text(text, style = Mint.type.display, modifier = Modifier.padding(horizontal = 24.dp))
}

/** Primary action then Cancel, from the state machine; Done (and Open) for finished transfers (§5.8.5). */
@Composable
private fun Buttons(item: TransferItem, onBack: () -> Unit, perform: (TransferAction) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item.state) {
            TransferState.COMPLETED -> {
                if (item.type == TransferType.DOWNLOAD) {
                    MintButton(stringResource(R.string.action_open), MintButtonKind.SECONDARY, onClick = { FileIntents.open(context, item.row) })
                }
                MintButton(stringResource(R.string.action_done), MintButtonKind.SUCCESS, onClick = onBack)
            }
            TransferState.CANCELLED -> MintButton(stringResource(R.string.action_done), MintButtonKind.SUCCESS, onClick = onBack)
            else -> item.rowActions.forEach { action ->
                when (action) {
                    TransferAction.PAUSE -> MintButton(stringResource(R.string.action_pause), MintButtonKind.SECONDARY, onClick = { perform(action) })
                    TransferAction.RESUME -> MintButton(stringResource(R.string.action_resume), MintButtonKind.SECONDARY, onClick = { perform(action) })
                    TransferAction.RETRY -> MintButton(stringResource(R.string.action_retry), MintButtonKind.SECONDARY, onClick = { perform(action) })
                    TransferAction.CANCEL -> MintButton(stringResource(R.string.action_cancel), MintButtonKind.DANGER, onClick = onCancel)
                    TransferAction.REMOVE -> Unit
                }
            }
        }
    }
}

/** Shared copy toast (the only toast in the app, UI-SPEC §5.11). */
internal fun copiedToast(context: android.content.Context) {
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

/** Rotation of the Details chevron. */
@Composable
internal fun chevronRotation(expanded: Boolean): Float =
    animateFloatAsState(if (expanded) 180f else 0f, com.maanit.stableshare.ui.theme.Motion.standard(), label = "chevron").value
