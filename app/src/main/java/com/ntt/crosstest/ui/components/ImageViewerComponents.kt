package com.ntt.crosstest.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.util.ImageHashHelper
import kotlinx.coroutines.launch

/**
 * Fullscreen Zoomable Image Lightbox with Pinch-to-zoom, Pan, and Double-tap 2.5x zoom.
 */
@Composable
fun FullscreenImageViewer(
    imageUrl: String?,
    onDismiss: () -> Unit,
    title: String = "Ảnh chụp màn hình",
    subtitle: String? = null,
) {
    if (imageUrl.isNullOrBlank()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF00F172A))
                .systemBarsPadding()
        ) {
            // Main Zoomable Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    // Zoom into tapped location
                                    val centerX = size.width / 2f
                                    val centerY = size.height / 2f
                                    offset = Offset(
                                        (centerX - tapOffset.x) * 1.5f,
                                        (centerY - tapOffset.y) * 1.5f
                                    )
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            if (scale > 1f) {
                                val maxOffsetX = (scale - 1f) * 600f
                                val maxOffsetY = (scale - 1f) * 900f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                ) {
                    val state = painter.state
                    when (state) {
                        is AsyncImagePainter.State.Loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                        is AsyncImagePainter.State.Error -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Không thể tải ảnh", color = Color.White)
                            }
                        }
                        else -> {
                            SubcomposeAsyncImageContent()
                        }
                    }
                }
            }

            // Top Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scale > 1.1f) {
                        IconButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ZoomOutMap,
                                contentDescription = "Thu nhỏ 1x",
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Đóng",
                            tint = Color.White
                        )
                    }
                }
            }

            // Bottom Helper Hint
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color(0x80000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Chạm 2 lần hoặc vuốt 2 ngón tay để phóng to / thu nhỏ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE2E8F0)
                )
            }
        }
    }
}

