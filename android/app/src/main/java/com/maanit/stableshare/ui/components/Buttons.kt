package com.maanit.stableshare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maanit.stableshare.ui.theme.Inter
import com.maanit.stableshare.ui.theme.Mint
import com.maanit.stableshare.ui.theme.Neutral

private val PrimaryShape = RoundedCornerShape(14.dp)

/**
 * Neutral primary button (UI-SPEC §5.5): 52 dp, near-black fill, white label, optional orange
 * leading icon. Disabled: pill fill, tertiary label, icon hidden.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
) {
    val c = Neutral.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PrimaryShape,
        modifier = modifier.height(52.dp),
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.inkPrimary,
            contentColor = c.white,
            disabledContainerColor = c.pill,
            disabledContentColor = c.inkTertiary,
        ),
    ) {
        if (icon != null && enabled) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Neutral.type.button)
    }
}

/** Neutral outlined button: transparent fill, 1.5 dp near-black border and label. */
@Composable
fun OutlinedNeutralButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
) {
    val c = Neutral.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = PrimaryShape,
        modifier = modifier.height(52.dp),
        contentPadding = contentPadding,
        border = BorderStroke(1.5.dp, if (enabled) c.inkPrimary else c.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = c.inkPrimary,
            disabledContentColor = c.inkTertiary,
        ),
    ) {
        Text(text, style = Neutral.type.button)
    }
}

/** Neutral text button: 14 sp SemiBold, colour per context, 48 dp minimum height. */
@Composable
fun NeutralTextButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    underline: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = Neutral.colors.inkTertiary),
    ) {
        Text(
            text,
            fontFamily = Inter,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        )
    }
}

enum class MintButtonKind { SECONDARY, DANGER, SUCCESS }

/**
 * Mint button (UI-SPEC §5.8.5): 104 × 36 dp visual (other sizes for onboarding), 4 dp radius,
 * 1 dp stroke border, 48 dp touch target.
 */
@Composable
fun MintButton(
    text: String,
    kind: MintButtonKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 104.dp,
    height: Dp = 36.dp,
) {
    val c = Mint.colors
    val (fill, label) = when (kind) {
        MintButtonKind.SECONDARY -> c.surface to c.inkPrimary
        MintButtonKind.DANGER -> c.danger to c.onDanger
        MintButtonKind.SUCCESS -> c.successSurface to c.inkPrimary
    }
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, c.stroke),
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = label),
        contentPadding = PaddingValues(horizontal = 8.dp),
        // Material buttons already grow their touch target to 48 dp around the 36 dp visual.
        modifier = modifier
            .defaultMinSize(minWidth = width)
            .height(height),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = Mint.type.label, color = label, textAlign = TextAlign.Center)
        }
    }
}
