package com.maanit.stableshare.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maanit.stableshare.ui.model.BarFill
import com.maanit.stableshare.ui.theme.Inter
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import com.maanit.stableshare.ui.theme.NeutralColors

val CardShape = RoundedCornerShape(16.dp)

/** Neutral card surface: white, 16 dp radius, row shadow (y 2 dp, blur 12 dp, 6 % black). */
fun Modifier.neutralCard(shadow: Boolean = true): Modifier {
    val withShadow = if (shadow) {
        dropShadow(CardShape, Shadow(radius = 12.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.06f)))
    } else {
        this
    }
    return withShadow.background(Color.White, CardShape).clip(CardShape)
}

/** "Limit 2" / "In queue 3" pill: 28 dp, pill fill, 12 dp padding, small label then a bold number. */
@Composable
fun CountPill(label: String, value: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null) {
    val c = Neutral.colors
    Row(
        modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(c.pill)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp, color = c.inkSecondary)
        Spacer(Modifier.width(4.dp))
        Text(value.toString(), fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = c.inkPrimary)
    }
}

/** Full-width banner card (UI-SPEC §5.4): leading 20 dp icon, body text, optional trailing action. */
@Composable
fun Banner(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Neutral.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.card, CardShape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Neutral.type.body, modifier = Modifier.weight(1f))
        if (action != null) action()
    }
}

/** Section header: label in the rowTitle style, secondary ink, count right-aligned in meta. */
@Composable
fun SectionHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Neutral.type.rowTitle, color = Neutral.colors.inkSecondary, modifier = Modifier.weight(1f))
        Text(count.toString(), style = Neutral.type.meta)
    }
}

/** Test-file chip: 32 dp, fully rounded, pill fill, 13 sp Medium label. */
@Composable
fun ActionChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Neutral.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.height(32.dp).background(c.pill, CircleShape).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = if (enabled) c.inkPrimary else c.inkTertiary)
        }
    }
}

/** Filter / preset chip: selected = near-black fill with white label, otherwise pill fill. */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Neutral.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .background(if (selected) c.inkPrimary else c.pill, CircleShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                fontFamily = Inter,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) c.white else c.inkPrimary,
            )
        }
    }
}

/** Segmented control (UI-SPEC §5.10): 44 dp, 12 dp radius, selected segment near-black with white text. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Neutral.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(shape)
                .background(c.pill),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(if (isSelected) c.inkPrimary else Color.Transparent)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = Neutral.type.button.copy(fontSize = 15.sp),
                        color = if (isSelected) c.white else c.inkPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

fun NeutralColors.barColor(fill: BarFill): Color = when (fill) {
    BarFill.MUTED -> muted
    BarFill.ACCENT, BarFill.ACCENT_PULSE -> accent
    BarFill.WARNING -> warningFill
    BarFill.PAUSED -> paused
    BarFill.DANGER -> danger
    BarFill.SUCCESS -> success
}

/** 4 dp, fully rounded progress bar; the fill width animates over `standard`. */
@Composable
fun ProgressBar(fraction: Float, fill: BarFill, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val c = Neutral.colors
    val reduced = LocalReducedMotion.current
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.standard(), label = "bar")
    val pulse = if (fill == BarFill.ACCENT_PULSE && !reduced) {
        rememberInfiniteTransition(label = "pulse")
            .animateFloat(0.6f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse").value
    } else {
        1f
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(c.track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .graphicsLayer { alpha = pulse }
                .background(c.barColor(fill), CircleShape),
        )
    }
}

/** Snackbars (UI-SPEC §5.11): near-black, white body text, orange 14 sp SemiBold action. */
@Composable
fun NeutralSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data: SnackbarData ->
        val c = Neutral.colors
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(c.inkPrimary, RoundedCornerShape(4.dp))
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(data.visuals.message, style = Neutral.type.body, color = c.white, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
            data.visuals.actionLabel?.let { NeutralTextButton(it, onClick = data::performAction, color = c.accent) }
        }
    }
}

/** Horizontal arrangement helper for rows of chips that wrap. */
val ChipSpacing = Arrangement.spacedBy(8.dp)