/**
 * Side-by-Side and Split Comparison Dialog between two reports (e.g., Today's report vs Previous day's report).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageComparisonDialog(
    currentReport: TesterReport,
    previousReport: TesterReport?,
    onDismiss: () -> Unit,
    onApprove: (() -> Unit)? = null,
    onReject: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var comparisonResult by remember { mutableStateOf<ImageHashHelper.ComparisonResult?>(null) }
    var isCalculatingHash by remember { mutableStateOf(false) }

    // Calculate dHash comparison asynchronously on load
    LaunchedEffect(currentReport.screenshotUrl, previousReport?.screenshotUrl) {
        val url1 = currentReport.screenshotUrl
        val url2 = previousReport?.screenshotUrl
        if (!url1.isNullOrBlank() && !url2.isNullOrBlank()) {
            isCalculatingHash = true
            val hash1 = ImageHashHelper.computeDHashFromUrl(context, url1)
            val hash2 = ImageHashHelper.computeDHashFromUrl(context, url2)
            if (hash1 != null && hash2 != null) {
                comparisonResult = ImageHashHelper.compare(hash1, hash2)
            }
            isCalculatingHash = false
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Song song, 1: So sánh đè (Slider), 2: Chớp ảnh (Blink)
    var sliderRatio by remember { mutableFloatStateOf(0.5f) }
    var blinkShowCurrent by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "So sánh ảnh kiểm thử 🔍",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Tester: ${currentReport.resolvedTesterName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Đóng")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Quick Action Buttons if callbacks provided
                        if (currentReport.isPending && onApprove != null && onReject != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onReject,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Từ chối ảnh này")
                                }
                                Button(
                                    onClick = onApprove,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32)
                                    )
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Duyệt (+1 C)")
                                }
                            }
                        } else {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Đóng đối chiếu")
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Similarity Assessment Banner
                val res = comparisonResult
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isCalculatingHash -> MaterialTheme.colorScheme.surfaceVariant
                            res?.level == ImageHashHelper.SimilarityLevel.DUPLICATE -> Color(0xFFFFEBEE)
                            res?.level == ImageHashHelper.SimilarityLevel.HIGH_SIMILARITY -> Color(0xFFFFF3E0)
                            res?.level == ImageHashHelper.SimilarityLevel.MODERATE -> Color(0xFFE8F5E9)
                            res?.level == ImageHashHelper.SimilarityLevel.DIFFERENT -> Color(0xFFE3F2FD)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isCalculatingHash) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(
                                "Đang phân tích cấu trúc 2 bức ảnh...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (res != null) {
                            val badgeColor = when (res.level) {
                                ImageHashHelper.SimilarityLevel.DUPLICATE -> Color(0xFFC62828)
                                ImageHashHelper.SimilarityLevel.HIGH_SIMILARITY -> Color(0xFFE65100)
                                ImageHashHelper.SimilarityLevel.MODERATE -> Color(0xFF2E7D32)
                                ImageHashHelper.SimilarityLevel.DIFFERENT -> Color(0xFF1565C0)
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (res.isSuspectDuplicate) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = res.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                                Text(
                                    text = if (res.isSuspectDuplicate) {
                                        "Cảnh báo: Ảnh có độ tương đồng rất cao (${res.similarity}%). Đề nghị kiểm tra kỹ đồng hồ status bar, % pin, và giao diện xem tester có nộp lại ảnh cũ không."
                                    } else {
                                        "Độ tương đồng: ${res.similarity}%. Ảnh thể hiện tính năng/màn hình có sự khác biệt rõ rệt."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else if (previousReport?.screenshotUrl.isNullOrBlank()) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "Tester này chưa có ảnh ngày trước để đối chiếu (đây là báo cáo đầu tiên).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "Không thể tính toán hash ảnh. Bạn vẫn có thể xem đối chiếu 2 ảnh bên dưới.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Comparison Mode Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Song song", fontSize = 13.sp) },
                        icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("So sánh đè", fontSize = 13.sp) },
                        icon = { Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Chớp đổi (A/B)", fontSize = 13.sp) },
                        icon = { Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Content Views
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    when (selectedTab) {
                        0 -> SideBySideView(currentReport, previousReport)
                        1 -> SplitSliderView(currentReport.screenshotUrl, previousReport?.screenshotUrl, sliderRatio) { sliderRatio = it }
                        2 -> BlinkView(currentReport, previousReport, blinkShowCurrent) { blinkShowCurrent = !blinkShowCurrent }
                    }
                }
            }
        }
    }
}

@Composable
private fun SideBySideView(
    currentReport: TesterReport,
    previousReport: TesterReport?
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left Column: Current Report
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Hôm nay",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        currentReport.createdAt?.take(16)?.replace("T", " ") ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                AsyncImage(
                    model = currentReport.screenshotUrl,
                    contentDescription = "Ảnh hôm nay",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Right Column: Previous Report
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Ngày trước đó",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        previousReport?.createdAt?.take(16)?.replace("T", " ") ?: "Chưa có",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                if (previousReport?.screenshotUrl != null) {
                    AsyncImage(
                        model = previousReport.screenshotUrl,
                        contentDescription = "Ảnh ngày trước",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Không có ảnh cũ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitSliderView(
    url1: String?,
    url2: String?,
    ratio: Float,
    onRatioChange: (Float) -> Unit,
) {
    if (url1 == null || url2 == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Cần cả 2 ảnh để thực hiện so sánh đè.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val width = maxWidth
            val height = maxHeight

            // Bottom layer: Old image (full)
            AsyncImage(
                model = url2,
                contentDescription = "Ảnh cũ",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Top layer: New image (clipped horizontally based on ratio)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ratio
                    }
            ) {
                AsyncImage(
                    model = url1,
                    contentDescription = "Ảnh mới",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // Indicator badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xB3000000)
            ) {
                Text(
                    text = "Độ phủ: ${ (ratio * 100).toInt() }% Ảnh mới · ${ ((1f - ratio) * 100).toInt() }% Ảnh cũ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Slider control
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ảnh cũ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Slider(
                value = ratio,
                onValueChange = onRatioChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("Ảnh mới", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BlinkView(
    currentReport: TesterReport,
    previousReport: TesterReport?,
    showCurrent: Boolean,
    onToggle: () -> Unit,
) {
    if (previousReport?.screenshotUrl == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Không có ảnh cũ để chớp đổi.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable { onToggle() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val currentUrl = if (showCurrent) currentReport.screenshotUrl else previousReport.screenshotUrl
                AsyncImage(
                    model = currentUrl,
                    contentDescription = if (showCurrent) "Ảnh hôm nay" else "Ảnh hôm trước",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = if (showCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = if (showCurrent) "ĐANG HIỆN: ẢNH HÔM NAY" else "ĐANG HIỆN: ẢNH HÔM TRƯỚC",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (showCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onToggle,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Chạm để đổi ảnh (hoặc chạm trực tiếp vào hình)")
        }
    }
}
