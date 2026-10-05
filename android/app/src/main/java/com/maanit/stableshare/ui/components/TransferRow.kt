package com.maanit.stableshare.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maanit.stableshare.R
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.ui.model.Condition
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.model.LabelTone
import com.maanit.stableshare.ui.model.StatePresentation
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.model.text
import com.maanit.stableshare.ui.theme.Inter
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import com.maanit.stableshare.ui.theme.NeutralColors

fun NeutralColors.labelColor(tone: LabelTone): Color = when (tone) {
    LabelTone.TERTIARY -> inkTertiary
    LabelTone.SECONDARY -> inkSecondary
    LabelTone.WARNING -> warning
    LabelTone.DANGER -> danger
    LabelTone.SUCCESS -> success
}

/** Max chunk attempts, for the "Gave up after 5 tries" copy. */
const val MAX_TRIES = 5

/**
 * The shared transfer row (UI-SPEC §5.4.1). Buttons come from the state machine via
 * [TransferItem.rowActions]; the row itself never changes state, it reports [onAction].
 */
@Composable
fun TransferRow(
    item: TransferItem,
    generated: Boolean,
    onOpen: () -> Unit,
    onAction: (TransferAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Started by demo mode: shows the "Demo" pill. */
    demo: Boolean = false,
    /** Shown below the progress bar (the Big upload demo's tip). */
    tip: (@Composable () -> Unit)? = null,
    /** Active cards (UI-SPEC §12.6): a details block under the progress bar, shown while [expanded]. */
    details: (@Composable () -> Unit)? = null,
    expanded: Boolean = false,
) {
    val c = Neutral.colors
    val label = StatePresentation.listLabel(item, MAX_TRIES).text()
    val stateText = progressStateDescription(label, item.percent)
    val expansion = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column(
        modifier
            .fillMaxWidth()
            .neutralCard()
            .clickable(onClick = onOpen)
            // The card merges the bar's semantics; its own state description would hide the bar's, so say both.
            .then(if (details != null) Modifier.semantics { stateDescription = "$stateText, $expansion" } else Modifier)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            FileIcon(item.name, item.type, generated)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = Neutral.type.rowTitle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(sizeLine(item), style = Neutral.type.meta)
                val speed = Format.speed(item.liveSpeed)
                val eta = item.etaSeconds
                if (speed != null && eta != null) {
                    Text(stringResource(R.string.row_live, Format.unbreakable(speed), Format.unbreakable(Format.eta(eta))), style = Neutral.type.meta)
                }
                if (item.restored) {
                    Spacer(Modifier.height(4.dp))
                    RestoredPill()
                }
                if (item.instant) {
                    Spacer(Modifier.height(4.dp))
                    InstantPill()
                }
                if (demo) {
                    Spacer(Modifier.height(4.dp))
                    DemoPill()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.rowActions.forEach { action -> ActionIcon(action, item.name) { onAction(action) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = Neutral.type.status,
                color = c.labelColor(StatePresentation.labelTone(item.condition)),
                modifier = Modifier.weight(1f),
            )
            if (item.condition == Condition.COMPLETED) {
                CheckBadge(animate = true)
            } else {
                Text(
                    "${item.percent}%",
                    fontFamily = Inter,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.inkPrimary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(
            StatePresentation.barFraction(item),
            StatePresentation.barFill(item.condition),
            Modifier.semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(item.percent / 100f, 0f..1f)
                stateDescription = stateText
            },
        )
        if (details != null) {
            val reduced = LocalReducedMotion.current
            AnimatedVisibility(
                expanded,
                enter = if (reduced) EnterTransition.None else expandVertically(Motion.standard()),
                exit = if (reduced) ExitTransition.None else shrinkVertically(Motion.standard()),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
                    Spacer(Modifier.height(12.dp))
                    details()
                }
            }
        }
        if (tip != null) {
            Spacer(Modifier.height(12.dp))
            tip()
        }
    }
}

/** "Uploading, 42 percent" (UI-SPEC §10): the label without its trailing ellipsis. */
@Composable
fun progressStateDescription(label: String, percent: Int): String =
    LocalContext.current.getString(R.string.progress_state_description, label.trimEnd('…', '.'), percent)

@Composable
private fun sizeLine(item: TransferItem): String =
    if (item.state == TransferState.COMPLETED) {
        Format.size(item.size)
    } else {
        val (done, total) = Format.sizeProgress(item.bytes, item.size)
        stringResource(R.string.row_size_progress, done, total)
    }

@Composable
fun RestoredPill(modifier: Modifier = Modifier) = InfoPill(stringResource(R.string.row_restored), modifier)

/** "Already on server" (UI-SPEC §5.4.1): the transfer has an INSTANT_UPLOAD event. */
@Composable
fun InstantPill(modifier: Modifier = Modifier) = InfoPill(stringResource(R.string.row_instant), modifier)

/** "Demo" (UI-SPEC §12.5): the transfer was started from Try a demo. */
@Composable
fun DemoPill(modifier: Modifier = Modifier) = InfoPill(stringResource(R.string.demo_chip), modifier)

/** The Big upload demo's one-time tip (UI-SPEC §12.5), inside its card. */
@Composable
fun DemoTip(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Neutral.colors.pill, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 12.dp, end = 4.dp),
    ) {
        Text(stringResource(R.string.demo_tip), style = Neutral.type.body, modifier = Modifier.padding(end = 8.dp))
        NeutralTextButton(
            stringResource(R.string.demo_tip_dismiss),
            onClick = onDismiss,
            color = Neutral.colors.inkPrimary,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

/** The 20 dp `neutral.pill` badge shared by the Restored, Already on server and Demo pills. */
@Composable
private fun InfoPill(text: String, modifier: Modifier = Modifier) {
    val c = Neutral.colors
    Box(
        modifier
            .height(20.dp)
            .background(c.pill, CircleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontFamily = Inter,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = c.inkSecondary,
        )
    }
}

private fun iconFor(action: TransferAction): ImageVector = when (action) {
    TransferAction.PAUSE -> Icons.Outlined.Pause
    TransferAction.RESUME -> Icons.Outlined.PlayArrow
    TransferAction.RETRY -> Icons.Outlined.Refresh
    TransferAction.CANCEL -> Icons.Outlined.Close
    TransferAction.REMOVE -> error("REMOVE is not a row action")
}

@Composable
private fun ActionIcon(action: TransferAction, name: String, onClick: () -> Unit) {
    val description = stringResource(
        when (action) {
            TransferAction.PAUSE -> R.string.cd_pause
            TransferAction.RESUME -> R.string.cd_resume
            TransferAction.RETRY -> R.string.cd_retry
            TransferAction.CANCEL -> R.string.cd_cancel
            TransferAction.REMOVE -> error("REMOVE is not a row action")
        },
        name,
    )
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(iconFor(action), contentDescription = description, tint = Neutral.colors.inkSecondary, modifier = Modifier.size(24.dp))
    }
}

/** 24 dp success circle with a white check; pops in with `emphasis` (instant under reduced motion). */
@Composable
fun CheckBadge(animate: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, Motion.emphasis()) }
    Box(
        modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .background(Neutral.colors.success, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = Neutral.colors.white, modifier = Modifier.size(16.dp))
    }
}
