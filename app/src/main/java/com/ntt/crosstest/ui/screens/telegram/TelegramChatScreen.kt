package com.ntt.crosstest.ui.screens.telegram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.telegram.TelegramAuthUiState
import com.ntt.crosstest.data.telegram.TelegramChatViewMode
import com.ntt.crosstest.data.telegram.TelegramConnectionStatus
import com.ntt.crosstest.data.telegram.TelegramMessageContentUi
import com.ntt.crosstest.data.telegram.TelegramMessageUi
import com.ntt.crosstest.ui.viewmodels.TelegramChatViewModel
import com.ntt.crosstest.util.CommunityLinks
import com.ntt.crosstest.util.openExternalLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PendingPhotoUpload(
    val localPath: String,
    val width: Int,
    val height: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramChatScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: TelegramChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TelegramChatViewModel(
                    repository = app.telegramChatRepository,
                    mirrorRepository = app.telegramMirrorRepository,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var inputText by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var pendingDeleteMessage by remember { mutableStateOf<TelegramMessageUi?>(null) }
    var fullScreenPhotoPath by remember { mutableStateOf<String?>(null) }
    var pendingPhoto by remember { mutableStateOf<PendingPhotoUpload?>(null) }
    var pendingPhotoCaption by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val prepared = copyPickedImageToCache(context, uri)
                if (prepared != null) {
                    pendingPhotoCaption = ""
                    pendingPhoto = prepared
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }

    LifecycleResumeEffect(Unit) {
        viewModel.onAppResume()
        onPauseOrDispose {
            viewModel.onAppPause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.onScreenLeft()
        }
    }

    // Sync input field when user selects "Edit message"
    LaunchedEffect(uiState.editingMessage?.id) {
        val editing = uiState.editingMessage
        if (editing != null && editing.content is TelegramMessageContentUi.Text) {
            inputText = editing.content.formattedText.text
        }
    }

    // Pagination when scrolling up toward older messages in reverseLayout
    LaunchedEffect(listState, uiState.messages.size, uiState.hasMoreHistory, uiState.isLoadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (uiState.messages.isNotEmpty() &&
                    lastVisible >= uiState.messages.lastIndex - 4 &&
                    uiState.hasMoreHistory &&
                    !uiState.isLoadingMore
                ) {
                    viewModel.loadMoreHistory()
                }
            }
    }

    val isReadOnlyMirror = uiState.viewMode == TelegramChatViewMode.READ_ONLY_MIRROR
    val isAuthFlow = uiState.viewMode == TelegramChatViewMode.AUTH_FLOW
    val isFullReady = uiState.viewMode == TelegramChatViewMode.FULL_TDLIB &&
        uiState.isConfigured &&
        uiState.authState is TelegramAuthUiState.Ready
    val openExternal = { openExternalLink(context, CommunityLinks.TELEGRAM_GROUP_URL) }

    // Mark visible messages as viewed (only in FULL_TDLIB)
    LaunchedEffect(listState, uiState.messages, isFullReady) {
        if (!isFullReady) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }
        }
            .distinctUntilChanged()
            .collect { visibleIds ->
                if (visibleIds.isNotEmpty()) {
                    viewModel.markMessagesViewed(visibleIds)
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.isSearchActive) {
                                viewModel.setSearchActive(false)
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    if (isFullReady && uiState.isSearchActive) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.searchMessages(it) },
                            placeholder = { Text(stringResource(R.string.telegram_search_hint)) },
                            singleLine = true,
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.searchMessages("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = null)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val chatInfo = uiState.chatInfo
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (chatInfo != null) {
                                TelegramAvatar(
                                    name = chatInfo.title,
                                    localPath = chatInfo.avatarLocalPath,
                                    remoteUrl = chatInfo.avatarRemoteUrl,
                                    size = 36.dp,
                                    seedId = chatInfo.chatId,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Column {
                                Text(
                                    text = chatInfo?.title ?: stringResource(R.string.telegram_chat_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isReadOnlyMirror) {
                                    Text(
                                        text = stringResource(R.string.telegram_read_only_subtitle),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (isFullReady && !uiState.isSearchActive) {
                        IconButton(onClick = { viewModel.setSearchActive(true) }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.telegram_menu_search),
                            )
                        }
                    } else if (isReadOnlyMirror) {
                        IconButton(onClick = { viewModel.refreshMirrorFeed() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.telegram_refresh_cd),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (isFullReady) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.telegram_menu_search)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.setSearchActive(true)
                                    },
                                )
                            }
                            if (isReadOnlyMirror) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.telegram_readonly_login_btn)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.requestTelegramLogin()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.telegram_refresh_cd)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.refreshMirrorFeed()
                                    },
                                )
                            } else if (isAuthFlow) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.telegram_back_to_preview_btn)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.backToReadOnlyMirror()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.telegram_menu_open_external)) },
                                onClick = {
                                    menuExpanded = false
                                    openExternal()
                                },
                            )
                            if (isFullReady) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.telegram_menu_logout),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showLogoutConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isFullReady && !uiState.isSearchActive) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    TelegramReplyEditBar(
                        replyingTo = uiState.replyingTo,
                        editingMessage = uiState.editingMessage,
                        onDismiss = {
                            if (uiState.editingMessage != null) {
                                inputText = ""
                            }
                            viewModel.setReplyingTo(null)
                            viewModel.setEditingMessage(null)
                        },
                    )
                    TelegramChatInputBar(
                        text = inputText,
                        onTextChange = {
                            inputText = it
                            viewModel.onTypingChanged(it)
                        },
                        onSend = {
                            val toSend = inputText
                            inputText = ""
                            viewModel.sendTextMessage(toSend)
                        },
                        onAttachPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        isEditing = uiState.editingMessage != null,
                    )
                }
            } else if (isReadOnlyMirror) {
                TelegramReadOnlyLoginBar(
                    onLoginClick = viewModel::requestTelegramLogin,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedVisibility(
                visible = isFullReady && uiState.connectionStatus != TelegramConnectionStatus.READY
            ) {
                val statusText = when (uiState.connectionStatus) {
                    TelegramConnectionStatus.WAITING_FOR_NETWORK -> stringResource(R.string.telegram_status_waiting_network)
                    TelegramConnectionStatus.UPDATING -> stringResource(R.string.telegram_status_updating)
                    TelegramConnectionStatus.CONNECTING_TO_PROXY -> stringResource(R.string.telegram_status_connecting_proxy)
                    TelegramConnectionStatus.CONNECTING,
                    TelegramConnectionStatus.READY -> stringResource(R.string.telegram_status_connecting)
                }
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            if ((isFullReady || isReadOnlyMirror) && !uiState.isSearchActive && uiState.chatInfo?.pinnedMessage != null) {
                val pinned = uiState.chatInfo!!.pinnedMessage!!
                TelegramPinnedMessageBar(
                    pinned = pinned,
                    onClick = {
                        val idx = uiState.messages.indexOfFirst { it.id == pinned.messageId }
                        if (idx >= 0) {
                            coroutineScope.launch { listState.animateScrollToItem(idx) }
                        }
                    },
                )
            }

            if (!uiState.errorBanner.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.errorBanner!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.clearErrorBanner() },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            when {
                isAuthFlow && !uiState.isConfigured -> {
                    MissingTelegramConfigContent(
                        onOpenExternal = openExternal,
                        onBackToPreview = viewModel::backToReadOnlyMirror,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                isAuthFlow && uiState.authState !is TelegramAuthUiState.Ready -> {
                    TelegramAuthContent(
                        authState = uiState.authState,
                        onSubmitPhone = viewModel::submitPhoneNumber,
                        onChangePhone = viewModel::changePhoneNumber,
                        onSubmitOtp = viewModel::submitOtpCode,
                        onResendOtp = viewModel::resendOtpCode,
                        onSubmitPassword = viewModel::submitPassword,
                        onRequestRecovery = viewModel::requestPasswordRecovery,
                        onBackToPassword = viewModel::backToPasswordEntry,
                        onSubmitRecoveryCode = viewModel::submitPasswordRecoveryCode,
                        onRegisterUser = viewModel::registerUser,
                        onRetry = viewModel::retryInitialize,
                        onOpenExternal = openExternal,
                        onBackToPreview = viewModel::backToReadOnlyMirror,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                !isReadOnlyMirror && !uiState.isConfigured -> {
                    MissingTelegramConfigContent(
                        onOpenExternal = openExternal,
                        onBackToPreview = viewModel::backToReadOnlyMirror,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                !isReadOnlyMirror && uiState.authState !is TelegramAuthUiState.Ready -> {
                    TelegramAuthContent(
                        authState = uiState.authState,
                        onSubmitPhone = viewModel::submitPhoneNumber,
                        onChangePhone = viewModel::changePhoneNumber,
                        onSubmitOtp = viewModel::submitOtpCode,
                        onResendOtp = viewModel::resendOtpCode,
                        onSubmitPassword = viewModel::submitPassword,
                        onRequestRecovery = viewModel::requestPasswordRecovery,
                        onBackToPassword = viewModel::backToPasswordEntry,
                        onSubmitRecoveryCode = viewModel::submitPasswordRecoveryCode,
                        onRegisterUser = viewModel::registerUser,
                        onRetry = viewModel::retryInitialize,
                        onOpenExternal = openExternal,
                        onBackToPreview = viewModel::backToReadOnlyMirror,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                isFullReady && uiState.isSearchActive -> {
                    if (uiState.isSearching) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (uiState.searchResults.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.telegram_search_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            itemsIndexed(
                                items = uiState.searchResults,
                                key = { _, msg -> msg.id },
                            ) { _, msg ->
                                TelegramMessageBubble(
                                    message = msg,
                                    showSenderHeader = true,
                                    onReply = {
                                        viewModel.setSearchActive(false)
                                        viewModel.setReplyingTo(it)
                                    },
                                    onCopy = { copyToClipboard(context, it) },
                                    onEdit = {
                                        viewModel.setSearchActive(false)
                                        viewModel.setEditingMessage(it)
                                    },
                                    onDelete = { pendingDeleteMessage = it },
                                    onToggleReaction = viewModel::toggleReaction,
                                    onPhotoClick = { fullScreenPhotoPath = it },
                                    onDownloadFile = viewModel::downloadFile,
                                )
                            }
                        }
                    }
                }

                uiState.isLoadingHistory && uiState.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.messages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.telegram_empty_messages),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isReadOnlyMirror) {
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { viewModel.refreshMirrorFeed() }) {
                                    Text(stringResource(R.string.telegram_retry_btn))
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        itemsIndexed(
                            items = uiState.messages,
                            key = { _, msg -> msg.id },
                        ) { index, msg ->
                            val aboveMsg = uiState.messages.getOrNull(index + 1)
                            val showHeader = aboveMsg == null ||
                                aboveMsg.senderId != msg.senderId ||
                                aboveMsg.content is TelegramMessageContentUi.SystemMessage
                            TelegramMessageBubble(
                                message = msg,
                                showSenderHeader = showHeader,
                                onReply = viewModel::setReplyingTo,
                                onCopy = { copyToClipboard(context, it) },
                                onEdit = viewModel::setEditingMessage,
                                onDelete = { pendingDeleteMessage = it },
                                onToggleReaction = viewModel::toggleReaction,
                                onPhotoClick = { fullScreenPhotoPath = it },
                                onDownloadFile = viewModel::downloadFile,
                                isReadOnlyMode = isReadOnlyMirror,
                                onRequestTelegramLogin = viewModel::requestTelegramLogin,
                            )
                        }

                        if (uiState.isLoadingMore) {
                            item(key = "loading_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.telegram_logout_confirm_title)) },
            text = { Text(stringResource(R.string.telegram_logout_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logOut()
                    }
                ) {
                    Text(
                        stringResource(R.string.telegram_logout_confirm_btn),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.tester_bonus_btn_cancel))
                }
            },
        )
    }

    val toDelete = pendingDeleteMessage
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteMessage = null },
            title = { Text(stringResource(R.string.telegram_delete_confirm_title)) },
            text = { Text(stringResource(R.string.telegram_delete_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteMessage = null
                        viewModel.deleteMessage(toDelete.id, revoke = true)
                    }
                ) {
                    Text(
                        stringResource(R.string.telegram_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMessage = null }) {
                    Text(stringResource(R.string.tester_bonus_btn_cancel))
                }
            },
        )
    }

    val photoToSend = pendingPhoto
    if (photoToSend != null) {
        AlertDialog(
            onDismissRequest = { pendingPhoto = null },
            title = { Text(stringResource(R.string.telegram_media_photo_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = File(photoToSend.localPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    OutlinedTextField(
                        value = pendingPhotoCaption,
                        onValueChange = { pendingPhotoCaption = it },
                        placeholder = { Text(stringResource(R.string.telegram_message_input_hint)) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingPhoto = null
                        viewModel.sendPhotoMessage(
                            localPath = photoToSend.localPath,
                            caption = pendingPhotoCaption,
                            width = photoToSend.width,
                            height = photoToSend.height,
                        )
                    }
                ) {
                    Text(stringResource(R.string.telegram_send_cd))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPhoto = null }) {
                    Text(stringResource(R.string.tester_bonus_btn_cancel))
                }
            },
        )
    }

    val viewerPath = fullScreenPhotoPath
    if (viewerPath != null) {
        TelegramImageViewerDialog(
            localPath = viewerPath,
            onDismiss = { fullScreenPhotoPath = null },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Telegram", text))
    Toast.makeText(context, R.string.telegram_copied_toast, Toast.LENGTH_SHORT).show()
}

private suspend fun copyPickedImageToCache(context: Context, uri: Uri): PendingPhotoUpload? =
    withContext(Dispatchers.IO) {
        runCatching {
            val destFile = File(context.cacheDir, "tg_upload_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, bounds)
            PendingPhotoUpload(
                localPath = destFile.absolutePath,
                width = bounds.outWidth.coerceAtLeast(0),
                height = bounds.outHeight.coerceAtLeast(0),
            )
        }.getOrNull()
    }
