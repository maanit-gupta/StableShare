package com.maanit.stableshare.ui.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.engine.DemoKind
import com.maanit.stableshare.engine.DemoPhase
import com.maanit.stableshare.engine.DemoProgress
import com.maanit.stableshare.ui.components.NeutralSnackbarHost
import com.maanit.stableshare.ui.components.ProgressBar
import com.maanit.stableshare.ui.components.progressStateDescription
import com.maanit.stableshare.ui.model.BarFill
import com.maanit.stableshare.ui.theme.Neutral
import com.maanit.stableshare.ui.transfers.ChooserOption

/** "Try a demo" (UI-SPEC §12.5): three options; the running one shows its progress in place of its subtitle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoSheet(vm: DemoSheetViewModel, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(vm) { vm.started.collect { onStarted() } }
    LaunchedEffect(vm) { vm.failureMessages.collect { snackbar.showSnackbar(it.resolve(resources)) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Neutral.colors.card,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Box {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(stringResource(R.string.demo_sheet_title), style = Neutral.type.heading, modifier = Modifier.padding(24.dp))
                DemoOption(DemoKind.QUICK, Icons.Outlined.Upload, R.string.demo_quick_title, R.string.demo_quick_subtitle, progress, vm::start)
                DemoOption(DemoKind.BIG, Icons.Outlined.CloudUpload, R.string.demo_big_title, R.string.demo_big_subtitle, progress, vm::start)
                DemoOption(DemoKind.DOWNLOAD, Icons.Outlined.Download, R.string.demo_download_title, R.string.demo_download_subtitle, progress, vm::start)
            }
            NeutralSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

@Composable
private fun DemoOption(
    kind: DemoKind,
    icon: ImageVector,
    title: Int,
    subtitle: Int,
    progress: DemoProgress?,
    onStart: (DemoKind) -> Unit,
) {
    val phase = progress?.takeIf { it.kind == kind }?.phase
    val subtitleText = when (phase) {
        is DemoPhase.Making -> stringResource(R.string.demo_making)
        DemoPhase.Waking -> stringResource(R.string.empty_waking)
        DemoPhase.Starting, null -> stringResource(subtitle)
    }
    ChooserOption(
        icon = icon,
        title = stringResource(title),
        subtitle = subtitleText,
        onClick = { onStart(kind) },
        enabled = progress == null,
        footer = (phase as? DemoPhase.Making)?.let { making ->
            {
                val description = progressStateDescription(subtitleText, making.percent)
                Spacer(Modifier.height(8.dp))
                ProgressBar(
                    making.percent / 100f,
                    BarFill.ACCENT,
                    Modifier.semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(making.percent / 100f, 0f..1f)
                        stateDescription = description
                    },
                )
            }
        },
    )
}
