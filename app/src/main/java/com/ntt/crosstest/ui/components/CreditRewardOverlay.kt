package com.ntt.crosstest.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.util.CoinSoundPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Which flow produced a Credit reward. */
enum class CreditRewardType {
    TEST_SUBMITTED,
    OWNER_APPROVED,
    REPORT_APPROVED_REMOTE,
    QUEST_CLAIMED,
    TOPUP,
}

/**
 * One reward to show in [CreditRewardOverlay].
 *
 * [id] must be unique per reward so showing two rewards back-to-back restarts
 * the animation/sound/timer instead of being considered the same event.
 */
data class CreditReward(
    val type: CreditRewardType,
    val amount: Int,
    val ownerAmount: Int = 0,
    val tipAmount: Int = 0,
    val appName: String = "",
    val newBalance: Int? = null,
    val id: Long = System.nanoTime(),
)

/**
 * Small, non-blocking reward card with a coin burst.
 *
 * The outer Box only uses layout; it has no clickable/pointer input, so touches
 * pass through to the screen underneath. The card disappears on its own after
 * ~2 seconds and also respects the system "remove animations" setting.
 *
 * @param compact single-line banner used for remote approvals.
 */
@Composable
fun CreditRewardOverlay(
    reward: CreditReward?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val latestReward by rememberUpdatedState(reward)
    var displayed by remember { mutableStateOf<CreditReward?>(null) }
    val burst = remember { Animatable(0f) }

    LaunchedEffect(reward?.id) {
        val current = reward ?: return@LaunchedEffect
        displayed = current
        CoinSoundPlayer.play(context.applicationContext)
        burst.snapTo(0f)
        if (reducedMotion) {
            burst.snapTo(1f)
        } else {
            launch {
                burst.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                )
            }
        }
        delay(if (compact) 1800L else 2000L)
        if (latestReward?.id == current.id) {
            onDismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = reward != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            enter = if (reducedMotion) {
                EnterTransition.None
            } else {
                fadeIn(tween(180)) +
                    slideInVertically(tween(220)) { -it / 2 } +
                    scaleIn(tween(220), initialScale = 0.92f)
            },
            exit = if (reducedMotion) {
                ExitTransition.None
            } else {
                fadeOut(tween(180)) +
                    slideOutVertically(tween(180)) { -it / 2 } +
                    scaleOut(tween(180), targetScale = 0.96f)
            },
        ) {
            displayed?.let { current ->
                if (compact) {
                    CompactRewardCard(current)
                } else {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .height(148.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CoinBurst(
                            progress = { burst.value },
                            modifier = Modifier.fillMaxSize(),
                        )
                        RewardCard(current)
                    }
                }
            }
        }
    }
}

@Composable
private fun RewardCard(reward: CreditReward) {
    val dark = isSystemInDarkTheme()
    val isTopUp = reward.type == CreditRewardType.TOPUP
    val container = if (isTopUp) {
        if (dark) Color(0xFF14361B) else Color(0xFFE8F5E9)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val accent = when {
        isTopUp -> if (dark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
        else -> Color(0xFFB8860B)
    }
    val borderColor = if (isTopUp) {
        if (dark) Color(0xFF4CAF50) else Color(0xFF66BB6A)
    } else {
        Color(0xFFFFB300).copy(alpha = 0.65f)
    }
    val title = rewardTitle(reward)
    val subtitle = rewardSubtitle(reward)
    val ownerLines = ownerLines(reward)
    val description = buildDescription(title, subtitle, ownerLines, reward)

    Card(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            color = if (isTopUp) {
                                accent.copy(alpha = 0.16f)
                            } else {
                                Color(0xFFFFB300).copy(alpha = 0.18f)
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = if (isTopUp) "🧾" else "🪙", fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (ownerLines.isEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.credit_reward_amount_format, reward.amount),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = accent,
                        )
                        Text(
                            text = stringResource(R.string.credit_unit),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                    }
                }
            }

            if (ownerLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ownerLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
            }

            reward.newBalance?.let { balance ->
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = borderColor.copy(alpha = 0.35f))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.credit_reward_balance_format, balance),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun CompactRewardCard(reward: CreditReward) {
    val title = rewardTitle(reward)
    val subtitle = rewardSubtitle(reward)
    val description = buildDescription(title, subtitle, emptyList(), reward)
    Card(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSystemInDarkTheme()) Color(0xFF14361B) else Color(0xFFE8F5E9),
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSystemInDarkTheme()) Color(0xFF4CAF50) else Color(0xFF66BB6A),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "🪙", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.credit_reward_amount_format, reward.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (isSystemInDarkTheme()) Color(0xFFA5D6A7) else Color(0xFF2E7D32),
            )
        }
    }
}

