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
                profile = app.campaignRepository.getMyProfile()
                // If profile is already unlocked (e.g. by Admin), proceed
                if (profile?.isLocked == false) {
                    onUnlocked()
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.localizedMessage ?: "Lỗi khi tải thông tin tài khoản.")
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
                    text = "Xác nhận mở khóa",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Bạn có chắc chắn muốn dùng 100 Credit để mở khóa tài khoản ngay lập tức?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "• Số dư hiện tại: ${profile?.credits ?: 0} Credit\n• Số dư sau khi mở khóa: ${(profile?.credits ?: 0) - 100} Credit",
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
                                    Toast.makeText(context, "Mở khóa tài khoản thành công!", Toast.LENGTH_LONG).show()
                                    onUnlocked()
                                } else {
                                    snackbarHostState.showSnackbar("Không thể mở khóa tài khoản.")
                                }
                            } catch (e: Exception) {
                                val msg = e.localizedMessage ?: "Lỗi khi mở khóa."
                                snackbarHostState.showSnackbar(
                                    if ("INSUFFICIENT" in msg.uppercase()) "Số dư không đủ 100 Credit." else msg
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
                        Text("Đang mở khóa...")
                    } else {
                        Text("Trừ 100 Credit & Mở khóa")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    enabled = !isUnlocking,
                ) {
                    Text("Hủy")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trạng thái tài khoản", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                app.authRepository.signOut()
                                onSignedOut()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Đăng xuất",
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
                    text = "Tài khoản bị tạm khóa",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Hệ thống ghi nhận tài khoản của bạn nhận đủ 3 lần cảnh báo trong 1 tuần (hoặc bị khóa bởi Quản trị viên).",
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
                            text = "Tài khoản:",
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
                            text = "Số dư hiện tại:",
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
                        text = "Để tiếp tục sử dụng ứng dụng và tham gia kiểm thử chéo, bạn có thể chọn 1 trong 2 hình thức mở khóa bên dưới:",
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
                            text = "Lựa chọn 1: Tự mở khóa ngay",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "Trừ 100 Credit từ tài khoản để mở khóa ngay lập tức và đặt lại chu kỳ cảnh báo.",
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
                            text = if (canUnlockWithCredits) "Trừ 100 Credit để mở khóa" else "Không đủ 100 Credit (Cần thêm ${100 - credits} C)",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (!canUnlockWithCredits) {
                        Text(
                            text = "💡 Số dư không đủ 100 Credit. Vui lòng chọn Lựa chọn 2 để gửi email tới Admin.",
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
                            text = "Lựa chọn 2: Liên hệ Admin",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "Gửi email giải trình tới Quản trị viên tại firstntt90@gmail.com để được hỗ trợ kiểm tra và mở khóa tài khoản thủ công.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val userEmail = profile?.email ?: "N/A"
                            val userId = profile?.id ?: "N/A"
                            val subject = Uri.encode("[12 Tester] Yêu cầu mở khóa tài khoản - $userEmail")
                            val body = Uri.encode(
                                "Chào Quản trị viên 12 Tester,\n\n" +
                                "Tài khoản của tôi bị tạm khóa do nhận cảnh báo kiểm thử.\n" +
                                "- Email: $userEmail\n" +
                                "- User ID: $userId\n\n" +
                                "Lý do / Giải trình:\n[Vui lòng nhập lý do của bạn tại đây...]\n\n" +
                                "Kính nhờ Admin hỗ trợ kiểm tra và mở khóa tài khoản giúp tôi.\nXin cảm ơn!"
                            )
                            val mailtoUri = Uri.parse("mailto:$ADMIN_EMAIL?subject=$subject&body=$body")
                            val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri)
                            try {
                                context.startActivity(emailIntent)
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Không tìm thấy ứng dụng email. Vui lòng gửi thư tới: $ADMIN_EMAIL",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Gửi email tới firstntt90@gmail.com", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Logout Option
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        app.authRepository.signOut()
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
                    text = "Đăng xuất tài khoản",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
