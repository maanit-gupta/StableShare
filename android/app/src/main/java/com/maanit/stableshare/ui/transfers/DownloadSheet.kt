package com.maanit.stableshare.ui.transfers

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.data.net.RemoteFile
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.components.FileIcon
import com.maanit.stableshare.ui.components.NeutralSnackbarHost
import com.maanit.stableshare.ui.components.OutlinedNeutralButton
import com.maanit.stableshare.ui.components.PrimaryButton
import com.maanit.stableshare.ui.mascot.MascotIllustration
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.theme.Inter
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Neutral

/** "Download from server" sheet (UI-SPEC §5.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(vm: DownloadSheetViewModel, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val files by vm.files.collectAsStateWithLifecycle()
    val added by vm.added.collectAsStateWithLifecycle()
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(vm) { vm.reset() }
    LaunchedEffect(vm) { vm.failureMessages.collect { snackbar.showSnackbar(it.resolve(resources)) } }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The sheet sizes itself to the skeleton first; re-expand once the real content is in.
    LaunchedEffect(files) { if (files != ServerFiles.Loading && sheetState.isVisible) sheetState.expand() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Neutral.colors.card,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Box(Modifier.heightIn(max = maxHeight)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(stringResource(R.string.download_sheet_title), style = Neutral.type.heading)
                    Text(stringResource(R.string.download_sheet_subtitle, DownloadSheetViewModel.hostOf(serverUrl)), style = Neutral.type.meta)
                }
                Spacer(Modifier.height(16.dp))
                when (val state = files) {
                    ServerFiles.Loading -> Skeleton()
                    ServerFiles.Unreachable -> ErrorState(serverUrl, onRetry = vm::load, onOpenSettings = onOpenSettings)
                    is ServerFiles.Loaded -> if (state.files.isEmpty()) {
                        Text(
                            stringResource(R.string.download_empty),
                            style = Neutral.type.body,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    } else {
                        LazyColumn {
                            items(state.files, key = { it.fileId }) { file ->
                                FileRow(
                                    file,
                                    status = when {
                                        file.fileId in added -> RowStatus.ADDED
                                        file.fileId in inProgress -> RowStatus.IN_PROGRESS
                                        else -> RowStatus.READY
                                    },
                                    onDownload = { vm.download(file) },
                                )
                            }
                        }
                    }
                }
            }
            NeutralSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

private enum class RowStatus { READY, ADDED, IN_PROGRESS }

@Composable
private fun FileRow(file: RemoteFile, status: RowStatus, onDownload: () -> Unit) {
    val c = Neutral.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(file.name, TransferType.DOWNLOAD, generated = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = Neutral.type.rowTitle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(Format.size(file.size), style = Neutral.type.meta)
        }
        TextButton(
            onClick = onDownload,
            enabled = status == RowStatus.READY,
            colors = ButtonDefaults.textButtonColors(contentColor = c.inkPrimary, disabledContentColor = c.inkTertiary),
        ) {
            if (status == RowStatus.ADDED) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = c.success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                stringResource(
                    when (status) {
                        RowStatus.READY -> R.string.download_action
                        RowStatus.ADDED -> R.string.download_added
                        RowStatus.IN_PROGRESS -> R.string.download_in_progress
                    },
                ),
                fontFamily = Inter,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Three 64 dp skeleton rows pulsing 0.5 ↔ 1 over 900 ms (static under reduced motion). */
@Composable
private fun Skeleton() {
    val reduced = LocalReducedMotion.current
    val alpha = if (reduced) {
        1f
    } else {
        rememberInfiniteTransition(label = "skeleton")
            .animateFloat(0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha").value
    }
    val pill = Neutral.colors.pill
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.graphicsLayer { this.alpha = alpha }) {
        repeat(3) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp, 34.dp).background(pill, shape))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(160.dp, 14.dp).background(pill, shape))
                    Box(Modifier.size(64.dp, 12.dp).background(pill, shape))
                }
            }
        }
    }
}

@Composable
private fun ErrorState(serverUrl: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MascotIllustration(MascotMood.SAD, 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.download_error_title), style = Neutral.type.heading, textAlign = TextAlign.Center)
        Text(serverUrl, style = Neutral.type.meta, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.download_try_again), onClick = onRetry, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedNeutralButton(stringResource(R.string.download_open_settings), onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
    }
}
