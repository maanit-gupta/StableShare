package com.maanit.stableshare.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.theme.Inter
import com.maanit.stableshare.ui.theme.Neutral
import java.util.Locale

/** The tag text (UI-SPEC §5.4.1): uppercase extension, at most 4 characters; "FILE" or "BIN". */
fun extensionTag(fileName: String, generated: Boolean): String {
    if (generated) return "BIN"
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.lastIndex) return "FILE"
    return fileName.substring(dot + 1).uppercase(Locale.ROOT).take(4)
}

/** Extension tag: 9 sp Bold white on a 3 dp-radius rectangle; red for PDF, grey otherwise. */
@Composable
fun ExtensionTag(tag: String, modifier: Modifier = Modifier) {
    val c = Neutral.colors
    Box(
        modifier
            .background(if (tag == "PDF") c.danger else c.inkSecondary, RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp),
    ) {
        Text(tag, color = c.white, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.sp, lineHeight = 11.sp)
    }
}

/** 28 × 34 dp document glyph with its extension tag and the direction badge (UI-SPEC §5.4.1). */
@Composable
fun FileIcon(fileName: String, type: TransferType, generated: Boolean, modifier: Modifier = Modifier) {
    val c = Neutral.colors
    // The 16 dp badge is centred on the glyph's bottom-right corner, so the box grows by 8 dp.
    Box(modifier.size(width = 36.dp, height = 42.dp)) {
        Box(Modifier.size(28.dp, 34.dp)) {
            Canvas(Modifier.size(28.dp, 34.dp)) {
                val stroke = 1.5.dp.toPx()
                val r = CornerRadius(3.dp.toPx())
                drawRoundRect(Color.White, cornerRadius = r)
                drawRoundRect(c.border, cornerRadius = r, style = Stroke(stroke), topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke))
                val lineH = 2.dp.toPx()
                val left = 6.dp.toPx()
                listOf(16.dp, 14.dp, 10.dp).forEachIndexed { i, w ->
                    drawRoundRect(
                        c.track,
                        topLeft = Offset(left, (6 + 5 * i).dp.toPx()),
                        size = Size(w.toPx(), lineH),
                        cornerRadius = CornerRadius(lineH / 2),
                    )
                }
            }
            ExtensionTag(extensionTag(fileName, generated), Modifier.align(Alignment.BottomCenter).offset(y = 2.dp))
        }
        Box(
            Modifier
                .offset(x = 20.dp, y = 26.dp)
                .size(16.dp)
                .background(c.white, CircleShape)
                .border(1.dp, c.track, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (type == TransferType.UPLOAD) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                contentDescription = null,
                tint = c.inkSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Upload launch-pad chip (UI-SPEC §5.6 B.12): 44 × 56 dp white card with lines and the tag. */
@Composable
fun FileChip(tag: String, modifier: Modifier = Modifier, shadowBlur: androidx.compose.ui.unit.Dp = 12.dp) {
    val c = Neutral.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(44.dp, 56.dp)
            .dropShadow(shape, Shadow(radius = shadowBlur, offset = DpOffset(0.dp, 4.dp), color = Color.Black.copy(alpha = 0.10f)))
            .background(c.white, shape),
    ) {
        Canvas(Modifier.size(44.dp, 56.dp)) {
            val h = 2.dp.toPx()
            listOf(24.dp, 28.dp, 18.dp).forEachIndexed { i, w ->
                drawRoundRect(
                    c.track,
                    topLeft = Offset(8.dp.toPx(), (10 + 6 * i).dp.toPx()),
                    size = Size(w.toPx(), h),
                    cornerRadius = CornerRadius(h / 2),
                )
            }
        }
        ExtensionTag(tag, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
    }
}
