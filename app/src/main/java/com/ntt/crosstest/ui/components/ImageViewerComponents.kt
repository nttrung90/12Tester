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
import androidx.compose.ui.res.stringResource
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
import com.ntt.crosstest.R
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.util.ImageHashHelper
import com.ntt.crosstest.util.ScreenshotManager
import com.ntt.crosstest.util.ScreenshotExpiredException
import com.ntt.crosstest.util.ScreenshotUnavailableException
import com.ntt.crosstest.util.ScreenshotPermissionDeniedException
import com.ntt.crosstest.util.ScreenshotDescriptor
import com.ntt.crosstest.util.ScreenshotRequestFactory
import kotlinx.coroutines.delay

/**
 * Fullscreen Zoomable Image Lightbox with Pinch-to-zoom, Pan, and Double-tap 2.5x zoom.
 */
@Composable
fun FullscreenImageViewer(
    imageUrl: String?,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.img_screenshot_title),
    subtitle: String? = null,
    isExpired: Boolean = false,
    report: TesterReport? = null,
    descriptor: ScreenshotDescriptor? = null,
) {
    if (imageUrl.isNullOrBlank()) return
    val context = LocalContext.current

    // Cập nhật động: tự động chuyển sang trạng thái hết hạn nếu dialog mở qua mốc 30 ngày (E01d)
    var isMediaExpired by remember(imageUrl, isExpired, report, descriptor) {
        mutableStateOf(
            isExpired ||
            (report?.isScreenshotExpired ?: false) ||
            ScreenshotManager.isExpired(
                imageUrl,
                report?.createdAt ?: descriptor?.createdAt,
                report?.mediaExpired ?: descriptor?.mediaExpired
            )
        )
    }

    LaunchedEffect(imageUrl, isExpired, report, descriptor) {
        if (isExpired || (report?.isScreenshotExpired == true)) {
            isMediaExpired = true
            return@LaunchedEffect
        }
        val effCreatedAt = report?.createdAt ?: descriptor?.createdAt
        val effMediaExpired = report?.mediaExpired ?: descriptor?.mediaExpired
        if (ScreenshotManager.isExpired(imageUrl, effCreatedAt, effMediaExpired)) {
            isMediaExpired = true
            return@LaunchedEffect
        }
        while (!isMediaExpired) {
            delay(5000L)
            if (ScreenshotManager.isExpired(imageUrl, effCreatedAt, effMediaExpired)) {
                isMediaExpired = true
                break
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        var scale by remember(imageUrl) { mutableFloatStateOf(1f) }
        var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }

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
                if (isMediaExpired) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.img_expired_storage_policy),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.img_expired_storage_policy_desc),
                            color = Color(0xFFCBD5E1),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val imageRequest = remember(context, imageUrl, report, descriptor) {
                        ScreenshotRequestFactory.createRequest(
                            context = context,
                            model = imageUrl,
                            report = report,
                            descriptor = descriptor,
                            crossfade = false,
                        )
                    }
                    SubcomposeAsyncImage(
                        model = imageRequest ?: imageUrl,
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
                                val err = state.result.throwable
                                val isPermissionDenied = err is ScreenshotPermissionDeniedException
                                val isExpiredError = isMediaExpired ||
                                    err is ScreenshotExpiredException ||
                                    err is ScreenshotUnavailableException ||
                                    ScreenshotManager.isExpired(imageUrl)

                                if (isExpiredError && !isPermissionDenied) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Schedule,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(56.dp)
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = stringResource(R.string.img_expired_storage_policy),
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = stringResource(R.string.img_expired_storage_policy_desc),
                                            color = Color(0xFFCBD5E1),
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Filled.BrokenImage,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(stringResource(R.string.img_load_failed), color = Color.White)
                                        }
                                    }
                                }
                            }
                            else -> {
                                SubcomposeAsyncImageContent()
                            }
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
                                contentDescription = stringResource(R.string.img_reset_zoom_cd),
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close),
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
                    text = stringResource(R.string.img_zoom_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE2E8F0)
                )
            }
        }
    }
}

