package com.maanit.stableshare.ui.mascot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maanit.stableshare.R
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.sin

/** Where the plane sits relative to the cloud when the mascot itself draws it. */
sealed interface CloudPlane {
    data object None : CloudPlane

    /** Centred on the top bump, 12 dp above the cloud's top edge, rotationZ 0 (§5.8.3). */
    data object Perched : CloudPlane

    /** Perched point, bobbing ±4 dp on a 1.2 s loop (Preparing). */
    data object Hover : CloudPlane

    /** Loops clockwise around the cloud once every [periodMs] (onboarding page 1). */
    data class Orbit(val periodMs: Int) : CloudPlane

    /** The splash entry path; [progress] runs 0 → 1 (see [entryPose]). */
    class Entry(val progress: State<Float>) : CloudPlane
}

val PLANE_WIDTH = 40.dp
val PLANE_HEIGHT = 30.dp

/** Gap between the cloud's top edge and the centre of a perched plane. */
val PERCH_LIFT = 12.dp

/**
 * Nimbus (UI-SPEC §2.1, §4). Layers are stacked bottom to top: left arm, one right arm, body,
 * one face, optional rain. Pass the width through [modifier]; the height follows the 240 × 200
 * viewport. The mascot is one accessibility node ("StableShare cloud, …") unless [decorative].
 */
@Composable
fun Mascot(
    mood: MascotMood,
    modifier: Modifier = Modifier,
    plane: CloudPlane = CloudPlane.None,
    waveLoop: Boolean = mood.motion == MascotMotion.WAVE_LOOP,
    decorative: Boolean = false,
    /** False keeps a rainy mood dry (UI-SPEC §12.4: an unreachable server is sad without rain). */
    rain: Boolean = mood.rain,
) {
    val reduced = LocalReducedMotion.current
    val description = stringResource(R.string.mascot_description, stringResource(mood.description))
    val semantics = if (decorative) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.clearAndSetSemantics { contentDescription = description }
    }
    BoxWithConstraints(modifier.aspectRatio(CloudGeometry.ASPECT).then(semantics)) {
        val width = constraints.maxWidth.toFloat()
        val height = width / CloudGeometry.ASPECT
        val animate = !reduced
        val loop = if (animate) rememberInfiniteTransition(label = "mascot") else null

        val breathePeriod = when (mood.motion) {
            MascotMotion.BREATHE -> 3_000
            MascotMotion.BREATHE_SLOW -> 5_000
            else -> 0
        }
        val breathe = if (loop != null && breathePeriod > 0) loop.cycle(breathePeriod, "breathe") else null
        val faceSlide = if (loop != null && mood.motion == MascotMotion.FACE_SLIDE) loop.cycle(2_000, "face") else null
        val waveCycle = if (loop != null && mood.armRaised && waveLoop) loop.cycle(1_200, "wave") else null
        val arrivalWave = remember { Animatable(0f) }
        LaunchedEffect(mood, animate) {
            if (animate && mood.motion == MascotMotion.WAVE_ARRIVAL && !waveLoop) {
                arrivalWave.snapTo(0f)
                arrivalWave.animateTo(0f, keyframes {
                    durationMillis = 3 * 1_200
                    for (i in 0 until 3) {
                        12f at i * 1_200 + 300
                        -12f at i * 1_200 + 900
                    }
                })
            } else {
                arrivalWave.snapTo(0f)
            }
        }
        val density = LocalDensity.current
        val fourDp = with(density) { 4.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (breathe != null) {
                        // 1 → 1.02 → 1 over one period.
                        val s = 1f + 0.01f * (1f - kotlin.math.cos(2 * PI * breathe.value).toFloat())
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(CloudGeometry.BREATHE_PIVOT_X, CloudGeometry.BREATHE_PIVOT_Y)
                    }
                },
        ) {
            Layer(R.drawable.mascot_arm_left)
            Crossfade(mood.armRaised, animationSpec = Motion.quick<Float>(), label = "arm") { raised ->
                if (raised) {
                    Layer(
                        R.drawable.mascot_arm_right_wave,
                        Modifier.graphicsLayer {
                            transformOrigin = TransformOrigin(CloudGeometry.RIGHT_ARM_PIVOT_X, CloudGeometry.RIGHT_ARM_PIVOT_Y)
                            rotationZ = when {
                                waveCycle != null -> 12f * sin(2 * PI * waveCycle.value).toFloat()
                                else -> arrivalWave.value
                            }
                        },
                    )
                } else {
                    Layer(R.drawable.mascot_arm_right_rest)
                }
            }
            Layer(R.drawable.mascot_cloud_body)
            Crossfade(mood.face, animationSpec = Motion.quick<Float>(), label = "face") { face ->
                Layer(
                    face.drawable,
                    Modifier.graphicsLayer {
                        if (faceSlide != null && face == MascotFace.SEARCHING) {
                            translationX = fourDp * sin(2 * PI * faceSlide.value).toFloat()
                        }
                    },
                )
            }
            AnimatedVisibility(rain, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.quick())) {
                Rain(loop?.takeIf { mood.motion == MascotMotion.RAIN }?.cycle(1_400, "rain"))
            }
        }

        if (plane != CloudPlane.None) {
            val perched = perchedPose(width, height, density.run { PERCH_LIFT.toPx() })
            val bob = if (loop != null && plane == CloudPlane.Hover) loop.cycle(1_200, "hover") else null
            val orbit = (plane as? CloudPlane.Orbit)?.let { o -> if (loop != null) loop.cycle(o.periodMs, "orbit") else null }
            PlaneSprite(Modifier.align(Alignment.TopStart)) {
                when (plane) {
                    CloudPlane.Perched -> perched
                    CloudPlane.Hover -> perched.copy(y = perched.y + fourDp * sin(2 * PI * (bob?.value ?: 0f)).toFloat())
                    is CloudPlane.Orbit -> orbit?.let { orbitPose(360f * it.value, width, height) } ?: perched
                    is CloudPlane.Entry -> entryPose(plane.progress.value, width, height, perched)
                    CloudPlane.None -> perched
                }
            }
        }
    }
}

