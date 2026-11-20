package com.ntt.crosstest.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ntt.crosstest.R

/**
 * Play Store closed-testing progress: testers reached vs 20, and the highest
 * per-tester day count vs 14.
 */
@Composable
fun ProgressSection(
    testerCount: Int,
    targetTesters: Int,
    maxDays: Int,
    targetDays: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ProgressRow(
                label = stringResource(R.string.progress_testers, testerCount, targetTesters),
                current = testerCount,
                target = targetTesters,
            )
            Spacer(Modifier.height(12.dp))
            ProgressRow(
                label = stringResource(R.string.progress_days, maxDays, targetDays),
                current = maxDays,
                target = targetDays,
            )
        }
    }
}

@Composable
private fun ProgressRow(label: String, current: Int, target: Int) {
    val progress = if (target > 0) (current.toFloat() / target).coerceIn(0f, 1f) else 0f
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (current >= target) {
                Text(text = "✓", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