/**
 * Side-by-Side and Split Comparison Dialog between two reports
 * (e.g., Today's report vs Previous day's report).
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

    var comparisonResult by remember { mutableStateOf<ImageHashHelper.ComparisonResult?>(null) }
    var isCalculatingHash by remember { mutableStateOf(false) }

    val currentRequest = remember(context, currentReport) {
        ScreenshotRequestFactory.createRequest(context, currentReport)
    }
    val previousRequest = remember(context, previousReport) {
        ScreenshotRequestFactory.createRequest(context, previousReport)
    }

    // Cập nhật động khi dialog mở qua mốc hết hạn (E01d)
    var isCurrentDynamicallyExpired by remember(currentReport.id, currentReport.isScreenshotExpired, currentReport.screenshotUrl) {
        mutableStateOf(currentReport.isScreenshotExpired || ScreenshotManager.isExpired(currentReport))
    }
    var isPreviousDynamicallyExpired by remember(previousReport?.id, previousReport?.isScreenshotExpired, previousReport?.screenshotUrl) {
        mutableStateOf((previousReport?.isScreenshotExpired ?: false) || ScreenshotManager.isExpired(previousReport))
    }
    LaunchedEffect(currentReport.id, previousReport?.id, currentReport.screenshotUrl, previousReport?.screenshotUrl) {
        while (!isCurrentDynamicallyExpired || (!isPreviousDynamicallyExpired && previousReport != null)) {
            delay(5000L)
            if (!isCurrentDynamicallyExpired && ScreenshotManager.isExpired(currentReport)) {
                isCurrentDynamicallyExpired = true
            }
            if (!isPreviousDynamicallyExpired && previousReport != null && ScreenshotManager.isExpired(previousReport)) {
                isPreviousDynamicallyExpired = true
            }
        }
    }
    LaunchedEffect(currentReport.screenshotUrl, previousReport?.screenshotUrl) {
        while (!isCurrentDynamicallyExpired || (!isPreviousDynamicallyExpired && previousReport != null)) {
            delay(5000L)
            if (!isCurrentDynamicallyExpired && ScreenshotManager.isExpired(currentReport)) {
                isCurrentDynamicallyExpired = true
            }
            if (!isPreviousDynamicallyExpired && previousReport != null && ScreenshotManager.isExpired(previousReport)) {
                isPreviousDynamicallyExpired = true
            }
        }
    }

    // Calculate dHash comparison asynchronously on load.
    // Ưu tiên image_hash đã lưu trong DB (cùng basis với ảnh đã upload), chỉ
    // tải + decode ảnh khi hash trống — đồng bộ với badge trên OwnerAppDetailScreen.
    LaunchedEffect(
        currentReport.screenshotUrl,
        previousReport?.screenshotUrl,
        currentReport.imageHash,
        previousReport?.imageHash,
        currentReport.isScreenshotExpired,
        previousReport?.isScreenshotExpired,
    ) {
        val url1 = currentReport.screenshotUrl
        val url2 = previousReport?.screenshotUrl
        if (!url1.isNullOrBlank() && !url2.isNullOrBlank()) {
            isCalculatingHash = true
            comparisonResult = null
            // Không tải từ URL nếu ảnh đã hết hạn theo chính sách 30 ngày
            val hash1 = currentReport.imageHash?.takeIf { it.isNotBlank() }
                ?: if (!currentReport.isScreenshotExpired) ImageHashHelper.computeDHashFromUrl(context, url1, currentReport) else null
            val hash2 = previousReport?.imageHash?.takeIf { it.isNotBlank() }
                ?: if (previousReport?.isScreenshotExpired != true) ImageHashHelper.computeDHashFromUrl(context, url2, previousReport) else null
            if (hash1 != null && hash2 != null) {
                comparisonResult = ImageHashHelper.compare(hash1, hash2, context)
            }
            isCalculatingHash = false
        }
    }

    ComparisonDialogScaffold(
        title = stringResource(R.string.img_compare_title),
        subtitle = stringResource(R.string.img_compare_tester_format, currentReport.resolvedTesterName),
        currentImage = currentRequest ?: currentReport.screenshotUrl,
        currentLabel = stringResource(R.string.img_today),
        currentDate = currentReport.createdAt?.take(16)?.replace("T", " ").orEmpty(),
        previousImage = previousRequest ?: previousReport?.screenshotUrl,
        previousLabel = stringResource(R.string.img_previous_day),
        previousDate = previousReport?.createdAt?.take(16)?.replace("T", " ")
            ?: stringResource(R.string.img_none_yet),
        comparisonResult = comparisonResult,
        isCalculatingHash = isCalculatingHash,
        currentExpired = isCurrentDynamicallyExpired,
        previousExpired = isPreviousDynamicallyExpired,
        onDismiss = onDismiss,
        actions = {
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
                        Text(stringResource(R.string.img_reject_photo))
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
                        Text(stringResource(R.string.img_approve_btn))
                    }
                }
            } else {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.img_close_comparison))
                }
            }
        }
    )
}

/**
 * Comparison dialog for testers: newly selected photo vs previous day's photo.
 * Hỗ trợ cả Uri ảnh vừa chọn (local) và URL ảnh đã nộp hôm nay.
 */
