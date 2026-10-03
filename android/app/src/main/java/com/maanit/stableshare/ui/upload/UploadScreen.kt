package com.maanit.stableshare.ui.upload

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.R
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.ui.LocalSnackbar
import com.maanit.stableshare.ui.components.ActionChip
import com.maanit.stableshare.ui.components.CancelTransferDialog
import com.maanit.stableshare.ui.components.CountPill
import com.maanit.stableshare.ui.components.FileChip
import com.maanit.stableshare.ui.components.PrimaryButton
import com.maanit.stableshare.ui.components.TransferRow
import com.maanit.stableshare.ui.components.extensionTag
import com.maanit.stableshare.ui.model.Format
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.theme.Inter
import com.maanit.stableshare.ui.theme.LocalReducedMotion
import com.maanit.stableshare.ui.theme.Motion
import com.maanit.stableshare.ui.theme.Neutral
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Throw timeline (UI-SPEC §5.6), in ms. */
private object Throw {
    const val LIFT_END = 80f
    const val FLIGHT_END = 700f
    const val HOVER_START = 500f
    const val FALL_END = 950f
    const val PLUS_ONE = 760f
    const val SETTLE_END = 1_100f
    /** "+1": in 120 ms, hold 300 ms, out 300 ms. */
    const val END = PLUS_ONE + 720f

    const val REDUCED_FADE = 150f
    const val REDUCED_END = REDUCED_FADE + 600f
}

private val CARD_HEIGHT = 224.dp
private val BACKBOARD_W = 114.dp
private val BACKBOARD_H = 68.dp
private val BACKBOARD_LIFT = 18.dp
private val RIM_W = 140.dp
private val RIM_H = 10.dp
private val NET_TOP_W = 120.dp
private val NET_BOTTOM_W = 64.dp
private val NET_H = 90.dp
private val TRAIL_SPACING = 24.dp

/** Everything the overlay needs about one throw, in overlay coordinates (px). */
private class ThrowPlan(val p0: Offset, val p1: Offset, val p2: Offset, val trail: List<Pair<Offset, Float>>, val tag: String)

private fun bezier(p0: Offset, p1: Offset, p2: Offset, u: Float): Offset {
    val a = 1 - u
    return p0 * (a * a) + p1 * (2 * a * u) + p2 * (u * u)
}

