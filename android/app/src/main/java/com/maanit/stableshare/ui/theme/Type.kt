package com.maanit.stableshare.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.maanit.stableshare.R

/** Bundled variable fonts (UI-SPEC §2.2); each weight is an instance of the variable file. */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    resId = res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Comfortaa = FontFamily(
    variable(R.font.comfortaa, 300),
    variable(R.font.comfortaa, 400),
    variable(R.font.comfortaa, 700),
)

val Inter = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    family: FontFamily,
    size: TextUnit,
    line: TextUnit,
    weight: FontWeight,
    color: Color = Color.Unspecified,
    align: TextAlign = TextAlign.Unspecified,
) = TextStyle(
    fontFamily = family,
    fontSize = size,
    lineHeight = line,
    fontWeight = weight,
    color = color,
    textAlign = align,
    lineHeightStyle = trim,
)

fun mintType(c: MintColors) = MintType(
    display = style(Comfortaa, 30.sp, 38.sp, FontWeight.Normal, c.inkPrimary, TextAlign.Center),
    percent = style(Comfortaa, 38.sp, 44.sp, FontWeight.Light, c.inkPrimary, TextAlign.Center),
    title = style(Comfortaa, 18.sp, 24.sp, FontWeight.Bold, c.inkPrimary, TextAlign.Center),
    body = style(Comfortaa, 14.sp, 20.sp, FontWeight.Normal, c.inkSecondary, TextAlign.Center),
    label = style(Comfortaa, 13.sp, 18.sp, FontWeight.Bold, align = TextAlign.Center),
    caption = style(Comfortaa, 12.sp, 16.sp, FontWeight.Normal, c.inkSecondary, TextAlign.Center),
)

fun neutralType(c: NeutralColors) = NeutralType(
    title = style(Inter, 28.sp, 34.sp, FontWeight.Bold, c.inkPrimary),
    subtitle = style(Inter, 15.sp, 22.sp, FontWeight.Normal, c.inkSecondary),
    heading = style(Inter, 18.sp, 24.sp, FontWeight.SemiBold, c.inkPrimary),
    rowTitle = style(Inter, 16.sp, 22.sp, FontWeight.SemiBold, c.inkStrong),
    body = style(Inter, 14.sp, 20.sp, FontWeight.Normal, c.inkSecondary),
    meta = style(Inter, 13.sp, 18.sp, FontWeight.Normal, c.inkTertiary),
    status = style(Inter, 13.sp, 18.sp, FontWeight.Medium),
    button = style(Inter, 16.sp, 22.sp, FontWeight.SemiBold),
    small = style(Inter, 12.sp, 16.sp, FontWeight.Medium, c.inkSecondary),
    hash = style(FontFamily.Monospace, 12.sp, 16.sp, FontWeight.Normal, c.inkTertiary),
)