@Composable
fun TesterImageComparisonDialog(
    currentImage: Any?,
    previousImage: String?,
    onDismiss: () -> Unit,
    currentDate: String? = null,
    previousDate: String? = null,
    comparisonResult: ImageHashHelper.ComparisonResult? = null,
    isCalculatingHash: Boolean = false,
    currentReport: TesterReport? = null,
    previousReport: TesterReport? = null,
) {
    val context = LocalContext.current

    // Cập nhật động khi dialog mở qua mốc hết hạn (E01d)
    var isCurrentDynamicallyExpired by remember(currentReport?.id, currentReport?.isScreenshotExpired, currentImage) {
        mutableStateOf(
            (currentReport?.isScreenshotExpired ?: false) ||
            (currentImage is String && ScreenshotManager.isExpired(currentImage, currentReport?.createdAt, currentReport?.mediaExpired))
        )
    }
    var isPreviousDynamicallyExpired by remember(previousReport?.id, previousReport?.isScreenshotExpired, previousImage) {
        mutableStateOf(
            (previousReport?.isScreenshotExpired ?: false) ||
            (previousImage != null && ScreenshotManager.isExpired(previousImage, previousReport?.createdAt, previousReport?.mediaExpired))
        )
    }
    LaunchedEffect(currentReport?.id, previousReport?.id, currentImage, previousImage) {
        while (!isCurrentDynamicallyExpired || (!isPreviousDynamicallyExpired && previousImage != null)) {
            delay(5000L)
            if (!isCurrentDynamicallyExpired && currentImage is String && ScreenshotManager.isExpired(currentImage, currentReport?.createdAt, currentReport?.mediaExpired)) {
                isCurrentDynamicallyExpired = true
            }
            if (!isPreviousDynamicallyExpired && previousImage != null && ScreenshotManager.isExpired(previousImage, previousReport?.createdAt, previousReport?.mediaExpired)) {
                isPreviousDynamicallyExpired = true
            }
        }
    }

    // Tạo request ở đầu dialog, giữ nguyên local Uri nếu là Uri
    val currentModel = remember(context, currentImage, currentReport) {
        if (currentImage is String) {
            ScreenshotRequestFactory.createRequest(context, currentImage, currentReport) ?: currentImage
        } else {
            currentImage
        }
    }
    val previousModel = remember(context, previousImage, previousReport) {
        ScreenshotRequestFactory.createRequest(context, previousImage, previousReport) ?: previousImage
    }

    ComparisonDialogScaffold(
        title = stringResource(R.string.img_compare_title),
        subtitle = null,
        currentImage = currentModel,
        currentLabel = stringResource(R.string.img_new_photo),
        currentDate = currentDate.orEmpty(),
        previousImage = previousModel,
        previousLabel = stringResource(R.string.img_previous_day),
        previousDate = previousDate ?: stringResource(R.string.img_none_yet),
        comparisonResult = comparisonResult,
        isCalculatingHash = isCalculatingHash,
        currentExpired = isCurrentDynamicallyExpired,
        previousExpired = isPreviousDynamicallyExpired,
        onDismiss = onDismiss,
        actions = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.img_close_comparison))
            }
        }
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComparisonDialogScaffold(
    title: String,
    subtitle: String?,
    currentImage: Any?,
    currentLabel: String,
    currentDate: String,
    previousImage: Any?,
    previousLabel: String,
    previousDate: String,
    comparisonResult: ImageHashHelper.ComparisonResult?,
    isCalculatingHash: Boolean,
    currentExpired: Boolean = false,
    previousExpired: Boolean = false,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
) {
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
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
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
                        actions()
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
                                text = stringResource(R.string.img_analyzing),
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
                                        stringResource(R.string.img_suspect_warning_format, res.similarity)
                                    } else {
                                        stringResource(R.string.img_similarity_ok_format, res.similarity)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else if (previousImage == null || (previousImage is String && previousImage.isBlank())) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.img_first_report_note),
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (currentExpired || previousExpired) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.img_expired_storage_policy),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.img_expired_storage_policy_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.img_hash_unavailable),
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
                        text = { Text(stringResource(R.string.img_tab_side_by_side), fontSize = 13.sp) },
                        icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.img_tab_slider), fontSize = 13.sp) },
                        icon = { Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.img_tab_blink), fontSize = 13.sp) },
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
                        0 -> SideBySideView(
                            currentImage = currentImage,
                            currentLabel = currentLabel,
                            currentDate = currentDate,
                            previousImage = previousImage,
                            previousLabel = previousLabel,
                            previousDate = previousDate,
                            currentExpired = currentExpired,
                            previousExpired = previousExpired,
                        )
                        1 -> SplitSliderView(currentImage, previousImage, sliderRatio, currentExpired, previousExpired) { sliderRatio = it }
                        2 -> BlinkView(currentImage, previousImage, blinkShowCurrent, currentExpired, previousExpired) { blinkShowCurrent = !blinkShowCurrent }
                    }
                }
            }
        }
    }
}