/** Upload screen with the hoop (UI-SPEC §5.6). */
@Composable
fun UploadScreen(vm: UploadViewModel, onBack: () -> Unit, onOpenDetail: (String) -> Unit, perform: (String, TransferAction) -> Unit) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val inQueue by vm.inQueue.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val resources = LocalContext.current.resources
    LaunchedEffect(vm) { vm.failureMessages.collect { snackbar.showSnackbar(it.resolve(resources)) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pick(uri) }

    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val clock = remember { Animatable(0f) }
    val chipScale = remember { Animatable(1f) }
    var plan by remember { mutableStateOf<ThrowPlan?>(null) }
    var throwing by remember { mutableStateOf(false) }
    var heldCount by remember { mutableStateOf<Int?>(null) }
    var justRevealed by remember { mutableStateOf<String?>(null) }
    var cancelTarget by remember { mutableStateOf<TransferItem?>(null) }

    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    var padCenter by remember { mutableStateOf<Offset?>(null) }

    val ready = selection as? Selection.Ready
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val reducedNow by rememberUpdatedState(reduced)
    // Derived so that only the threshold crossings recompose the screen, not every frame.
    val hover by remember {
        derivedStateOf { !reducedNow && throwing && clock.value >= Throw.HOVER_START && clock.value < Throw.FALL_END }
    }

    fun startThrow() {
        val file = ready ?: return
        val card = cardBounds ?: return
        val pad = padCenter ?: return
        if (throwing) return
        throwing = true
        heldCount = inQueue
        val tag = extensionTag(file.name, file.generated)
        with(density) {
            val p0 = pad - overlayOrigin
            val rimY = card.bottom - BACKBOARD_LIFT.toPx() - overlayOrigin.y
            val p2 = Offset(card.center.x - overlayOrigin.x, rimY)
            val p1 = Offset((p0.x + p2.x) / 2, rimY - 120.dp.toPx())
            // Trail dots every 24 dp of path, each stamped with the time the chip passes it.
            val trail = ArrayList<Pair<Offset, Float>>()
            var travelled = 0f
            var next = TRAIL_SPACING.toPx()
            var prev = p0
            val steps = 120
            for (k in 1..steps) {
                val time = Throw.LIFT_END + (Throw.FLIGHT_END - Throw.LIFT_END) * k / steps
                val point = bezier(p0, p1, p2, FastOutSlowInEasing.transform(k / steps.toFloat()))
                travelled += hypot(point.x - prev.x, point.y - prev.y)
                while (travelled >= next) {
                    trail += point to time
                    next += TRAIL_SPACING.toPx()
                }
                prev = point
            }
            plan = ThrowPlan(p0, p1, p2, trail, tag)
        }
        scope.launch {
            clock.snapTo(0f)
            chipScale.snapTo(1f)
            val lift = launch { if (!reduced) clock.animateTo(Throw.LIFT_END, tween(Throw.LIFT_END.toInt(), easing = LinearEasing)) }
            // Create and enqueue first; the animation only celebrates a transfer that exists.
            val id = vm.create()
            lift.join()
            if (id == null) {
                if (!reduced) {
                    chipScale.snapTo(1.06f)
                    clock.snapTo(0f)
                    chipScale.animateTo(1f, Motion.emphasis())
                }
                plan = null
                heldCount = null
                throwing = false
                return@launch
            }
            var counted = false
            var landed = false
            val countAt = if (reduced) Throw.REDUCED_FADE else Throw.PLUS_ONE
            val landAt = if (reduced) Throw.REDUCED_FADE else Throw.FALL_END
            val end = if (reduced) Throw.REDUCED_END else Throw.END
            clock.animateTo(end, tween((end - clock.value).toInt(), easing = LinearEasing)) {
                if (!counted && value >= countAt) {
                    counted = true
                    heldCount = null
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
                    )
                }
                if (!landed && value >= landAt) {
                    landed = true
                    justRevealed = id
                    vm.reveal(id)
                }
                if (value >= (if (reduced) Throw.REDUCED_FADE else Throw.SETTLE_END)) throwing = false
            }
            throwing = false
            plan = null
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Neutral.colors.page)
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    ) {
        val cardWidth = min(maxWidth - 48.dp, 360.dp)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = Neutral.colors.inkPrimary, modifier = Modifier.size(24.dp))
                }
            }
            // Everything between the top bar and the launch pad scrolls, so short screens and large
            // font scales stay usable; the launch pad and button stay pinned at the bottom.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { viewport = it.boundsInRoot() }
                    .verticalScroll(scroll),
            ) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.upload_title), style = Neutral.type.title, modifier = Modifier.weight(1f))
                        CountPill(stringResource(R.string.upload_in_queue), heldCount ?: inQueue)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.upload_subtitle), style = Neutral.type.subtitle)
                }
                Spacer(Modifier.height(20.dp))
                DropZone(
                    width = cardWidth,
                    selected = ready != null,
                    hover = hover,
                    enabled = !throwing,
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .onGloballyPositioned { cardBounds = it.boundsInRoot() },
                )
                // The net hangs 72 dp below the card; the test files start 16 dp below the net.
                Spacer(Modifier.height(NET_H - BACKBOARD_LIFT + 16.dp))
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(stringResource(R.string.test_files_label), style = Neutral.type.small)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            50 to R.string.test_file_50,
                            200 to R.string.test_file_200,
                            500 to R.string.test_file_500,
                            1024 to R.string.test_file_1024,
                        ).forEach { (mb, label) ->
                            ActionChip(stringResource(label), onClick = { vm.generate(mb) }, enabled = !throwing)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // Rows added this visit, newest first; a visit adds only a handful.
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { row ->
                        key(row.item.id) {
                            val isNew = row.item.id == justRevealed
                            val rise = remember { Animatable(if (isNew && !reduced) 1f else 0f) }
                            val bringIntoView = remember { BringIntoViewRequester() }
                            LaunchedEffect(Unit) {
                                if (isNew) bringIntoView.bringIntoView()
                                rise.animateTo(0f, Motion.standard())
                            }
                            TransferRow(
                                row.item,
                                generated = row.generated,
                                onOpen = { onOpenDetail(row.item.id) },
                                onAction = { action ->
                                    if (action == TransferAction.CANCEL) cancelTarget = row.item else perform(row.item.id, action)
                                },
                                modifier = Modifier
                                    .bringIntoViewRequester(bringIntoView)
                                    .graphicsLayer {
                                        translationY = rise.value * 16.dp.toPx()
                                        alpha = 1f - rise.value
                                    },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            LaunchPad(
                selection,
                hidden = plan != null,
                chipScale = { chipScale.value },
                modifier = Modifier.padding(horizontal = 24.dp),
                onSlotPlaced = { padCenter = it },
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                stringResource(R.string.upload_button),
                onClick = ::startThrow,
                enabled = ready != null && !throwing,
                icon = Icons.Outlined.Upload,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .testTag("upload-button"),
            )
            Spacer(Modifier.height(16.dp))
        }

        // Overlay 1: trail dots and the flying chip (between the card and the net).
        plan?.let { p -> FlyingChip(p, clock, reduced) }
        // Overlay 2: rim, net and "+1", always above the chip.
        cardBounds?.let { card ->
            Hoop(card.translate(-overlayOrigin), viewport.translate(-overlayOrigin), clock, reduced = reduced, plusOneVisible = plan != null)
        }
    }

    cancelTarget?.let { target ->
        CancelTransferDialog(target.name, onDismiss = { cancelTarget = null }, onConfirm = {
            cancelTarget = null
            perform(target.id, TransferAction.CANCEL)
        })
    }
}

/** Drop zone card (B.4–B.8): dashed border, icon and copy, backboard. Rim and net are drawn in the overlay. */
@Composable
private fun DropZone(width: Dp, selected: Boolean, hover: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Neutral.colors
    val fill by animateColorAsState(if (hover) c.accentTint else c.card, Motion.quick(), label = "fill")
    val dash by animateColorAsState(if (hover) c.accentDash else c.border, Motion.quick(), label = "dash")
    val title = stringResource(if (selected) R.string.drop_zone_ready_title else R.string.drop_zone_empty_title)
    Box(
        modifier
            .size(width, CARD_HEIGHT)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = CornerRadius(16.dp.toPx())
            drawRoundRect(fill, cornerRadius = r)
            val stroke = 1.5.dp.toPx()
            drawRoundRect(
                dash,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = r,
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
            // Backboard: 114 × 68, white, 2.5 dp orange stroke, 6 dp radius, bottom 18 dp above the card's.
            val bw = BACKBOARD_W.toPx()
            val bh = BACKBOARD_H.toPx()
            val left = (size.width - bw) / 2
            val bottom = size.height - BACKBOARD_LIFT.toPx()
            val top = bottom - bh
            drawRoundRect(c.white, Offset(left, top), Size(bw, bh), CornerRadius(6.dp.toPx()))
            val bs = 2.5.dp.toPx()
            drawRoundRect(c.accent, Offset(left + bs / 2, top + bs / 2), Size(bw - bs, bh - bs), CornerRadius(6.dp.toPx()), style = Stroke(bs))
            // Inner square 40 × 26, centred on the lower half.
            val iw = 40.dp.toPx()
            val ih = 26.dp.toPx()
            val inner = 2.dp.toPx()
            val cy = top + bh * 0.75f
            drawRect(c.accent, Offset(size.width / 2 - iw / 2 + inner / 2, cy - ih / 2 + inner / 2), Size(iw - inner, ih - inner), style = Stroke(inner))
        }
        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Upload, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = Neutral.type.heading, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (selected) R.string.drop_zone_ready_sub else R.string.drop_zone_empty_sub),
                style = Neutral.type.meta,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Launch pad (B.12): the chip with its caption, or a dashed slot with "No file yet". */
@Composable
private fun LaunchPad(selection: Selection, hidden: Boolean, chipScale: () -> Float, modifier: Modifier = Modifier, onSlotPlaced: (Offset) -> Unit) {
    val c = Neutral.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp, 56.dp)
                .onGloballyPositioned {
                    val b = it.boundsInRoot()
                    onSlotPlaced(b.center)
                },
        ) {
            when (selection) {
                Selection.None -> Canvas(Modifier.fillMaxSize()) {
                    val s = 1.5.dp.toPx()
                    drawRoundRect(
                        c.border,
                        topLeft = Offset(s / 2, s / 2),
                        size = Size(size.width - s, size.height - s),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                is Selection.Generating -> FileChip("BIN")
                is Selection.Ready -> if (!hidden) {
                    FileChip(
                        extensionTag(selection.name, selection.generated),
                        Modifier.graphicsLayer {
                            scaleX = chipScale()
                            scaleY = chipScale()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when (selection) {
                Selection.None -> Text(stringResource(R.string.launch_pad_empty), style = Neutral.type.meta)
                is Selection.Generating -> {
                    Text(generatedName(selection.sizeMb), style = Neutral.type.rowTitle.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(stringResource(R.string.launch_pad_generating, selection.percent), style = Neutral.type.meta)
                }
                is Selection.Ready -> {
                    Text(selection.name, style = Neutral.type.rowTitle.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(Format.size(selection.size), style = Neutral.type.meta)
                }
            }
        }
    }
}

/** While generating there is no file name yet; the chip label doubles as the caption ("200 MB"). */
@Composable
private fun generatedName(sizeMb: Int): String = stringResource(
    when (sizeMb) {
        50 -> R.string.test_file_50
        200 -> R.string.test_file_200
        500 -> R.string.test_file_500
        else -> R.string.test_file_1024
    },
)

/** Trail dots and the chip in flight (overlay layer 1). */
@Composable
private fun FlyingChip(plan: ThrowPlan, clock: Animatable<Float, *>, reduced: Boolean) {
    val t = clock.value
    val accent = Neutral.colors.accent
    Canvas(Modifier.fillMaxSize()) {
        if (reduced) return@Canvas
        val r = 2.dp.toPx()
        plan.trail.forEach { (point, at) ->
            val age = t - at
            if (age in 0f..500f) drawCircle(accent, radius = r, center = point, alpha = 1f - age / 500f)
        }
    }
    val density = LocalDensity.current
    val (pos, scale, rotation, alpha) = chipPose(plan, t, reduced, with(density) { 40.dp.toPx() })
    if (alpha <= 0f) return
    val shadow = when {
        reduced -> 12.dp
        t < Throw.LIFT_END -> (12 + 8 * t / Throw.LIFT_END).dp
        else -> 20.dp
    }
    FileChip(
        plan.tag,
        shadowBlur = shadow,
        modifier = Modifier
            .offsetPx(pos - with(density) { Offset(22.dp.toPx(), 28.dp.toPx()) })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
                this.alpha = alpha
            },
    )
}

private data class ChipPose(val position: Offset, val scale: Float, val rotation: Float, val alpha: Float)

private fun chipPose(plan: ThrowPlan, t: Float, reduced: Boolean, fallPx: Float): ChipPose = when {
    reduced -> ChipPose(plan.p0, 1f, 0f, (1f - t / Throw.REDUCED_FADE).coerceIn(0f, 1f))
    t < Throw.LIFT_END -> ChipPose(plan.p0, 1f + 0.06f * t / Throw.LIFT_END, 0f, 1f)
    t < Throw.FLIGHT_END -> {
        val u = FastOutSlowInEasing.transform((t - Throw.LIFT_END) / (Throw.FLIGHT_END - Throw.LIFT_END))
        ChipPose(bezier(plan.p0, plan.p1, plan.p2, u), 1.06f + (0.7f - 1.06f) * u, -18f * u, 1f)
    }
    t < Throw.FALL_END -> {
        val v = (t - Throw.FLIGHT_END) / (Throw.FALL_END - Throw.FLIGHT_END)
        ChipPose(plan.p2 + Offset(0f, fallPx * v), 0.7f + (0.4f - 0.7f) * v, -18f, 1f - v)
    }
    else -> ChipPose(plan.p2, 0.4f, -18f, 0f)
}

/** Rim (B.9), net (B.10) and "+1" (B.11): overlay layer 2, drawn above the flying chip. */
@Composable
private fun Hoop(card: Rect, viewport: Rect, clock: Animatable<Float, *>, reduced: Boolean, plusOneVisible: Boolean) {
    val c = Neutral.colors
    val t = if (plusOneVisible) clock.value else -1f
    // Clipped to the scrolling area, so the hoop never draws over the top bar or the launch pad.
    Canvas(Modifier.fillMaxSize()) { clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom) {
        val rimY = card.bottom - BACKBOARD_LIFT.toPx()
        val cx = card.center.x
        drawNet(cx, rimY, c.net)
        val pulse = if (!reduced && t in 700f..900f) 1f + 0.04f * sin(PI * (t - 700f) / 200f).toFloat() else 1f
        scale(pulse, 1f, pivot = Offset(cx, rimY)) {
            val w = RIM_W.toPx()
            val h = RIM_H.toPx()
            drawOval(c.accent, topLeft = Offset(cx - w / 2, rimY - h / 2), size = Size(w, h), style = Stroke(4.dp.toPx()))
        }
    } }
    if (!plusOneVisible || t < 0f) return
    val start = if (reduced) Throw.REDUCED_FADE else Throw.PLUS_ONE
    val p = t - start
    if (p < 0f) return
    val alpha = when {
        reduced -> if (p < 600f) 1f else 0f
        p < 120f -> p / 120f
        p < 420f -> 1f
        else -> (1f - (p - 420f) / 300f).coerceAtLeast(0f)
    }
    val density = LocalDensity.current
    val rise = if (reduced) 0f else with(density) { 16.dp.toPx() } * (p / 120f).coerceAtMost(1f)
    // At the backboard's top-right corner.
    val corner = with(density) {
        Offset(card.center.x + BACKBOARD_W.toPx() / 2, card.bottom - BACKBOARD_LIFT.toPx() - BACKBOARD_H.toPx())
    }
    Text(
        stringResource(R.string.upload_plus_one),
        color = c.accent,
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        modifier = Modifier
            .offsetPx(corner - with(density) { Offset(0f, 24.dp.toPx()) })
            .graphicsLayer {
                translationY = -rise
                this.alpha = alpha
            },
    )
}

/** Crisscross net: 120 dp at the rim narrowing to 64 dp, 90 dp tall, 6 diagonals each way. */
private fun DrawScope.drawNet(cx: Float, top: Float, color: Color) {
    val topW = NET_TOP_W.toPx()
    val bottomW = NET_BOTTOM_W.toPx()
    val bottom = top + NET_H.toPx()
    val stroke = 1.5.dp.toPx()
    val n = 6
    fun topAt(i: Int) = Offset(cx - topW / 2 + topW * i / n, top)
    fun bottomAt(i: Int) = Offset(cx - bottomW / 2 + bottomW * i / n, bottom)
    for (i in 0 until n) {
        drawLine(color, topAt(i), bottomAt(i + 1), stroke)
        drawLine(color, topAt(i + 1), bottomAt(i), stroke)
    }
    drawLine(color, topAt(0), bottomAt(0), stroke)
    drawLine(color, topAt(n), bottomAt(n), stroke)
}

private fun Modifier.offsetPx(offset: Offset): Modifier =
    offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
