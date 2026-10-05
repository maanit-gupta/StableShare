package com.maanit.stableshare.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maanit.stableshare.R
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.theme.Inter
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral

/** The status dot's states (UI-SPEC §12.1); an invalid address shows no dot. */
enum class ServerDot(val label: Int) {
    CHECKING(R.string.settings_checking),
    ONLINE(R.string.server_status_online),
    WAKING(R.string.server_status_waking),
    UNREACHABLE(R.string.server_status_unreachable),
}

/** Not checked yet (null) counts as checking; Invalid has no dot. */
fun ServerHealth?.dot(): ServerDot? = when (this) {
    null -> ServerDot.CHECKING
    is ServerHealth.Online -> ServerDot.ONLINE
    ServerHealth.Waking -> ServerDot.WAKING
    is ServerHealth.Unreachable -> ServerDot.UNREACHABLE
    ServerHealth.Invalid -> null
}

/** Nimbus for a server state (UI-SPEC §12.4); Unreachable is SAD without rain. */
fun ServerHealth?.mood(): MascotMood = when (this) {
    ServerHealth.Waking -> MascotMood.SEARCHING
    is ServerHealth.Online -> MascotMood.HAPPY
    is ServerHealth.Unreachable -> MascotMood.SAD
    null, ServerHealth.Invalid -> MascotMood.IDLE
}

@Composable
fun ServerDot.color(): Color = when (this) {
    ServerDot.CHECKING -> Neutral.colors.muted
    ServerDot.ONLINE -> Neutral.colors.success
    ServerDot.WAKING -> Neutral.colors.warning
    ServerDot.UNREACHABLE -> Neutral.colors.danger
}

/** The label colour next to a dot: tertiary ink while checking, otherwise the dot's colour. */
@Composable
fun ServerDot.labelColor(): Color = if (this == ServerDot.CHECKING) Neutral.colors.inkTertiary else color()

@Composable
fun StatusDot(dot: ServerDot) {
    val fill by animateColorAsState(dot.color(), Motion.quick(), label = "statusDot")
    Box(Modifier.size(8.dp).background(fill, CircleShape))
}

/**
 * The Transfers header's server pill: [CountPill]'s shape with the status dot (12.1) and the
 * server's name. The state is the pill's state description, so the dot is never the only signal.
 */
@Composable
fun ServerPill(label: String, dot: ServerDot?, onClick: () -> Unit, onClickLabel: String, modifier: Modifier = Modifier) {
    val c = Neutral.colors
    val state = dot?.let { stringResource(it.label) }
    Row(
        modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(c.pill)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .semantics { if (state != null) stateDescription = state }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            StatusDot(dot)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = c.inkPrimary)
    }
}