@Composable
private fun SideBySideView(
    currentImage: Any?,
    currentLabel: String,
    currentDate: String,
    previousImage: Any?,
    previousLabel: String,
    previousDate: String,
    currentExpired: Boolean = false,
    previousExpired: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left Column: Current image
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
                        text = currentLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = currentDate,
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
                if (currentExpired) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.img_expired_storage_policy),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    AsyncImage(
                        model = currentImage,
                        contentDescription = stringResource(R.string.img_today_photo_cd),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // Right Column: Previous image
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
                        text = previousLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = previousDate,
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
                if (previousExpired) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.img_expired_storage_policy),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (previousImage != null) {
                    AsyncImage(
                        model = previousImage,
                        contentDescription = stringResource(R.string.img_prev_photo_cd),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.img_no_old_photo),
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
    currentImage: Any?,
    previousImage: Any?,
    ratio: Float,
    currentExpired: Boolean = false,
    previousExpired: Boolean = false,
    onRatioChange: (Float) -> Unit,
) {
    if (currentExpired || previousExpired) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.img_expired_storage_policy), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.img_expired_storage_policy_desc), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (currentImage == null || previousImage == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.img_need_two_photos_slider), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Bottom layer: Old image (full)
            AsyncImage(
                model = previousImage,
                contentDescription = stringResource(R.string.img_old_photo_cd),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Top layer: New image (fades in based on ratio)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ratio
                    }
            ) {
                AsyncImage(
                    model = currentImage,
                    contentDescription = stringResource(R.string.img_new_photo_cd),
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
                    text = stringResource(R.string.img_overlay_ratio_format, (ratio * 100).toInt(), ((1f - ratio) * 100).toInt()),
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
            Text(stringResource(R.string.img_old_photo), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Slider(
                value = ratio,
                onValueChange = onRatioChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(stringResource(R.string.img_new_photo), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BlinkView(
    currentImage: Any?,
    previousImage: Any?,
    showCurrent: Boolean,
    currentExpired: Boolean = false,
    previousExpired: Boolean = false,
    onToggle: () -> Unit,
) {
    if (currentExpired || previousExpired) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.img_expired_storage_policy), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.img_expired_storage_policy_desc), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (previousImage == null || currentImage == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.img_no_old_photo_blink), style = MaterialTheme.typography.bodyMedium)
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
                val model = if (showCurrent) currentImage else previousImage
                AsyncImage(
                    model = model,
                    contentDescription = if (showCurrent) stringResource(R.string.img_today_photo_cd) else stringResource(R.string.img_prev_photo_cd),
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
                        text = if (showCurrent) stringResource(R.string.img_showing_today) else stringResource(R.string.img_showing_previous),
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
            Text(stringResource(R.string.img_tap_to_swap))
        }
    }
}

