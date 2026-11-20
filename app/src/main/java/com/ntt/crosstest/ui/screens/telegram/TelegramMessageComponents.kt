package com.ntt.crosstest.ui.screens.telegram

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ntt.crosstest.R
import com.ntt.crosstest.data.telegram.PinnedMessageUi
import com.ntt.crosstest.data.telegram.TelegramEntityType
import com.ntt.crosstest.data.telegram.TelegramFormattedText
import com.ntt.crosstest.data.telegram.TelegramMessageContentUi
import com.ntt.crosstest.data.telegram.TelegramMessageUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val QUICK_REACTIONS = listOf("👍", "❤️", "🔥", "😂", "👏", "😮")

fun buildTelegramAnnotatedString(
    formatted: TelegramFormattedText,
    linkColor: Color,
    codeBgColor: Color,
    onOpenUrl: ((String) -> Unit)? = null,
): AnnotatedString {
    val raw = formatted.text
    if (raw.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        append(raw)
        for (entity in formatted.entities) {
            val start = entity.offset.coerceIn(0, raw.length)
            val end = (entity.offset + entity.length).coerceIn(start, raw.length)
            if (start >= end) continue

            when (val type = entity.type) {
                TelegramEntityType.Bold -> addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    start,
                    end,
                )
                TelegramEntityType.Italic -> addStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                    start,
                    end,
                )
                TelegramEntityType.Underline -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline),
                    start,
                    end,
                )
                TelegramEntityType.Strikethrough -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    start,
                    end,
                )
                TelegramEntityType.Code, TelegramEntityType.Pre -> addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBgColor,
                    ),
                    start,
                    end,
                )
                TelegramEntityType.Mention,
                TelegramEntityType.BotCommand,
                TelegramEntityType.Hashtag -> addStyle(
                    SpanStyle(color = linkColor),
                    start,
                    end,
                )
                TelegramEntityType.Url -> {
                    val snippet = raw.substring(start, end)
                    val targetUrl = normalizeClickableUrl(snippet)
                    addLink(
                        url = LinkAnnotation.Url(
                            url = targetUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                )
                            ),
                            linkInteractionListener = onOpenUrl?.let { opener ->
                                { opener(targetUrl) }
                            },
                        ),
                        start = start,
                        end = end,
                    )
                }
                is TelegramEntityType.TextUrl -> {
                    val targetUrl = normalizeClickableUrl(type.url)
                    addLink(
                        url = LinkAnnotation.Url(
                            url = targetUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                )
                            ),
                            linkInteractionListener = onOpenUrl?.let { opener ->
                                { opener(targetUrl) }
                            },
                        ),
                        start = start,
                        end = end,
                    )
                }
                TelegramEntityType.Spoiler -> addStyle(
                    SpanStyle(background = codeBgColor),
                    start,
                    end,
                )
            }
        }
    }
}

private fun normalizeClickableUrl(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("tg://", ignoreCase = true)
    ) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

