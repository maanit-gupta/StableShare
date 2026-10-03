package com.maanit.stableshare.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Style A (UI-SPEC §3.1, §3.3): Splash, Onboarding, Transfer detail. */
@Immutable
data class MintColors(
    val bg: Color = Color(0xFF7BBCA5),
    val surface: Color = Color(0xFFDDFBE2),
    val inkPrimary: Color = Color(0xFF102A24),
    val inkSecondary: Color = Color(0xFF24403A),
    val stroke: Color = Color(0xFF12201C),
    val danger: Color = Color(0xFFD92B52),
    val onDanger: Color = Color(0xFFFFFFFF),
    val successSurface: Color = Color(0xFFE8FAEC),
    val accentYellow: Color = Color(0xFFF7E65E),
    val accentGreen: Color = Color(0xFF6BBF5E),
)

@Immutable
data class MintType(
    val display: TextStyle,
    val percent: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

@Immutable
data class MintTokens(val colors: MintColors, val type: MintType)

/** Style B (UI-SPEC §3.2, §3.3): every list, sheet, dialog and settings screen. */
@Immutable
data class NeutralColors(
    val page: Color = Color(0xFFEFF1F5),
    val card: Color = Color(0xFFFFFFFF),
    val border: Color = Color(0xFFCBCED4),
    val inkPrimary: Color = Color(0xFF0F1115),
    val inkStrong: Color = Color(0xFF2A2D33),
    val inkSecondary: Color = Color(0xFF4B5563),
    val inkTertiary: Color = Color(0xFF6F7682),
    val pill: Color = Color(0xFFE3E7ED),
    val track: Color = Color(0xFFE5E7EB),
    val accent: Color = Color(0xFFFF6B2C),
    val accentTint: Color = Color(0xFFFFF3EC),
    val accentDash: Color = Color(0xFFEE6F3F),
    val net: Color = Color(0xFFA7AEB8),
    val success: Color = Color(0xFF21A06A),
    val warning: Color = Color(0xFFB45309),
    val warningFill: Color = Color(0xFFF59E0B),
    val danger: Color = Color(0xFFE5484D),
    val muted: Color = Color(0xFFC9CED6),
    val paused: Color = Color(0xFF9CA3AF),
    /** "White" where the spec says white (labels on dark fills, chips, the plane underside). */
    val white: Color = Color(0xFFFFFFFF),
)

@Immutable
data class NeutralType(
    val title: TextStyle,
    val subtitle: TextStyle,
    val heading: TextStyle,
    val rowTitle: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val status: TextStyle,
    val button: TextStyle,
    val small: TextStyle,
    val hash: TextStyle,
)

@Immutable
data class NeutralTokens(val colors: NeutralColors, val type: NeutralType)
