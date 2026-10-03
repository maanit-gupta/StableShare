package com.maanit.stableshare.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maanit.stableshare.R
import com.maanit.stableshare.ui.theme.Neutral

private data class Tab(val route: String, val target: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TRANSFERS, Routes.TRANSFERS, R.string.nav_transfers, Icons.Outlined.SwapVert),
    Tab(Routes.HISTORY, Routes.HISTORY, R.string.nav_history, Icons.Outlined.History),
    Tab(Routes.SETTINGS, Routes.settings(), R.string.nav_settings, Icons.Outlined.Settings),
)

/**
 * Bottom navigation (UI-SPEC §5.3): 64 dp plus the system inset, white, 1 dp track-coloured top
 * border. The white extends under the gesture/navigation inset.
 */
@Composable
fun AppBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val c = Neutral.colors
    Box(
        Modifier
            .background(c.card)
            .drawBehind { drawLine(c.track, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding(),
    ) {
        NavigationBar(
            containerColor = c.card,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0),
            modifier = Modifier.height(64.dp),
        ) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab.target) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label), style = Neutral.type.small) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.inkPrimary,
                        indicatorColor = c.accentTint,
                        unselectedIconColor = c.inkTertiary,
                        unselectedTextColor = c.inkTertiary,
                    ),
                )
            }
        }
    }
}
