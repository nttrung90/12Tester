package com.ntt.crosstest.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ntt.crosstest.data.Localizer

/**
 * Universal text descriptor supporting both raw text (e.g. user input, URLs)
 * and localized string resources that resolve dynamically at render time.
 * Prevents stale cached strings across language switches.
 */
sealed class UiText {
    data class DynamicString(val value: String) : UiText()
    data class StringResource(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText()

    @Composable
    fun asString(): String = when (this) {
        is DynamicString -> value
        is StringResource -> if (args.isEmpty()) {
            stringResource(resId)
        } else {
            stringResource(resId, *args.toTypedArray())
        }
    }

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> if (args.isEmpty()) {
            context.getString(resId)
        } else {
            context.getString(resId, *args.toTypedArray())
        }
    }

    fun asString(localizer: Localizer): String = when (this) {
        is DynamicString -> value
        is StringResource -> if (args.isEmpty()) {
            localizer.get(resId)
        } else {
            localizer.get(resId, *args.toTypedArray())
        }
    }

    companion object {
        fun raw(value: String): UiText = DynamicString(value)
        fun res(@StringRes resId: Int, vararg args: Any): UiText = StringResource(resId, args.toList())
    }
}
