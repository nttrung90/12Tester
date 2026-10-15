package com.ntt.crosstest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.util.TopupConfig

/**
 * Compact Credit badge displayed on the Home TopAppBar.
 * Shows current balance and an optional Top-up action button.
 * The top-up button is conditionally rendered based on [isTopupEnabled] (Feature Flag).
 */
@Composable
fun CreditTopupBadge(
    credits: Int,
    onBadgeClick: () -> Unit,
    onTopupClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTopupEnabled: Boolean = TopupConfig.IS_TOPUP_FEATURE_ENABLED,
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(start = 10.dp, end = if (isTopupEnabled) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Balance display (Clickable to view Credit History)
        Row(
            modifier = Modifier.clickable { onBadgeClick() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "🪙",
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "$credits",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        // Top-up Button (strictly controlled by Feature Flag)
        if (isTopupEnabled) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFFFFB300)) // Amber gold
                    .clickable { onTopupClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.topup_btn),
                        tint = Color(0xFF3E2723),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(R.string.topup_btn),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF3E2723),
                    )
                }
            }
        }
    }
}
