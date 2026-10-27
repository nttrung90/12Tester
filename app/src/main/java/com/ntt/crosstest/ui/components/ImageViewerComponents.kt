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

/**
 * Fullscreen Zoomable Image Lightbox with Pinch-to-zoom, Pan, and Double-tap 2.5x zoom.
 */
@Composable
fun FullscreenImageViewer(
    imageUrl: String?,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.img_screenshot_title),
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
                                Text(stringResource(R.string.img_load_failed), color = Color.White)
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

    // Calculate dHash comparison asynchronously on load.
    // Ưu tiên image_hash đã lưu trong DB (cùng basis với ảnh đã upload), chỉ
    // tải + decode ảnh khi hash trống — đồng bộ với badge trên OwnerAppDetailScreen.
    LaunchedEffect(
        currentReport.screenshotUrl,
        previousReport?.screenshotUrl,
        currentReport.imageHash,
        previousReport?.imageHash,
    ) {
        val url1 = currentReport.screenshotUrl
        val url2 = previousReport?.screenshotUrl
        if (!url1.isNullOrBlank() && !url2.isNullOrBlank()) {
            isCalculatingHash = true
            comparisonResult = null
            val hash1 = currentReport.imageHash?.takeIf { it.isNotBlank() }
                ?: ImageHashHelper.computeDHashFromUrl(context, url1)
            val hash2 = previousReport.imageHash?.takeIf { it.isNotBlank() }
                ?: ImageHashHelper.computeDHashFromUrl(context, url2)
            if (hash1 != null && hash2 != null) {
                comparisonResult = ImageHashHelper.compare(hash1, hash2, context)
            }
            isCalculatingHash = false
        }
    }

    ComparisonDialogScaffold(
        title = stringResource(R.string.img_compare_title),
        subtitle = stringResource(R.string.img_compare_tester_format, currentReport.resolvedTesterName),
        currentImage = currentReport.screenshotUrl,
        currentLabel = stringResource(R.string.img_today),
        currentDate = currentReport.createdAt?.take(16)?.replace("T", " ").orEmpty(),
        previousImage = previousReport?.screenshotUrl,
        previousLabel = stringResource(R.string.img_previous_day),
        previousDate = previousReport?.createdAt?.take(16)?.replace("T", " ")
            ?: stringResource(R.string.img_none_yet),
        comparisonResult = comparisonResult,
        isCalculatingHash = isCalculatingHash,
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
) {
    ComparisonDialogScaffold(
        title = stringResource(R.string.img_compare_title),
        subtitle = null,
        currentImage = currentImage,
        currentLabel = stringResource(R.string.img_new_photo),
        currentDate = currentDate.orEmpty(),
        previousImage = previousImage,
        previousLabel = stringResource(R.string.img_previous_day),
        previousDate = previousDate ?: stringResource(R.string.img_none_yet),
        comparisonResult = comparisonResult,
        isCalculatingHash = isCalculatingHash,
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
    previousImage: String?,
    previousLabel: String,
    previousDate: String,
    comparisonResult: ImageHashHelper.ComparisonResult?,
    isCalculatingHash: Boolean,
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
                        } else if (previousImage.isNullOrBlank()) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.img_first_report_note),
                                style = MaterialTheme.typography.bodySmall
                            )
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
                        )
                        1 -> SplitSliderView(currentImage, previousImage, sliderRatio) { sliderRatio = it }
                        2 -> BlinkView(currentImage, previousImage, blinkShowCurrent) { blinkShowCurrent = !blinkShowCurrent }
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
    previousImage: String?,
    previousLabel: String,
    previousDate: String,
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
                AsyncImage(
                    model = currentImage,
                    contentDescription = stringResource(R.string.img_today_photo_cd),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
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
                if (previousImage != null) {
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
    previousImage: String?,
    ratio: Float,
    onRatioChange: (Float) -> Unit,
) {
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
    previousImage: String?,
    showCurrent: Boolean,
    onToggle: () -> Unit,
) {
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

