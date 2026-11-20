package com.ntt.crosstest.data.telegram

enum class TelegramChatViewMode {
    READ_ONLY_MIRROR,
    AUTH_FLOW,
    FULL_TDLIB,
}

enum class OtpDeliveryType {
    TELEGRAM_APP,
    SMS,
    CALL,
    MISSED_CALL,
    FRAGMENT,
    UNKNOWN,
}

enum class TelegramConnectionStatus {
    READY,
    CONNECTING,
    UPDATING,
    WAITING_FOR_NETWORK,
    CONNECTING_TO_PROXY,
}

sealed interface TelegramAuthUiState {
    data object Initializing : TelegramAuthUiState

    data class WaitPhoneNumber(
        val countryCode: String = "+84",
        val phoneNumberInput: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
    ) : TelegramAuthUiState

    data class WaitCode(
        val phoneNumber: String,
        val codeType: OtpDeliveryType,
        val codeLength: Int,
        val nextType: OtpDeliveryType?,
        val timeoutSeconds: Int,
        val isLoading: Boolean = false,
        val error: String? = null,
    ) : TelegramAuthUiState

    data class WaitPassword(
        val passwordHint: String,
        val hasRecoveryEmail: Boolean,
        val recoveryEmailPattern: String,
        val isRecoveryMode: Boolean = false,
        val isLoading: Boolean = false,
        val error: String? = null,
    ) : TelegramAuthUiState

    data class WaitRegistration(
        val suggestedFirstName: String = "",
        val suggestedLastName: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
    ) : TelegramAuthUiState

    data class ResolvingGroup(
        val statusMessage: String? = null,
    ) : TelegramAuthUiState

    data object JoinRequestSent : TelegramAuthUiState

    data object Ready : TelegramAuthUiState

    data class Error(
        val message: String,
    ) : TelegramAuthUiState
}

data class PinnedMessageUi(
    val messageId: Long,
    val previewText: String,
    val senderName: String? = null,
)

data class TelegramChatInfo(
    val chatId: Long,
    val title: String,
    val memberCount: Int = 0,
    val onlineCount: Int = 0,
    val avatarFileId: Int = 0,
    val avatarLocalPath: String? = null,
    val avatarRemoteUrl: String? = null,
    val pinnedMessage: PinnedMessageUi? = null,
    val isForum: Boolean = false,
)

data class TelegramSenderUi(
    val id: Long,
    val displayName: String,
    val avatarFileId: Int = 0,
    val avatarLocalPath: String? = null,
    val isBot: Boolean = false,
)

data class TelegramReactionUi(
    val emoji: String,
    val count: Int,
    val isChosen: Boolean,
)

data class TelegramReplyPreviewUi(
    val messageId: Long,
    val senderName: String,
    val previewText: String,
)

sealed interface TelegramEntityType {
    data object Url : TelegramEntityType
    data class TextUrl(val url: String) : TelegramEntityType
    data object Bold : TelegramEntityType
    data object Italic : TelegramEntityType
    data object Code : TelegramEntityType
    data object Pre : TelegramEntityType
    data object Underline : TelegramEntityType
    data object Strikethrough : TelegramEntityType
    data object Spoiler : TelegramEntityType
    data object Mention : TelegramEntityType
    data object BotCommand : TelegramEntityType
    data object Hashtag : TelegramEntityType
}

data class TelegramTextEntity(
    val offset: Int,
    val length: Int,
    val type: TelegramEntityType,
)

data class TelegramFormattedText(
    val text: String,
    val entities: List<TelegramTextEntity> = emptyList(),
)

sealed interface TelegramMessageContentUi {
    data class Text(
        val formattedText: TelegramFormattedText,
    ) : TelegramMessageContentUi

