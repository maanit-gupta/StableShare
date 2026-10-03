package com.maanit.stableshare.ui.mascot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.maanit.stableshare.ui.theme.Mint
import com.maanit.stableshare.ui.theme.StableShareTheme

/** Every mood with its plane position, laid out like docs/design/mascot/previews/mascot_sheet.png. */
@Composable
fun MascotSheet() {
    val cells = listOf(
        "idle" to (MascotMood.IDLE to CloudPlane.Perched),
        "queued" to (MascotMood.FOCUSED to CloudPlane.Perched),
        "transferring" to (MascotMood.FOCUSED to CloudPlane.Hover),
        "retrying" to (MascotMood.WORRIED to CloudPlane.Perched),
        "waiting_network" to (MascotMood.SEARCHING to CloudPlane.None),
        "paused" to (MascotMood.SLEEPY to CloudPlane.Perched),
        "failed" to (MascotMood.SAD to CloudPlane.None),
        "completed" to (MascotMood.HAPPY to CloudPlane.Perched),
        "cancelled" to (MascotMood.CALM to CloudPlane.None),
    )
    Column(
        Modifier.background(Mint.colors.bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (label, mood) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 24.dp)) {
                            Mascot(mood.first, Modifier.width(104.dp), plane = mood.second)
                        }
                        Text(label, style = Mint.type.caption)
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 380, heightDp = 560)
@Composable
private fun MascotSheetPreview() {
    StableShareTheme(reducedMotion = true) { MascotSheet() }
}

@Preview(widthDp = 260, heightDp = 240)
@Composable
private fun MascotIdlePreview() {
    StableShareTheme(reducedMotion = true) {
        Box(Modifier.background(Mint.colors.bg).padding(top = 32.dp)) {
            Mascot(MascotMood.IDLE, Modifier.width(220.dp), plane = CloudPlane.Perched)
        }
    }
}
