package com.maanit.stableshare.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import com.maanit.stableshare.R
import com.maanit.stableshare.ui.mascot.CloudGeometry
import com.maanit.stableshare.ui.mascot.Mascot
import com.maanit.stableshare.ui.mascot.PERCH_LIFT
import com.maanit.stableshare.ui.mascot.PlaneMath
import com.maanit.stableshare.ui.mascot.PlanePose
import com.maanit.stableshare.ui.mascot.PlaneSprite
import com.maanit.stableshare.ui.mascot.perchedPose
import com.maanit.stableshare.ui.model.Condition
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.model.PlaneSpot
import com.maanit.stableshare.ui.model.RingStroke
import com.maanit.stableshare.ui.model.RingStyle
import com.maanit.stableshare.ui.model.RingTone
import com.maanit.stableshare.ui.model.StatePresentation
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Mint
import com.maanit.stableshare.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** Sizes of the hero's mascot / drops / ring column (UI-SPEC §5.8.1). */
private class StageGeometry(screenWidth: Dp) {
    val mascotWidth = min(screenWidth * 0.54f, 220.dp)
    val mascotHeight = mascotWidth / CloudGeometry.ASPECT
    val dropsTop = mascotHeight + 8.dp
    val ringTop = dropsTop + DROP + 16.dp
    val ring = (screenWidth * 0.38f).coerceIn(120.dp, 160.dp)
    val height = ringTop + ring

    companion object {
        val DROP = 6.dp
    }
}

private val RING_STROKE = 1.5.dp

/**
 * The cloud, the data drops and the progress ring, with Dart flying between them. Everything is
 * laid out at fixed offsets so the plane's positions can be computed rather than measured.
 */
@Composable
fun HeroStage(item: TransferItem, screenWidth: Dp, modifier: Modifier = Modifier) {
    val g = remember(screenWidth) { StageGeometry(screenWidth) }
    val condition = item.condition
    val reduced = LocalReducedMotion.current
    val frame = remember { mutableLongStateOf(0L) }
    val spot = StatePresentation.plane(condition)
    val style = StatePresentation.ring(condition)
    val moving = !reduced && (
        spot in setOf(PlaneSpot.HOVER, PlaneSpot.RING_WOBBLE, PlaneSpot.RING_LAP) ||
            condition == Condition.TRANSFERRING || style.turnMs != null
        )
    LaunchedEffect(moving) {
        if (moving) while (true) withInfiniteAnimationFrameMillis { frame.longValue = it }
    }

    val stageWidth = remember { mutableFloatStateOf(0f) }
    Box(modifier.fillMaxWidth().height(g.height).onSizeChanged { stageWidth.floatValue = it.width.toFloat() }) {
        Mascot(
            StatePresentation.mood(condition),
            Modifier
                .width(g.mascotWidth)
                .align(Alignment.TopCenter),
        )
        Drops(active = StatePresentation.dropsActive(condition) && !reduced, frame = { frame.longValue }, modifier = Modifier.align(Alignment.TopCenter).offset(y = g.dropsTop))
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = g.ringTop)
                .size(g.ring),
            contentAlignment = Alignment.Center,
        ) {
            Ring(style, frame = { frame.longValue }, modifier = Modifier.size(g.ring))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = if (condition == Condition.CANCELLED) 0.6f else 1f },
            ) {
                Text("${item.percent}%", style = Mint.type.percent)
                Text(stringResource(R.string.detail_of_total, Format.size(item.size)), style = Mint.type.caption)
            }
        }
        Plane(item, spot, g, stageWidth = { stageWidth.floatValue }, frame = { frame.longValue })
    }
}

/** Three 6 dp drops, 10 dp apart; while transferring they light up in turn (decorative). */
@Composable
private fun Drops(active: Boolean, frame: () -> Long, modifier: Modifier = Modifier) {
    val ink = Mint.colors.inkPrimary
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            Canvas(
                Modifier
                    .size(StageGeometry.DROP)
                    .graphicsLayer {
                        alpha = if (!active) 0.3f else {
                            // 300 ms pulse each (0.3 → 1 → 0.3), staggered 150 ms; the cycle repeats every 600 ms.
                            val t = ((frame() - i * 150L) % 600L + 600L) % 600L
                            if (t < 300) 0.3f + 0.7f * sin(PI * t / 300.0).toFloat() else 0.3f
                        }
                    },
            ) { drawCircle(ink) }
        }
    }
}

/** The progress ring (UI-SPEC §5.8.3): dashed, solid or dotted, with rotating dashes while busy. */
@Composable
private fun Ring(style: RingStyle, frame: () -> Long, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val c = Mint.colors
    val color = when (style.tone) {
        RingTone.STROKE -> c.stroke
        RingTone.SECONDARY_60 -> c.inkSecondary.copy(alpha = 0.6f)
        RingTone.DANGER -> c.danger
    }
    // The dash gap closes 4 → 0 dp over 400 ms when COMPLETED arrives on screen.
    val solid = style.stroke == RingStroke.SOLID
    val gap = remember { Animatable(if (solid) 0f else 4f) }
    LaunchedEffect(solid, reduced) {
        if (!solid) gap.snapTo(4f)
        else if (reduced) gap.snapTo(0f)
        else gap.animateTo(0f, tween(400, easing = LinearEasing))
    }
    val density = LocalDensity.current
    Canvas(modifier) {
        val strokePx = RING_STROKE.toPx()
        val radius = size.minDimension / 2 - strokePx / 2
        val turn = style.turnMs?.takeIf { !reduced }?.let { 360f * (frame() % it) / it } ?: 0f
        val on = with(density) { 4.dp.toPx() }
        val effect = when {
            style.stroke == RingStroke.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(with(density) { 1.dp.toPx() }, with(density) { 6.dp.toPx() }))
            gap.value < 0.01f -> null
            else -> PathEffect.dashPathEffect(floatArrayOf(on, with(density) { gap.value.dp.toPx() }))
        }
        rotate(turn) {
            drawCircle(
                color,
                radius = radius,
                style = Stroke(
                    width = strokePx,
                    cap = if (style.stroke == RingStroke.DOTTED) StrokeCap.Round else StrokeCap.Butt,
                    pathEffect = effect,
                ),
            )
        }
    }
}

