package com.maanit.stableshare.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maanit.stableshare.R
import com.maanit.stableshare.ui.components.MintButton
import com.maanit.stableshare.ui.components.MintButtonKind
import com.maanit.stableshare.ui.mascot.CloudGeometry
import com.maanit.stableshare.ui.mascot.CloudPlane
import com.maanit.stableshare.ui.mascot.Mascot
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.mascot.PERCH_LIFT
import com.maanit.stableshare.ui.mascot.PLANE_WIDTH
import com.maanit.stableshare.ui.mascot.perchedPose
import com.maanit.stableshare.ui.theme.Mint
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import kotlinx.coroutines.launch

private data class Page(val title: Int, val helper: Int)

private val pages = listOf(
    Page(R.string.onboarding_title_1, R.string.onboarding_helper_1),
    Page(R.string.onboarding_title_2, R.string.onboarding_helper_2),
    Page(R.string.onboarding_title_3, R.string.onboarding_helper_3),
)

/**
 * First-run intro (UI-SPEC §5.2): three pages sharing one Mint layout. Skip and Get started both
 * call [onFinish], which asks for permissions, sets onboardingCompleted and opens Transfers.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val helperBottoms = remember { mutableStateMapOf<Int, Float>() }
    var pagerTop by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Mint.colors.bg)
            .safeDrawingPadding(),
    ) {
        val screenHeight = maxHeight
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterEnd) {
                if (pager.currentPage < pages.lastIndex) {
                    TextButton(onClick = onFinish, modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(R.string.onboarding_skip), style = Mint.type.body)
                    }
                }
            }
            Box(Modifier.weight(1f).onGloballyPositioned { pagerTop = it.positionInRoot().y }) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { index ->
                    PageContent(index, artHeight = screenHeight * 0.45f - 48.dp) { bottom -> helperBottoms[index] = bottom }
                }
                // Dots sit 32 dp below the current page's helper text (UI-SPEC §5.2).
                val target = (helperBottoms[pager.currentPage] ?: 0f) - pagerTop
                val y by animateFloatAsState(target, Motion.standard(), label = "dotsY")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = y + 32.dp.toPx() },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Dots(pager.currentPage)
                }
            }
            if (pager.currentPage < pages.lastIndex) {
                MintButton(
                    stringResource(R.string.onboarding_next),
                    MintButtonKind.SECONDARY,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    width = 140.dp,
                    height = 44.dp,
                )
            } else {
                MintButton(
                    stringResource(R.string.onboarding_get_started),
                    MintButtonKind.SUCCESS,
                    onClick = onFinish,
                    width = 160.dp,
                    height = 44.dp,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PageContent(index: Int, artHeight: Dp, onHelperBottom: (Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val artWidth = maxWidth * 0.5f
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(artHeight.coerceAtLeast(artWidth / CloudGeometry.ASPECT + 48.dp)), contentAlignment = Alignment.BottomCenter) {
                Art(index, artWidth)
            }
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(pages[index].title),
                style = Mint.type.display,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(pages[index].helper),
                style = Mint.type.body,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 8.dp)
                    .onGloballyPositioned { onHelperBottom(it.positionInRoot().y + it.size.height) },
            )
        }
    }
}

@Composable
private fun Art(index: Int, width: Dp) {
    val height = width / CloudGeometry.ASPECT
    Box(Modifier.width(width).height(height)) {
        when (index) {
            0 -> Mascot(MascotMood.IDLE, Modifier.width(width), plane = CloudPlane.Orbit(2_400))
            1 -> {
                Mascot(MascotMood.SLEEPY, Modifier.width(width), plane = CloudPlane.Perched)
                // A pause badge at the cloud's upper right.
                Box(
                    Modifier
                        .offset(x = width * 0.82f - 12.dp, y = height * 0.36f - 12.dp)
                        .size(24.dp)
                        .background(Mint.colors.surface, CircleShape)
                        .border(1.5.dp, Mint.colors.stroke, CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Pause, contentDescription = null, tint = Mint.colors.stroke, modifier = Modifier.size(14.dp))
                }
            }
            else -> {
                Mascot(MascotMood.HAPPY, Modifier.width(width), plane = CloudPlane.Perched, waveLoop = true)
                // A success check badge at the perched plane's tail.
                val density = LocalDensity.current
                val perch = with(density) { perchedPose(width.toPx(), height.toPx(), PERCH_LIFT.toPx()) }
                val tailX = with(density) { perch.x.toDp() } - PLANE_WIDTH * TAIL_FROM_CENTRE
                val tailY = with(density) { perch.y.toDp() }
                Box(
                    Modifier
                        .offset(x = tailX - 10.dp, y = tailY - 10.dp)
                        .size(20.dp)
                        .background(Neutral.colors.success, CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Neutral.colors.white, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** The plane drawable's tail sits at x 4 of 64, i.e. 0.44 of its width left of the centre. */
private const val TAIL_FROM_CENTRE = 0.44f

@Composable
private fun Dots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        pages.indices.forEach { i ->
            val active = i == current
            val w by animateDpAsState(if (active) 20.dp else 8.dp, Motion.standard(), label = "dot")
            Box(
                Modifier
                    .width(w)
                    .height(8.dp)
                    .then(
                        if (active) Modifier.background(Mint.colors.inkPrimary, CircleShape)
                        else Modifier.border(1.5.dp, Mint.colors.stroke, CircleShape),
                    ),
            )
        }
    }
}