    data class Photo(
        val formattedCaption: TelegramFormattedText,
        val fileId: Int,
        val localPath: String?,
        val isDownloading: Boolean,
        val width: Int,
        val height: Int,
        val minithumbnailBytes: ByteArray? = null,
        val isReadOnlyPreview: Boolean = false,
    ) : TelegramMessageContentUi {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Photo) return false
            return formattedCaption == other.formattedCaption &&
                fileId == other.fileId &&
                localPath == other.localPath &&
                isDownloading == other.isDownloading &&
                width == other.width &&
                height == other.height &&
                isReadOnlyPreview == other.isReadOnlyPreview &&
                (minithumbnailBytes contentEquals other.minithumbnailBytes)
        }

        override fun hashCode(): Int {
            var result = formattedCaption.hashCode()
            result = 31 * result + fileId
            result = 31 * result + (localPath?.hashCode() ?: 0)
            result = 31 * result + isDownloading.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + isReadOnlyPreview.hashCode()
            result = 31 * result + (minithumbnailBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Sticker(
        val emoji: String,
        val fileId: Int,
        val localPath: String?,
        val isWebp: Boolean,
        val width: Int,
        val height: Int,
    ) : TelegramMessageContentUi

    data class Document(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val fileId: Int,
        val localPath: String?,
        val isDownloading: Boolean,
        val formattedCaption: TelegramFormattedText,
    ) : TelegramMessageContentUi

    data class MediaPlaceholder(
        val typeLabel: String,
        val formattedCaption: TelegramFormattedText,
    ) : TelegramMessageContentUi

    data class SystemMessage(
        val text: String,
    ) : TelegramMessageContentUi

    data class Unsupported(
        val typeName: String,
    ) : TelegramMessageContentUi
}

data class TelegramMessageUi(
    val id: Long,
    val chatId: Long,
    val senderId: Long,
    val senderName: String,
    val senderAvatarFileId: Int = 0,
    val senderAvatarPath: String? = null,
    val isOutgoing: Boolean,
    val isSending: Boolean = false,
    val sendFailed: Boolean = false,
    val sendError: String? = null,
    val dateSeconds: Int,
    val isEdited: Boolean = false,
    val isPinned: Boolean = false,
    val replyPreview: TelegramReplyPreviewUi? = null,
    val content: TelegramMessageContentUi,
    val reactions: List<TelegramReactionUi> = emptyList(),
)

data class TelegramChatUiState(
    val viewMode: TelegramChatViewMode = TelegramChatViewMode.READ_ONLY_MIRROR,
    val isConfigured: Boolean = true,
    val authState: TelegramAuthUiState = TelegramAuthUiState.Initializing,
    val connectionStatus: TelegramConnectionStatus = TelegramConnectionStatus.CONNECTING,
    val chatInfo: TelegramChatInfo? = null,
    val messages: List<TelegramMessageUi> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreHistory: Boolean = true,
    val replyingTo: TelegramMessageUi? = null,
    val editingMessage: TelegramMessageUi? = null,
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<TelegramMessageUi> = emptyList(),
    val isSearching: Boolean = false,
    val errorBanner: String? = null,
)

data class CountryDialCode(
    val iso: String,
    val name: String,
    val dialCode: String,
    val flag: String,
)

object TelegramCountryCodes {
    val DEFAULT = CountryDialCode("VN", "Vietnam", "+84", "🇻🇳")

    val ALL = listOf(
        DEFAULT,
        CountryDialCode("US", "United States / Canada", "+1", "🇺🇸"),
        CountryDialCode("GB", "United Kingdom", "+44", "🇬🇧"),
        CountryDialCode("SG", "Singapore", "+65", "🇸🇬"),
        CountryDialCode("JP", "Japan", "+81", "🇯🇵"),
        CountryDialCode("KR", "South Korea", "+82", "🇰🇷"),
        CountryDialCode("IN", "India", "+91", "🇮🇳"),
        CountryDialCode("ID", "Indonesia", "+62", "🇮🇩"),
        CountryDialCode("TH", "Thailand", "+66", "🇹🇭"),
        CountryDialCode("MY", "Malaysia", "+60", "🇲🇾"),
        CountryDialCode("PH", "Philippines", "+63", "🇵🇭"),
        CountryDialCode("AU", "Australia", "+61", "🇦🇺"),
        CountryDialCode("DE", "Germany", "+49", "🇩🇪"),
        CountryDialCode("FR", "France", "+33", "🇫🇷"),
        CountryDialCode("BR", "Brazil", "+55", "🇧🇷"),
        CountryDialCode("RU", "Russia", "+7", "🇷🇺"),
    )
}
