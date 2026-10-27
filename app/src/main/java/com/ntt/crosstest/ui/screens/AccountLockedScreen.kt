package com.ntt.crosstest.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Profile
import kotlinx.coroutines.launch

private const val ADMIN_EMAIL = "firstntt90@gmail.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountLockedScreen(
    onUnlocked: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as TestCheoApp
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var isUnlocking by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    fun refreshProfile() {
        coroutineScope.launch {
            isLoading = true
            try {
                profile = app.campaignRepository.getMyProfile(forceRefresh = true)
                // If profile is already unlocked (e.g. by Admin), proceed
                if (profile?.isLocked == false) {
                    onUnlocked()
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.localizedMessage ?: resources.getString(R.string.account_locked_load_failed))
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshProfile()
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!isUnlocking) showConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.locked_confirm_title),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.locked_confirm_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.locked_confirm_balance_format, profile?.credits ?: 0, (profile?.credits ?: 0) - 100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isUnlocking = true
                            try {
                                val result = app.campaignRepository.selfUnlockAccount()
                                if (result.success) {
                                    showConfirmDialog = false
                                    Toast.makeText(context, resources.getString(R.string.locked_unlock_success), Toast.LENGTH_LONG).show()
                                    onUnlocked()
                                } else {
                                    snackbarHostState.showSnackbar(resources.getString(R.string.locked_unlock_failed))
                                }
                            } catch (e: Exception) {
                                val msg = e.localizedMessage ?: resources.getString(R.string.locked_unlock_error)
                                snackbarHostState.showSnackbar(
                                    if ("INSUFFICIENT" in msg.uppercase()) resources.getString(R.string.locked_insufficient_credits) else msg
                                )
                            } finally {
                                isUnlocking = false
                            }
                        }
                    },
                    enabled = !isUnlocking,
                ) {
                    if (isUnlocking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.locked_unlocking))
                    } else {
                        Text(stringResource(R.string.locked_unlock_deduct_btn))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    enabled = !isUnlocking,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.locked_screen_title), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                app.performSignOut()
                                onSignedOut()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.logout),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val credits = profile?.credits ?: 0
        val canUnlockWithCredits = credits >= 100

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Lock Hero Icon
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFEBEE)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Locked",
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(48.dp),
                )
            }

            // Title & Subtitle
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.locked_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.locked_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // User Info & Credit Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.locked_account_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = profile?.email ?: "Tester",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.create_current_balance_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "$credits Credit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (canUnlockWithCredits) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        )
                    }
                }
            }

            // Instruction Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFF57F17),
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.locked_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Option 1: Deduct 100 Credit to unlock immediately
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.locked_option1_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = stringResource(R.string.locked_option1_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canUnlockWithCredits && !isUnlocking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2C4A73),
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = if (canUnlockWithCredits) stringResource(R.string.locked_option1_btn) else stringResource(R.string.locked_option1_btn_insufficient_format, 100 - credits),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (!canUnlockWithCredits) {
                        Text(
                            text = stringResource(R.string.locked_insufficient_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD32F2F),
                        )
                    }
                }
            }

            // Option 2: Contact Admin via Email
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Email,
                            contentDescription = null,
                            tint = Color(0xFF1E88E5),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.locked_option2_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = stringResource(R.string.locked_option2_desc, ADMIN_EMAIL),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val userEmail = profile?.email ?: "N/A"
                            val userId = profile?.id ?: "N/A"
                            val emailAndId = "$userEmail\n- User ID: $userId"
                            val subject = Uri.encode(resources.getString(R.string.account_locked_email_subject, userEmail))
                            val body = Uri.encode(resources.getString(R.string.account_locked_email_body_template, emailAndId))
                            val mailtoUri = Uri.parse("mailto:$ADMIN_EMAIL?subject=$subject&body=$body")
                            val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
                                // LocalContext is a localized wrapper (not an Activity).
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(emailIntent)
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.locked_no_email_app_format, ADMIN_EMAIL),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.locked_send_email_btn_format, ADMIN_EMAIL), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Logout Option
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        app.performSignOut()
                        onSignedOut()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.logout),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
