package com.maanit.stableshare.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Motion tokens (UI-SPEC §3.5). */
object Motion {
    const val QUICK_MS = 150
    const val STANDARD_MS = 250
    const val LONG_MS = 600
    const val PROGRESS_MS = 300

    fun <T> quick(): FiniteAnimationSpec<T> = tween(QUICK_MS, easing = FastOutSlowInEasing)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(STANDARD_MS, easing = FastOutSlowInEasing)
    fun <T> emphasis(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.7f, stiffness = 300f)

    /** Progress bars easing between updates (UI-SPEC §12.6). */
    fun <T> progress(): FiniteAnimationSpec<T> = tween(PROGRESS_MS, easing = LinearEasing)
}

/**
 * True when the user turned animations off (UI-SPEC §10): "Remove animations" sets
 * ANIMATOR_DURATION_SCALE to 0. Provided by [StableShareTheme]; tests override it.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