/** A layer that fills the shared 240 × 200 box. */
@Composable
private fun Layer(drawable: Int, modifier: Modifier = Modifier) {
    Image(painterResource(drawable), contentDescription = null, modifier = modifier.fillMaxSize())
}

/** Three drops, each clipped to its own strip so they can fall and fade out of step. */
@Composable
private fun Rain(cycle: State<Float>?) {
    val painter = painterResource(R.drawable.mascot_rain)
    val fall = with(LocalDensity.current) { 6.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        CloudGeometry.RAIN_STRIPS.forEachIndexed { i, (left, right) ->
            // Staggered 200 ms within the 1.4 s loop.
            val phase = cycle?.let { ((it.value - i * 200f / 1_400f) % 1f + 1f) % 1f }
            val dy = phase?.let { fall * it } ?: 0f
            val alpha = phase?.let { 1f - it } ?: 1f
            clipRect(left = size.width * left, right = size.width * right) {
                translate(top = dy) { with(painter) { draw(size, alpha = alpha) } }
            }
        }
    }
}

/** The plane drawable at 40 × 30 dp, placed by its centre; [pose] is read at draw time. */
@Composable
fun PlaneSprite(modifier: Modifier = Modifier, pose: () -> PlanePose) {
    Image(
        painterResource(R.drawable.mascot_plane),
        contentDescription = null,
        modifier = modifier
            .size(PLANE_WIDTH, PLANE_HEIGHT)
            .graphicsLayer {
                val p = pose()
                translationX = p.x - PLANE_WIDTH.toPx() / 2
                translationY = p.y - PLANE_HEIGHT.toPx() / 2
                rotationZ = p.rotation
                alpha = p.alpha
            },
    )
}

/** Perched pose in a mascot box of [width] × [height] px. */
fun perchedPose(width: Float, height: Float, liftPx: Float) =
    PlanePose(width * CloudGeometry.TOP_X, height * CloudGeometry.TOP_Y - liftPx, rotation = 0f)

fun orbitPose(phiDeg: Float, width: Float, height: Float) = PlaneMath.onEllipse(
    phiDeg,
    width * CloudGeometry.ORBIT_CX,
    height * CloudGeometry.ORBIT_CY,
    width * CloudGeometry.ORBIT_RX,
    height * CloudGeometry.ORBIT_RY,
)

/**
 * Splash entry (§5.1): in from off-screen left along a curve, one clockwise loop around the
 * cloud ending at 12 o'clock, then a short glide down onto the perch.
 */
fun entryPose(p: Float, width: Float, height: Float, perched: PlanePose): PlanePose {
    val joinPhi = 270f
    val landPhi = 720f
    val join = orbitPose(joinPhi, width, height)
    return when {
        p < ENTRY_CURVE_END -> {
            val t = p / ENTRY_CURVE_END
            val sx = -width * 1.1f
            val sy = height * CloudGeometry.ORBIT_CY + height * 0.35f
            val cx = join.x - width * 0.2f
            val cy = height * 1.05f
            val x = quad(sx, cx, join.x, t)
            val y = quad(sy, cy, join.y, t)
            val dx = 2 * (1 - t) * (cx - sx) + 2 * t * (join.x - cx)
            val dy = 2 * (1 - t) * (cy - sy) + 2 * t * (join.y - cy)
            val heading = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
            PlanePose(x, y, PlaneMath.rotationForHeading(heading))
        }
        p < ENTRY_ORBIT_END -> {
            val t = (p - ENTRY_CURVE_END) / (ENTRY_ORBIT_END - ENTRY_CURVE_END)
            orbitPose(joinPhi + (landPhi - joinPhi) * FastOutSlowInEasing.transform(t), width, height)
        }
        else -> {
            val t = ((p - ENTRY_ORBIT_END) / (1f - ENTRY_ORBIT_END)).coerceIn(0f, 1f)
            orbitPose(landPhi, width, height).lerp(perched, FastOutSlowInEasing.transform(t))
        }
    }
}

private const val ENTRY_CURVE_END = 0.25f
private const val ENTRY_ORBIT_END = 0.85f

private fun quad(a: Float, b: Float, c: Float, t: Float) = (1 - t) * (1 - t) * a + 2 * (1 - t) * t * b + t * t * c

/** 0 → 1, linear, restarting every [periodMs]; callers turn it into sines or angles. */
@Composable
internal fun InfiniteTransition.cycle(periodMs: Int, label: String): State<Float> =
    animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = label)

/** A mascot sized as a fixed width (Neutral illustrations). */
@Composable
fun MascotIllustration(
    mood: MascotMood,
    width: Dp,
    plane: CloudPlane = CloudPlane.None,
    modifier: Modifier = Modifier,
    rain: Boolean = mood.rain,
) {
    Mascot(mood, modifier.size(width, width / CloudGeometry.ASPECT), plane = plane, decorative = true, rain = rain)
}
