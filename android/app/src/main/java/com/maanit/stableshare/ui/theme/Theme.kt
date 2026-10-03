package com.maanit.stableshare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Typography as M3Typography

private val mintColors = MintColors()
private val neutralColors = NeutralColors()

private val DefaultMint = MintTokens(mintColors, mintType(mintColors))
private val DefaultNeutral = NeutralTokens(neutralColors, neutralType(neutralColors))

val LocalMintTokens = staticCompositionLocalOf { DefaultMint }
val LocalNeutralTokens = staticCompositionLocalOf { DefaultNeutral }

/**
 * The two locked token sets (UI-SPEC §1, §3): light only, no dynamic colour. Material 3
 * components that are used directly (sheets, switches, text fields) get a scheme built from the
 * Neutral tokens, because every dialog and sheet is Neutral.
 */
@Composable
fun StableShareTheme(
    reducedMotion: Boolean = rememberSystemReducedMotion(),
    content: @Composable () -> Unit,
) {
    val n = neutralColors
    val scheme = lightColorScheme(
        primary = n.inkPrimary,
        onPrimary = n.white,
        primaryContainer = n.accentTint,
        onPrimaryContainer = n.inkPrimary,
        secondary = n.inkSecondary,
        onSecondary = n.white,
        secondaryContainer = n.accentTint,
        onSecondaryContainer = n.inkPrimary,
        background = n.page,
        onBackground = n.inkPrimary,
        surface = n.card,
        onSurface = n.inkPrimary,
        surfaceVariant = n.pill,
        onSurfaceVariant = n.inkSecondary,
        surfaceContainerLowest = n.card,
        surfaceContainerLow = n.card,
        surfaceContainer = n.card,
        surfaceContainerHigh = n.card,
        surfaceContainerHighest = n.pill,
        outline = n.border,
        outlineVariant = n.track,
        error = n.danger,
        onError = n.white,
        inverseSurface = n.inkPrimary,
        inverseOnSurface = n.white,
        inversePrimary = n.accent,
    )
    val body = DefaultNeutral.type.body
    CompositionLocalProvider(
        LocalMintTokens provides DefaultMint,
        LocalNeutralTokens provides DefaultNeutral,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            // Material components colour their own text, so these styles carry no colour.
            typography = M3Typography(
                bodyLarge = DefaultNeutral.type.subtitle.uncoloured(),
                bodyMedium = body.uncoloured(),
                bodySmall = DefaultNeutral.type.meta.uncoloured(),
                titleLarge = DefaultNeutral.type.heading.uncoloured(),
                titleMedium = DefaultNeutral.type.rowTitle.uncoloured(),
                labelLarge = DefaultNeutral.type.button.uncoloured(),
                labelMedium = DefaultNeutral.type.small.uncoloured(),
                labelSmall = DefaultNeutral.type.small.uncoloured(),
                headlineSmall = DefaultNeutral.type.heading.uncoloured(),
            ),
            content = content,
        )
    }
}

private fun TextStyle.uncoloured() = copy(color = Color.Unspecified)

/** Short accessors: `Mint.colors.bg`, `Neutral.type.title`. */
object Mint {
    val colors: MintColors @Composable @ReadOnlyComposable get() = LocalMintTokens.current.colors
    val type: MintType @Composable @ReadOnlyComposable get() = LocalMintTokens.current.type
}

object Neutral {
    val colors: NeutralColors @Composable @ReadOnlyComposable get() = LocalNeutralTokens.current.colors
    val type: NeutralType @Composable @ReadOnlyComposable get() = LocalNeutralTokens.current.type
}
