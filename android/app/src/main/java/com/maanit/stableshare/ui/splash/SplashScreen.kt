package com.maanit.stableshare.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.maanit.stableshare.R
import com.maanit.stableshare.ui.mascot.CloudGeometry
import com.maanit.stableshare.ui.mascot.CloudPlane
import com.maanit.stableshare.ui.mascot.Mascot
import com.maanit.stableshare.ui.mascot.MascotMood
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Mint
import com.maanit.stableshare.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Total length of the animated splash, and of the static one under reduced motion (UI-SPEC §5.1, §10). */
const val SPLASH_MS = 1_600
const val SPLASH_REDUCED_MS = 600

/**
 * Animated splash (UI-SPEC §5.1), shown once per cold start from the launcher. A tap anywhere
 * skips to the end. Under reduced motion everything is shown at rest for 600 ms.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinished)
    val clock = remember { Animatable(if (reduced) SPLASH_MS.toFloat() else 0f) }
    val cloudScale = remember { Animatable(if (reduced) 1f else 0.8f) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun complete() {
        if (done) return
        done = true
        finish()
    }

    LaunchedEffect(reduced) {
        if (reduced) {
            delay(SPLASH_REDUCED_MS.toLong())
        } else {
            launch { cloudScale.animateTo(1f, Motion.emphasis()) }
            clock.animateTo(SPLASH_MS.toFloat(), tween(SPLASH_MS, easing = LinearEasing))
        }
        complete()
    }

    val t by remember { derivedStateOf { clock.value } }
    val planeProgress = remember { derivedStateOf { ((clock.value - 300f) / 700f).coerceIn(0f, 1f) } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Mint.colors.bg)
            .testTag("splash")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch {
                    clock.snapTo(SPLASH_MS.toFloat())
                    cloudScale.snapTo(1f)
                    complete()
                }
            },
    ) {
        val cloudWidth = min(maxWidth * 0.48f, 200.dp)
        val cloudHeight = cloudWidth / CloudGeometry.ASPECT
        val rise = with(LocalDensity.current) { 8.dp.toPx() }
        // Cloud centred at 42 % of the screen height; text follows below it.
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = maxHeight * 0.42f - cloudHeight / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Mascot(
                MascotMood.IDLE,
                Modifier
                    .width(cloudWidth)
                    .graphicsLayer {
                        scaleX = cloudScale.value
                        scaleY = cloudScale.value
                        alpha = (t / 500f).coerceIn(0f, 1f)
                    },
                plane = if (reduced) CloudPlane.Perched else CloudPlane.Entry(planeProgress),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.splash_wordmark),
                style = Mint.type.display,
                modifier = Modifier.graphicsLayer {
                    val p = ((t - 600f) / 300f).coerceIn(0f, 1f)
                    alpha = p
                    translationY = (1f - p) * rise
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.splash_tagline),
                style = Mint.type.body,
                modifier = Modifier.graphicsLayer { alpha = ((t - 750f) / 300f).coerceIn(0f, 1f) },
            )
        }
    }
}