@Composable
fun TelegramAvatar(
    name: String,
    localPath: String?,
    size: Dp,
    seedId: Long = 0L,
    remoteUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val fallbackColor = remember(seedId, name) {
        val palette = listOf(
            Color(0xFF1E88E5),
            Color(0xFF43A047),
            Color(0xFFE53935),
            Color(0xFF8E24AA),
            Color(0xFFFB8C00),
            Color(0xFF00897B),
        )
        val idx = abs((if (seedId != 0L) seedId else name.hashCode().toLong()) % palette.size).toInt()
        palette[idx]
    }
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "T"

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fallbackColor),
        contentAlignment = Alignment.Center,
    ) {
        if (!localPath.isNullOrBlank() && File(localPath).exists()) {
            AsyncImage(
                model = File(localPath),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!remoteUrl.isNullOrBlank()) {
            AsyncImage(
                model = remoteUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TelegramMessageBubble(
    message: TelegramMessageUi,
    showSenderHeader: Boolean,
    onReply: (TelegramMessageUi) -> Unit,
    onCopy: (String) -> Unit,
    onEdit: (TelegramMessageUi) -> Unit,
    onDelete: (TelegramMessageUi) -> Unit,
    onToggleReaction: (Long, String) -> Unit,
    onPhotoClick: (String) -> Unit,
    onDownloadFile: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isReadOnlyMode: Boolean = false,
    onRequestTelegramLogin: () -> Unit = {},
) {
    if (message.content is TelegramMessageContentUi.SystemMessage) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ) {
                Text(
                    text = "${message.senderName}: ${message.content.text}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        return
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }
    }

    val bubbleColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    val copyableText = remember(message.content) {
        when (val c = message.content) {
            is TelegramMessageContentUi.Text -> c.formattedText.text
            is TelegramMessageContentUi.Photo -> c.formattedCaption.text
            is TelegramMessageContentUi.Document -> c.formattedCaption.text
            is TelegramMessageContentUi.MediaPlaceholder -> c.formattedCaption.text
            else -> ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!message.isOutgoing) {
            if (showSenderHeader) {
                TelegramAvatar(
                    name = message.senderName,
                    localPath = message.senderAvatarPath,
                    size = 32.dp,
                    seedId = message.senderId,
                )
            } else {
                Spacer(Modifier.width(32.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isOutgoing) 16.dp else 4.dp,
                    bottomEnd = if (message.isOutgoing) 4.dp else 16.dp,
                ),
                color = bubbleColor,
                modifier = Modifier
                    .widthIn(min = 84.dp, max = 290.dp)
                    .combinedClickable(
                        onClick = { menuExpanded = true },
                        onLongClick = { menuExpanded = true },
                    ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!message.isOutgoing && showSenderHeader) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                    }

                    if (message.replyPreview != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    text = message.replyPreview.senderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = message.replyPreview.previewText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    when (val content = message.content) {
                        is TelegramMessageContentUi.Text -> {
                            val annotated = remember(content.formattedText, linkColor, codeBg) {
                                buildTelegramAnnotatedString(content.formattedText, linkColor, codeBg, openUrl)
                            }
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            )
                        }

                        is TelegramMessageContentUi.Photo -> {
                            val aspect = if (content.width > 0 && content.height > 0) {
                                (content.width.toFloat() / content.height.toFloat()).coerceIn(0.6f, 1.8f)
                            } else {
                                1.33f
                            }
                            val miniBitmap = remember(content.minithumbnailBytes) {
                                content.minithumbnailBytes?.let { bytes ->
                                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(aspect)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        if (content.isReadOnlyPreview || isReadOnlyMode) {
                                            onRequestTelegramLogin()
                                        } else {
                                            val p = content.localPath
                                            if (!p.isNullOrBlank() && File(p).exists()) {
                                                onPhotoClick(p)
                                            } else if (content.fileId > 0) {
                                                onDownloadFile(content.fileId)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (content.isReadOnlyPreview) {
                                    if (miniBitmap != null) {
                                        val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            Modifier.blur(12.dp)
                                        } else {
                                            Modifier
                                        }
                                        Image(
                                            bitmap = miniBitmap,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .then(blurMod),
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Color.Black.copy(
                                                    alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.35f else 0.58f
                                                )
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Lock,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(26.dp),
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = stringResource(R.string.telegram_readonly_photo_overlay),
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                } else {
                                    if (miniBitmap != null) {
                                        Image(
                                            bitmap = miniBitmap,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    if (!content.localPath.isNullOrBlank() && File(content.localPath).exists()) {
                                        AsyncImage(
                                            model = File(content.localPath),
                                            contentDescription = stringResource(R.string.telegram_media_photo_label),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else if (content.isDownloading) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                    } else {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.5f),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Download,
                                                contentDescription = stringResource(R.string.telegram_download_tap),
                                                tint = Color.White,
                                                modifier = Modifier.padding(8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (content.formattedCaption.text.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                val annotated = remember(content.formattedCaption, linkColor, codeBg) {
                                    buildTelegramAnnotatedString(content.formattedCaption, linkColor, codeBg, openUrl)
                                }
                                Text(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                                )
                            }
                        }

                        is TelegramMessageContentUi.Sticker -> {
                            if (content.isWebp && !content.localPath.isNullOrBlank() && File(content.localPath).exists()) {
                                AsyncImage(
                                    model = File(content.localPath),
                                    contentDescription = stringResource(R.string.telegram_media_sticker_label),
                                    modifier = Modifier.size(132.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(
                                    text = content.emoji.ifBlank { "🎨" },
                                    fontSize = 48.sp,
                                )
                            }
                        }

                        is TelegramMessageContentUi.Document -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (content.localPath == null && content.fileId > 0) {
                                            onDownloadFile(content.fileId)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = content.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val statusText = when {
                                        content.isDownloading -> stringResource(R.string.telegram_downloading)
                                        content.localPath != null -> formatBytes(content.sizeBytes)
                                        else -> "${formatBytes(content.sizeBytes)} · ${stringResource(R.string.telegram_download_tap)}"
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            if (content.formattedCaption.text.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = buildTelegramAnnotatedString(content.formattedCaption, linkColor, codeBg, openUrl),
                                    style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                                )
                            }
                        }

                        is TelegramMessageContentUi.MediaPlaceholder -> {
                            Text(
                                text = "[${content.typeLabel}]",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (content.formattedCaption.text.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = buildTelegramAnnotatedString(content.formattedCaption, linkColor, codeBg, openUrl),
                                    style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                                )
                            }
                        }

                        is TelegramMessageContentUi.Unsupported -> {
                            Text(
                                text = "[${content.typeName}]",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.7f),
                            )
                        }

                        is TelegramMessageContentUi.SystemMessage -> Unit
                    }

                    if (message.reactions.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            message.reactions.forEach { reaction ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (reaction.isChosen) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                    },
                                    border = if (reaction.isChosen) {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.clickable {
                                        onToggleReaction(message.id, reaction.emoji)
                                    },
                                ) {
                                    Text(
                                        text = "${reaction.emoji} ${reaction.count}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (message.isEdited) {
                            Text(
                                text = stringResource(R.string.telegram_edited_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.65f),
                            )
                        }
                        if (message.isSending) {
                            Text(
                                text = stringResource(R.string.telegram_sending_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.65f),
                            )
                        } else if (message.sendFailed) {
                            Text(
                                text = stringResource(R.string.telegram_send_failed_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            text = formatMessageTime(message.dateSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.65f),
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (!isReadOnlyMode) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        QUICK_REACTIONS.forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 20.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        menuExpanded = false
                                        onToggleReaction(message.id, emoji)
                                    }
                                    .padding(4.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.telegram_action_reply)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        if (isReadOnlyMode) {
                            onRequestTelegramLogin()
                        } else {
                            onReply(message)
                        }
                    },
                )
                if (copyableText.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.telegram_action_copy)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCopy(copyableText)
                        },
                    )
                }
                if (!isReadOnlyMode && message.isOutgoing && message.content is TelegramMessageContentUi.Text) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.telegram_action_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit(message)
                        },
                    )
                }
                if (!isReadOnlyMode && message.isOutgoing) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.telegram_action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete(message)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun TelegramPinnedMessageBar(
    pinned: PinnedMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.telegram_pinned_message_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = pinned.previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun TelegramReplyEditBar(
    replyingTo: TelegramMessageUi?,
    editingMessage: TelegramMessageUi?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = editingMessage ?: replyingTo ?: return
    val header = if (editingMessage != null) {
        stringResource(R.string.telegram_editing_label)
    } else {
        stringResource(R.string.telegram_replying_to_format, active.senderName)
    }
    val preview = when (val c = active.content) {
        is TelegramMessageContentUi.Text -> c.formattedText.text
        is TelegramMessageContentUi.Photo -> c.formattedCaption.text.ifBlank {
            stringResource(R.string.telegram_media_photo_label)
        }
        is TelegramMessageContentUi.Document -> c.fileName
        is TelegramMessageContentUi.Sticker -> "${c.emoji} ${stringResource(R.string.telegram_media_sticker_label)}"
        else -> "..."
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (editingMessage != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun TelegramChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachPhoto: () -> Unit,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!isEditing) {
                IconButton(onClick = onAttachPhoto) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = stringResource(R.string.telegram_attach_photo_cd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.telegram_message_input_hint)) },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.telegram_send_cd),
                    tint = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}

@Composable
fun TelegramReadOnlyLoginBar(
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.telegram_readonly_login_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onLoginClick,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.telegram_readonly_login_btn))
            }
        }
    }
}

@Composable
fun TelegramImageViewerDialog(
    localPath: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(localPath),
                contentDescription = stringResource(R.string.telegram_media_photo_label),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}

private fun formatMessageTime(dateSeconds: Int): String {
    if (dateSeconds <= 0) return ""
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(dateSeconds * 1000L))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
