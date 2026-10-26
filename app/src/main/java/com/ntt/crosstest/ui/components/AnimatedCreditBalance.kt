package com.ntt.crosstest.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Credit balance with a short count-up animation and a light pulse whenever the
 * value increases.
 *
 * The first composition never animates: [animateIntAsState] starts at the
 * current balance, so opening a screen that already has a cached balance does
 * not play a "0 → N" count-up. Animations are replaced with [snap] when the
 * system animator duration scale is disabled (Reduce motion / "Remove
 * animations").
 *
 * The node exposes a polite live region with the final balance, so TalkBack
 * announces the new balance once instead of every intermediate frame.
 */
@Composable
fun AnimatedCreditBalance(
    balance: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    pulseOnChange: Boolean = true,
    format: (Int) -> String = { it.toString() },
    accessibilityLabel: String? = null,
) {
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val displayed by animateIntAsState(
        targetValue = balance,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(durationMillis = 650, easing = FastOutSlowInEasing)
        },
        label = "creditBalance",
    )
    val pulse = remember { Animatable(1f) }
    var previousBalance by remember { mutableIntStateOf(balance) }

    LaunchedEffect(balance) {
        if (balance == previousBalance) return@LaunchedEffect
        val increasing = balance > previousBalance
        previousBalance = balance
        if (reducedMotion || !pulseOnChange || !increasing) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(1.10f, tween(durationMillis = 120, easing = FastOutSlowInEasing))
        pulse.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
    }

    val targetText = format(balance)
    Text(
        text = format(displayed),
        modifier = modifier
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .clearAndSetSemantics {
                contentDescription = accessibilityLabel ?: targetText
                liveRegion = LiveRegionMode.Polite
            },
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
    )
}
