package com.ntt.crosstest.data.telegram

import com.ntt.crosstest.R
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SupabaseHolder
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Locale

@Serializable
data class TelegramMirrorRpcParams(
    @SerialName("p_chat_key") val chatKey: String = TelegramMirrorRepository.DEFAULT_CHAT_KEY,
    @SerialName("p_before_message_id") val beforeMessageId: Long? = null,
    @SerialName("p_after_message_id") val afterMessageId: Long? = null,
    @SerialName("p_since_edited_at") val sinceEditedAt: String? = null,
    @SerialName("p_limit") val limit: Int = TelegramMirrorRepository.DEFAULT_PAGE_LIMIT,
)

@Serializable
data class TelegramMirrorEntityDto(
    val offset: Int = 0,
    val length: Int = 0,
    val type: String = "",
    val url: String? = null,
)

@Serializable
data class TelegramMirrorChatDto(
    @SerialName("chat_key") val chatKey: String? = null,
    @SerialName("telegram_chat_id") val telegramChatId: Long = 0L,
    val title: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("pinned_message_id") val pinnedMessageId: Long? = null,
    @SerialName("pinned_preview") val pinnedPreview: String? = null,
    @SerialName("pinned_sender_name") val pinnedSenderName: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class TelegramMirrorMessageDto(
    @SerialName("telegram_message_id") val telegramMessageId: Long,
    @SerialName("sender_id") val senderId: Long = 0L,
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("sender_is_bot") val senderIsBot: Boolean = false,
    @SerialName("content_type") val contentType: String = "text",
    @SerialName("text_content") val textContent: String = "",
    @SerialName("entities_json") val entitiesJson: List<TelegramMirrorEntityDto> = emptyList(),
    @SerialName("media_width") val mediaWidth: Int = 0,
    @SerialName("media_height") val mediaHeight: Int = 0,
    @SerialName("media_thumb_base64") val mediaThumbBase64: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long = 0L,
    @SerialName("reply_to_message_id") val replyToMessageId: Long? = null,
    @SerialName("reply_sender_name") val replySenderName: String? = null,
    @SerialName("reply_preview_text") val replyPreviewText: String? = null,
    @SerialName("sent_at") val sentAt: String = "",
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
)

@Serializable
data class TelegramMirrorFeedDto(
    val chat: TelegramMirrorChatDto? = null,
    val messages: List<TelegramMirrorMessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("server_time") val serverTime: String? = null,
)

data class TelegramMirrorUiState(
    val chatInfo: TelegramChatInfo? = null,
    val messages: List<TelegramMessageUi> = emptyList(),
    val hasMoreHistory: Boolean = false,
    val isLoadingInitial: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val latestMessageId: Long = 0L,
    val serverTime: String? = null,
)

interface TelegramMirrorFeedProvider {
    suspend fun fetchFeed(
        chatKey: String = TelegramMirrorRepository.DEFAULT_CHAT_KEY,
        beforeMessageId: Long? = null,
        afterMessageId: Long? = null,
        sinceEditedAt: String? = null,
        limit: Int = TelegramMirrorRepository.DEFAULT_PAGE_LIMIT,
    ): String
}

class SupabaseTelegramMirrorFeedProvider(
    private val clientProvider: () -> SupabaseClient? = {
        if (SupabaseHolder.isInitialized()) SupabaseHolder.get() else null
    },
) : TelegramMirrorFeedProvider {
    override suspend fun fetchFeed(
        chatKey: String,
        beforeMessageId: Long?,
        afterMessageId: Long?,
        sinceEditedAt: String?,
        limit: Int,
    ): String {
        val client = clientProvider()
            ?: throw IllegalStateException("Supabase is not initialized")
        val params = TelegramMirrorRpcParams(
            chatKey = chatKey,
            beforeMessageId = beforeMessageId,
            afterMessageId = afterMessageId,
            sinceEditedAt = sinceEditedAt,
            limit = limit,
        )
        return client.postgrest.rpc(RPC_NAME, params).data
    }

    companion object {
        const val RPC_NAME = "get_telegram_mirror_feed"
    }
}

class TelegramMirrorRepository(
    private val feedProvider: TelegramMirrorFeedProvider = SupabaseTelegramMirrorFeedProvider(),
    private val localizer: Localizer? = null,
    private val chatKey: String = DEFAULT_CHAT_KEY,
    private val pageLimit: Int = DEFAULT_PAGE_LIMIT,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val _uiState = MutableStateFlow(TelegramMirrorUiState())
    val uiState: StateFlow<TelegramMirrorUiState> = _uiState.asStateFlow()

    private val messageMap = LinkedHashMap<Long, TelegramMessageUi>()

    suspend fun loadInitialFeed(): Result<TelegramMirrorFeedDto> {
        _uiState.update { it.copy(isLoadingInitial = true, errorMessage = null) }
        return runCatching {
            val raw = feedProvider.fetchFeed(
                chatKey = chatKey,
                beforeMessageId = null,
                afterMessageId = null,
                sinceEditedAt = null,
                limit = pageLimit,
            )
            parseFeed(raw)
        }.onSuccess { dto ->
            val mappedChat = dto.chat?.let { mapChat(it) }
            val pinnedId = mappedChat?.pinnedMessage?.messageId
            val chatId = mappedChat?.chatId ?: 0L
            val maxSeenId: Long
            val sortedMessages: List<TelegramMessageUi>
            synchronized(messageMap) {
                messageMap.clear()
                var maxId = 0L
                for (msgDto in dto.messages) {
                    if (msgDto.telegramMessageId > maxId) {
                        maxId = msgDto.telegramMessageId
                    }
                    if (!msgDto.isDeleted) {
                        messageMap[msgDto.telegramMessageId] = mapMessage(msgDto, chatId, pinnedId)
                    }
                }
                maxSeenId = maxId
                sortedMessages = messageMap.values.sortedByDescending { it.id }
            }
            _uiState.update {
                it.copy(
                    chatInfo = mappedChat ?: it.chatInfo,
                    messages = sortedMessages,
                    hasMoreHistory = dto.hasMore,
                    isLoadingInitial = false,
                    errorMessage = null,
                    latestMessageId = maxSeenId,
                    serverTime = dto.serverTime ?: it.serverTime,
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    isLoadingInitial = false,
                    errorMessage = localizedLoadError(err),
                )
            }
        }
    }

    suspend fun loadOlderMessages(beforeMessageId: Long? = null): Result<TelegramMirrorFeedDto> {
        val cursorId = beforeMessageId ?: synchronized(messageMap) { messageMap.keys.minOrNull() }
        if (cursorId == null || cursorId <= 0L) {
            return Result.success(TelegramMirrorFeedDto(hasMore = false))
        }
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        return runCatching {
            val raw = feedProvider.fetchFeed(
                chatKey = chatKey,
                beforeMessageId = cursorId,
                afterMessageId = null,
                sinceEditedAt = null,
                limit = pageLimit,
            )
            parseFeed(raw)
        }.onSuccess { dto ->
            val mappedChat = dto.chat?.let { mapChat(it) } ?: _uiState.value.chatInfo
            val pinnedId = mappedChat?.pinnedMessage?.messageId
            val chatId = mappedChat?.chatId ?: 0L
            val sortedMessages: List<TelegramMessageUi>
            var addedCount = 0
            synchronized(messageMap) {
                for (msgDto in dto.messages) {
                    if (msgDto.isDeleted) {
                        messageMap.remove(msgDto.telegramMessageId)
                    } else {
                        val prev = messageMap.put(msgDto.telegramMessageId, mapMessage(msgDto, chatId, pinnedId))
                        if (prev == null) addedCount++
                    }
                }
                sortedMessages = messageMap.values.sortedByDescending { it.id }
            }
            _uiState.update {
                it.copy(
                    chatInfo = mappedChat,
                    messages = sortedMessages,
                    hasMoreHistory = dto.hasMore && dto.messages.isNotEmpty() && addedCount > 0,
                    isLoadingMore = false,
                    errorMessage = null,
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    isLoadingMore = false,
                    errorMessage = localizedLoadError(err),
                )
            }
        }
    }

    suspend fun pollUpdates(
        afterMessageId: Long? = null,
        sinceEditedAt: String? = null,
    ): Result<TelegramMirrorFeedDto> {
        val currentState = _uiState.value
        val effectiveAfter = afterMessageId ?: currentState.latestMessageId.takeIf { it > 0L }
        val effectiveSince = sinceEditedAt ?: currentState.serverTime
        return runCatching {
            val raw = feedProvider.fetchFeed(
                chatKey = chatKey,
                beforeMessageId = null,
                afterMessageId = effectiveAfter,
                sinceEditedAt = effectiveSince,
                limit = pageLimit,
            )
            parseFeed(raw)
        }.onSuccess { dto ->
            val mappedChat = dto.chat?.let { mapChat(it) } ?: _uiState.value.chatInfo
            val pinnedId = mappedChat?.pinnedMessage?.messageId
            val chatId = mappedChat?.chatId ?: 0L
            var maxSeenId = _uiState.value.latestMessageId
            val sortedMessages: List<TelegramMessageUi>
            synchronized(messageMap) {
                for (msgDto in dto.messages) {
                    if (msgDto.telegramMessageId > maxSeenId) {
                        maxSeenId = msgDto.telegramMessageId
                    }
                    if (msgDto.isDeleted) {
                        messageMap.remove(msgDto.telegramMessageId)
                    } else {
                        messageMap[msgDto.telegramMessageId] = mapMessage(msgDto, chatId, pinnedId)
                    }
                }
                sortedMessages = messageMap.values.sortedByDescending { it.id }
            }
            _uiState.update {
                it.copy(
                    chatInfo = mappedChat,
                    messages = sortedMessages,
                    latestMessageId = maxSeenId,
                    serverTime = dto.serverTime ?: it.serverTime,
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    internal fun parseFeed(rawJson: String): TelegramMirrorFeedDto =
        json.decodeFromString(TelegramMirrorFeedDto.serializer(), rawJson)

    internal fun mapChat(dto: TelegramMirrorChatDto): TelegramChatInfo {
        val pinned = if (dto.pinnedMessageId != null && dto.pinnedMessageId > 0L) {
            PinnedMessageUi(
                messageId = dto.pinnedMessageId,
                previewText = dto.pinnedPreview.orEmpty().ifBlank {
                    localizer?.get(R.string.telegram_pinned_message_label) ?: "Pinned message"
                },
                senderName = dto.pinnedSenderName,
            )
        } else {
            null
        }
        return TelegramChatInfo(
            chatId = dto.telegramChatId,
            title = dto.title.ifBlank {
                localizer?.get(R.string.telegram_chat_title) ?: "Telegram Group Chat"
            },
            memberCount = 0,
            onlineCount = 0,
            avatarFileId = 0,
            avatarLocalPath = null,
            avatarRemoteUrl = dto.avatarUrl?.takeIf { it.isNotBlank() },
            pinnedMessage = pinned,
            isForum = false,
        )
    }

    internal fun mapMessage(
        dto: TelegramMirrorMessageDto,
        chatId: Long,
        pinnedMessageId: Long? = null,
    ): TelegramMessageUi {
        val formattedText = mapFormattedText(dto.textContent, dto.entitiesJson)
        val replyPreview = if (dto.replyToMessageId != null && dto.replyToMessageId > 0L) {
            TelegramReplyPreviewUi(
                messageId = dto.replyToMessageId,
                senderName = dto.replySenderName?.takeIf { it.isNotBlank() } ?: "Message",
                previewText = dto.replyPreviewText?.takeIf { it.isNotBlank() } ?: "...",
            )
        } else {
            null
        }
        return TelegramMessageUi(
            id = dto.telegramMessageId,
            chatId = chatId,
            senderId = dto.senderId,
            senderName = dto.senderName.ifBlank { "Member" },
            senderAvatarFileId = 0,
            senderAvatarPath = null,
            isOutgoing = false,
            isSending = false,
            sendFailed = false,
            sendError = null,
            dateSeconds = parseIsoTimestampSeconds(dto.sentAt),
            isEdited = !dto.editedAt.isNullOrBlank(),
            isPinned = pinnedMessageId != null && dto.telegramMessageId == pinnedMessageId,
            replyPreview = replyPreview,
            content = mapContent(dto, formattedText),
            reactions = emptyList(),
        )
    }

    private fun mapContent(
        dto: TelegramMirrorMessageDto,
        formattedText: TelegramFormattedText,
    ): TelegramMessageContentUi {
        return when (dto.contentType.lowercase(Locale.US)) {
            "text" -> TelegramMessageContentUi.Text(formattedText)
            "photo" -> TelegramMessageContentUi.Photo(
                formattedCaption = formattedText,
                fileId = 0,
                localPath = null,
                isDownloading = false,
                width = dto.mediaWidth,
                height = dto.mediaHeight,
                minithumbnailBytes = decodeThumbBase64(dto.mediaThumbBase64),
                isReadOnlyPreview = true,
            )
            "sticker" -> TelegramMessageContentUi.Sticker(
                emoji = dto.textContent.ifBlank { "🎨" },
                fileId = 0,
                localPath = null,
                isWebp = false,
                width = dto.mediaWidth,
                height = dto.mediaHeight,
            )
            "document" -> TelegramMessageContentUi.Document(
                fileName = dto.fileName?.ifBlank { "document" } ?: "document",
                mimeType = "",
                sizeBytes = dto.fileSize,
                fileId = 0,
                localPath = null,
                isDownloading = false,
                formattedCaption = formattedText,
            )
            "video" -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer?.get(R.string.telegram_media_video_label) ?: "Video",
                formattedCaption = formattedText,
            )
            "animation" -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer?.get(R.string.telegram_media_animation_label) ?: "GIF",
                formattedCaption = formattedText,
            )
            "voice" -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer?.get(R.string.telegram_media_voice_label) ?: "Voice message",
                formattedCaption = formattedText,
            )
            "audio" -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer?.get(R.string.telegram_media_audio_label) ?: "Audio",
                formattedCaption = formattedText,
            )
            "service" -> TelegramMessageContentUi.SystemMessage(
                text = dto.textContent.ifBlank { "..." }
            )
            else -> TelegramMessageContentUi.Unsupported(
                typeName = dto.contentType.ifBlank { "Unsupported" }
            )
        }
    }

    private fun mapFormattedText(
        text: String,
        entities: List<TelegramMirrorEntityDto>,
    ): TelegramFormattedText {
        if (text.isEmpty() || entities.isEmpty()) {
            return TelegramFormattedText(text = text)
        }
        val mapped = entities.mapNotNull { e ->
            if (e.length <= 0) return@mapNotNull null
            val mappedType = when (e.type.lowercase(Locale.US)) {
                "url" -> TelegramEntityType.Url
                "text_url", "text_link", "texturl" -> e.url?.takeIf { it.isNotBlank() }?.let { TelegramEntityType.TextUrl(it) }
                "bold" -> TelegramEntityType.Bold
                "italic" -> TelegramEntityType.Italic
                "code" -> TelegramEntityType.Code
                "pre" -> TelegramEntityType.Pre
                "underline" -> TelegramEntityType.Underline
                "strikethrough" -> TelegramEntityType.Strikethrough
                "spoiler" -> TelegramEntityType.Spoiler
                "mention", "text_mention", "mentionname" -> TelegramEntityType.Mention
                "bot_command", "botcommand" -> TelegramEntityType.BotCommand
                "hashtag" -> TelegramEntityType.Hashtag
                else -> null
            } ?: return@mapNotNull null
            TelegramTextEntity(
                offset = e.offset,
                length = e.length,
                type = mappedType,
            )
        }
        return TelegramFormattedText(text = text, entities = mapped)
    }

    private fun localizedLoadError(err: Throwable): String {
        val fallback = localizer?.get(R.string.telegram_mirror_load_error)
            ?: "Unable to load messages. Please try again."
        val detail = err.message?.takeIf { it.isNotBlank() }
        return if (localizer != null) fallback else (detail ?: fallback)
    }

    companion object {
        const val DEFAULT_CHAT_KEY = "community_main"
        const val DEFAULT_PAGE_LIMIT = 40

        internal fun decodeThumbBase64(raw: String?): ByteArray? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            val cleaned = trimmed
                .substringAfter("base64,", trimmed)
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
            if (cleaned.isEmpty()) return null
            return runCatching { Base64.getDecoder().decode(cleaned) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }

        internal fun parseIsoTimestampSeconds(raw: String?): Int {
            if (raw.isNullOrBlank()) return 0
            return runCatching {
                OffsetDateTime.parse(raw.trim()).toEpochSecond().toInt()
            }.recoverCatching {
                Instant.parse(raw.trim()).epochSecond.toInt()
            }.getOrDefault(0)
        }
    }
}