/** Gold particles radiating from behind the reward card. */
@Composable
private fun CoinBurst(
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val p = progress()
        if (p <= 0f || p >= 1f) return@Canvas
        val count = 10
        val radius = 4.dp.toPx()
        for (i in 0 until count) {
            val angle = (-170f + i * 18f) * (PI.toFloat() / 180f)
            val distance = size.minDimension * (0.12f + 0.58f * p)
            val gravity = p * p * 20.dp.toPx()
            val x = center.x + cos(angle) * distance
            val y = center.y + sin(angle) * distance + gravity
            val alpha = (1f - p).coerceIn(0f, 1f)
            drawCircle(
                color = Color(0xFFFFC107).copy(alpha = alpha * 0.95f),
                radius = radius,
                center = Offset(x, y),
            )
            drawCircle(
                color = Color(0xFFFFA000).copy(alpha = alpha),
                radius = radius * 0.55f,
                center = Offset(x, y),
            )
        }
    }
}

@Composable
private fun rewardTitle(reward: CreditReward): String = when (reward.type) {
    CreditRewardType.TEST_SUBMITTED -> stringResource(R.string.credit_reward_test_submitted_title)
    CreditRewardType.OWNER_APPROVED -> stringResource(R.string.credit_reward_owner_approved_title)
    CreditRewardType.REPORT_APPROVED_REMOTE -> stringResource(R.string.credit_reward_remote_title)
    CreditRewardType.QUEST_CLAIMED -> stringResource(R.string.credit_reward_quest_title)
    CreditRewardType.TOPUP -> stringResource(R.string.credit_reward_topup_title)
}

@Composable
private fun rewardSubtitle(reward: CreditReward): String? = when (reward.type) {
    CreditRewardType.TEST_SUBMITTED -> reward.appName.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.credit_reward_test_submitted_subtitle_format, it)
    } ?: stringResource(R.string.credit_reward_provisional_label)
    CreditRewardType.REPORT_APPROVED_REMOTE -> stringResource(R.string.credit_reward_remote_subtitle)
    CreditRewardType.QUEST_CLAIMED -> stringResource(R.string.credit_reward_quest_subtitle)
    CreditRewardType.TOPUP -> stringResource(R.string.credit_reward_topup_subtitle)
    CreditRewardType.OWNER_APPROVED -> null
}

@Composable
private fun ownerLines(reward: CreditReward): List<String> {
    if (reward.type != CreditRewardType.OWNER_APPROVED) return emptyList()
    val lines = mutableListOf<String>()
    if (reward.amount > 0) {
        lines.add(stringResource(R.string.credit_reward_tester_amount_format, reward.amount))
    }
    if (reward.ownerAmount > 0) {
        lines.add(stringResource(R.string.credit_reward_you_amount_format, reward.ownerAmount))
    }
    if (reward.tipAmount > 0) {
        lines.add(stringResource(R.string.credit_reward_tip_amount_format, reward.tipAmount))
    }
    return lines
}

@Composable
private fun buildDescription(
    title: String,
    subtitle: String?,
    ownerLines: List<String>,
    reward: CreditReward,
): String {
    val parts = mutableListOf(title)
    if (subtitle != null) parts.add(subtitle)
    parts.addAll(ownerLines)
    if (ownerLines.isEmpty()) {
        parts.add(stringResource(R.string.credit_reward_amount_format, reward.amount))
    }
    reward.newBalance?.let { parts.add(stringResource(R.string.credit_reward_balance_format, it)) }
    return parts.joinToString(". ")
}
