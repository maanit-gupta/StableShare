package com.maanit.stableshare.ui.model

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Copy decided by pure presentation code and resolved against resources only when shown, so the
 * mapping from state to words is unit-testable. Arguments may themselves be [UiText].
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    /** Text that is already final (stored messages, file names). */
    data class Raw(val text: String) : UiText

    fun resolve(res: Resources): String = when (this) {
        is Res -> res.getString(id, *args.map { it.resolveArg(res) }.toTypedArray())
        is Plural -> res.getQuantityString(id, count, *args.map { it.resolveArg(res) }.toTypedArray())
        is Raw -> text
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText = Plural(id, count, args.toList())
    }
}

private fun Any.resolveArg(res: Resources): Any = if (this is UiText) resolve(res) else this

@Composable
@ReadOnlyComposable
fun UiText.text(): String = resolve(LocalContext.current.resources)