/**
 * Dart (UI-SPEC §4.1, §5.8.3). The pose is computed at draw time from the spot, the animated
 * progress angle and the frame clock; moving between spots blends with `emphasis`.
 */
@Composable
private fun Plane(item: TransferItem, spot: PlaneSpot, g: StageGeometry, stageWidth: () -> Float, frame: () -> Long) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val targetTheta = 360f * item.percent / 100f
    val theta = remember { Animatable(targetTheta) }
    LaunchedEffect(targetTheta, reduced) {
        if (reduced) theta.snapTo(targetTheta) else theta.animateTo(targetTheta, spring(dampingRatio = 0.8f, stiffness = 120f))
    }

    val memory = remember { PoseMemory() }
    val blend = remember { Animatable(1f) }
    val lap = remember { Animatable(0f) }
    val alpha = remember { Animatable(if (spot == PlaneSpot.HIDDEN) 0f else 1f) }
    LaunchedEffect(spot, reduced) {
        val previous = memory.spot
        memory.spot = spot
        if (previous == null || previous == spot) return@LaunchedEffect
        memory.from = memory.last
        if (spot == PlaneSpot.HIDDEN) {
            if (reduced) alpha.snapTo(0f) else alpha.animateTo(0f, tween(200))
            return@LaunchedEffect
        }
        alpha.snapTo(1f)
        if (spot == PlaneSpot.RING_TOP && !reduced) {
            // One fast lap from where it is, ending at 12 o'clock.
            val start = theta.value
            memory.lapping = true
            lap.snapTo(start)
            blend.snapTo(1f)
            lap.animateTo(ceil((start + 360f) / 360f) * 360f, tween(Motion.LONG_MS, easing = LinearEasing))
            memory.lapping = false
            return@LaunchedEffect
        }
        if (reduced) {
            blend.snapTo(1f)
        } else {
            blend.snapTo(0f)
            blend.animateTo(1f, Motion.emphasis())
        }
    }

    PlaneSprite {
        memory.stageWidthPx = stageWidth()
        val target = poseFor(item.condition, spot, g, density, theta.value, frame(), reduced, memory, lap.value)
        val pose = if (blend.value < 1f) memory.from?.lerp(target, blend.value) ?: target else target
        memory.last = pose
        pose.copy(alpha = alpha.value)
    }
}

private class PoseMemory {
    var spot: PlaneSpot? = null
    var last: PlanePose? = null
    var from: PlanePose? = null
    var lapping = false
    var stageWidthPx = 0f
}

private fun poseFor(
    condition: Condition,
    spot: PlaneSpot,
    g: StageGeometry,
    density: androidx.compose.ui.unit.Density,
    theta: Float,
    now: Long,
    reduced: Boolean,
    memory: PoseMemory,
    lap: Float,
): PlanePose = with(density) {
    val stageWidth = memory.stageWidthPx
    val mascotLeft = stageWidth / 2 - g.mascotWidth.toPx() / 2
    val perch = perchedPose(g.mascotWidth.toPx(), g.mascotHeight.toPx(), PERCH_LIFT.toPx()).let { it.copy(x = it.x + mascotLeft) }
    val cx = stageWidth / 2
    val cy = (g.ringTop + g.ring / 2).toPx()
    val r = g.ring.toPx() / 2 - RING_STROKE.toPx() / 2
    fun wave(periodMs: Long) = sin(2 * PI * (now % periodMs) / periodMs).toFloat()
    when (spot) {
        PlaneSpot.PERCHED -> perch
        PlaneSpot.HOVER -> if (reduced) perch else perch.copy(y = perch.y + 4.dp.toPx() * wave(1_200))
        PlaneSpot.RING_PROGRESS -> {
            val base = PlaneMath.onRing(theta, cx, cy, r)
            if (reduced || condition != Condition.TRANSFERRING) {
                base
            } else {
                val (nx, ny) = PlaneMath.radial(theta)
                val bob = 2.dp.toPx() * wave(1_200)
                base.copy(x = base.x + nx * bob, y = base.y + ny * bob)
            }
        }
        PlaneSpot.RING_WOBBLE -> {
            val base = PlaneMath.onRing(theta, cx, cy, r)
            val phase = now % 3_000
            if (reduced || phase >= 1_000) base else base.copy(rotation = base.rotation + 8f * sin(2 * PI * phase / 500.0).toFloat())
        }
        PlaneSpot.RING_LAP -> if (reduced) PlaneMath.onRing(theta, cx, cy, r) else PlaneMath.onRing(theta + 360f * (now % 1_600) / 1_600f, cx, cy, r)
        PlaneSpot.RING_FAILED -> PlaneMath.failed(cx, cy, r)
        PlaneSpot.RING_TOP -> PlaneMath.onRing(if (memory.lapping) lap else 0f, cx, cy, r)
        PlaneSpot.HIDDEN -> memory.last ?: PlaneMath.onRing(theta, cx, cy, r)
    }
}
