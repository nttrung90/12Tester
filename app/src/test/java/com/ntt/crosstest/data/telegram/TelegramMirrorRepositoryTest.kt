package com.ntt.crosstest.data.telegram

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TelegramMirrorRepositoryTest {

    private data class CapturedCall(
        val chatKey: String,
        val beforeMessageId: Long?,
        val afterMessageId: Long?,
        val sinceEditedAt: String?,
        val limit: Int,
    )

    private class FakeFeedProvider(
        private val responder: (CapturedCall) -> String,
    ) : TelegramMirrorFeedProvider {
        val calls = mutableListOf<CapturedCall>()

        override suspend fun fetchFeed(
            chatKey: String,
            beforeMessageId: Long?,
            afterMessageId: Long?,
            sinceEditedAt: String?,
            limit: Int,
        ): String {
            val call = CapturedCall(chatKey, beforeMessageId, afterMessageId, sinceEditedAt, limit)
            calls.add(call)
            return responder(call)
        }
    }

    @Test
    fun loadInitialFeedParsesChatMessagesEntitiesReplyAndPhotoThumbnail() = runTest {
        val rawThumb = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x7F)
        val thumbBase64 = Base64.getEncoder().encodeToString(rawThumb)
        val initialJson = """
            {
              "chat": {
                "chat_key": "community_main",
                "telegram_chat_id": -1001234567890,
                "title": "12 Cross Testers",
                "avatar_url": "https://example.com/avatar.jpg",
                "pinned_message_id": 100,
                "pinned_preview": "Read community rules",
                "pinned_sender_name": "Admin",
                "updated_at": "2026-11-20T00:00:00Z"
              },
              "messages": [
                {
                  "telegram_message_id": 101,
                  "sender_id": 501,
                  "sender_name": "Alice",
                  "sender_is_bot": false,
                  "content_type": "photo",
                  "text_content": "Check https://example.com",
                  "entities_json": [
                    { "offset": 6, "length": 19, "type": "url", "url": null }
                  ],
                  "media_width": 1280,
                  "media_height": 720,
                  "media_thumb_base64": "$thumbBase64",
                  "file_name": null,
                  "file_size": 0,
                  "reply_to_message_id": 100,
                  "reply_sender_name": "Admin",
                  "reply_preview_text": "Read community rules",
                  "sent_at": "2026-11-20T00:05:00Z",
                  "edited_at": null,
                  "is_deleted": false
                },
                {
                  "telegram_message_id": 100,
                  "sender_id": 1,
                  "sender_name": "Admin",
                  "sender_is_bot": false,
                  "content_type": "text",
                  "text_content": "Read community rules",
                  "entities_json": [
                    { "offset": 0, "length": 4, "type": "bold" },
                    { "offset": 5, "length": 9, "type": "text_url", "url": "https://12testers.app/rules" }
                  ],
                  "media_width": 0,
                  "media_height": 0,
                  "media_thumb_base64": null,
                  "file_name": null,
                  "file_size": 0,
                  "reply_to_message_id": null,
                  "reply_sender_name": null,
                  "reply_preview_text": null,
                  "sent_at": "2026-11-20T00:01:00+00:00",
                  "edited_at": null,
                  "is_deleted": false
                }
              ],
              "has_more": true,
              "server_time": "2026-11-20T00:06:00.000000Z"
            }
        """.trimIndent()

        val provider = FakeFeedProvider { initialJson }
        val repo = TelegramMirrorRepository(feedProvider = provider)

        val result = repo.loadInitialFeed()
        assertTrue(result.isSuccess)
        assertEquals(1, provider.calls.size)
        assertEquals("community_main", provider.calls[0].chatKey)
        assertNull(provider.calls[0].beforeMessageId)
        assertNull(provider.calls[0].afterMessageId)
        assertNull(provider.calls[0].sinceEditedAt)
        assertEquals(40, provider.calls[0].limit)

        val state = repo.uiState.value
        val chatInfo = state.chatInfo
        assertNotNull(chatInfo)
        assertEquals(-1001234567890L, chatInfo!!.chatId)
        assertEquals("12 Cross Testers", chatInfo.title)
        assertEquals("https://example.com/avatar.jpg", chatInfo.avatarRemoteUrl)
        assertEquals(100L, chatInfo.pinnedMessage?.messageId)
        assertEquals("Read community rules", chatInfo.pinnedMessage?.previewText)
        assertTrue(state.hasMoreHistory)
        assertEquals("2026-11-20T00:06:00.000000Z", state.serverTime)
        assertEquals(101L, state.latestMessageId)

        assertEquals(listOf(101L, 100L), state.messages.map { it.id })
        val photoMsg = state.messages[0]
        assertEquals("Alice", photoMsg.senderName)
        assertEquals(100L, photoMsg.replyPreview?.messageId)
        assertEquals("Admin", photoMsg.replyPreview?.senderName)
        val photoContent = photoMsg.content as TelegramMessageContentUi.Photo
        assertTrue(photoContent.isReadOnlyPreview)
        assertArrayEquals(rawThumb, photoContent.minithumbnailBytes)
        assertEquals(TelegramEntityType.Url, photoContent.formattedCaption.entities.single().type)

        val textMsg = state.messages[1]
        assertTrue(textMsg.isPinned)
        val textContent = textMsg.content as TelegramMessageContentUi.Text
        assertEquals(TelegramEntityType.Bold, textContent.formattedText.entities[0].type)
        assertEquals(
            TelegramEntityType.TextUrl("https://12testers.app/rules"),
            textContent.formattedText.entities[1].type,
        )
    }

    @Test
    fun loadOlderMessagesPaginatesBeforeOldestAndDeltaPollMergesEditsAndDeletes() = runTest {
        val provider = FakeFeedProvider { call ->
            when {
                call.beforeMessageId == null && call.afterMessageId == null -> """
                    {
                      "chat": { "chat_key": "community_main", "telegram_chat_id": -10099, "title": "Group" },
                      "messages": [
                        { "telegram_message_id": 20, "sender_id": 1, "sender_name": "U1", "content_type": "text", "text_content": "Msg 20", "sent_at": "2026-11-20T00:02:00Z" },
                        { "telegram_message_id": 10, "sender_id": 2, "sender_name": "U2", "content_type": "text", "text_content": "Msg 10", "sent_at": "2026-11-20T00:01:00Z" }
                      ],
                      "has_more": true,
                      "server_time": "2026-11-20T00:02:30Z"
                    }
                """.trimIndent()

                call.beforeMessageId == 10L -> """
                    {
                      "chat": { "chat_key": "community_main", "telegram_chat_id": -10099, "title": "Group" },
                      "messages": [
                        { "telegram_message_id": 5, "sender_id": 3, "sender_name": "U3", "content_type": "document", "text_content": "Spec", "file_name": "spec.pdf", "file_size": 2048, "sent_at": "2026-11-20T00:00:30Z" }
                      ],
                      "has_more": false,
                      "server_time": "2026-11-20T00:02:35Z"
                    }
                """.trimIndent()

                call.afterMessageId == 20L -> """
                    {
                      "chat": { "chat_key": "community_main", "telegram_chat_id": -10099, "title": "Group Updated" },
                      "messages": [
                        { "telegram_message_id": 25, "sender_id": 4, "sender_name": "U4", "content_type": "text", "text_content": "New msg 25", "sent_at": "2026-11-20T00:03:00Z" },
                        { "telegram_message_id": 20, "sender_id": 1, "sender_name": "U1", "content_type": "text", "text_content": "Msg 20 edited", "sent_at": "2026-11-20T00:02:00Z", "edited_at": "2026-11-20T00:03:10Z" },
                        { "telegram_message_id": 10, "sender_id": 2, "sender_name": "U2", "content_type": "text", "text_content": "", "sent_at": "2026-11-20T00:01:00Z", "is_deleted": true }
                      ],
                      "has_more": false,
                      "server_time": "2026-11-20T00:03:15Z"
                    }
                """.trimIndent()

                else -> error("Unexpected call: $call")
            }
        }

        val repo = TelegramMirrorRepository(feedProvider = provider)
        assertTrue(repo.loadInitialFeed().isSuccess)
        assertEquals(listOf(20L, 10L), repo.uiState.value.messages.map { it.id })
        assertTrue(repo.uiState.value.hasMoreHistory)

        assertTrue(repo.loadOlderMessages().isSuccess)
        assertEquals(listOf(20L, 10L, 5L), repo.uiState.value.messages.map { it.id })
        assertFalse(repo.uiState.value.hasMoreHistory)
        val docContent = repo.uiState.value.messages.last().content as TelegramMessageContentUi.Document
        assertEquals("spec.pdf", docContent.fileName)
        assertEquals(2048L, docContent.sizeBytes)

        assertTrue(repo.pollUpdates().isSuccess)
        val deltaCall = provider.calls.last()
        assertEquals(20L, deltaCall.afterMessageId)
        assertEquals("2026-11-20T00:02:30Z", deltaCall.sinceEditedAt)

        val stateAfterPoll = repo.uiState.value
        assertEquals("Group Updated", stateAfterPoll.chatInfo?.title)
        // Message 10 was soft-deleted, 20 was edited, 25 was inserted
        assertEquals(listOf(25L, 20L, 5L), stateAfterPoll.messages.map { it.id })
        val editedMsg = stateAfterPoll.messages.first { it.id == 20L }
        assertTrue(editedMsg.isEdited)
        assertEquals("Msg 20 edited", (editedMsg.content as TelegramMessageContentUi.Text).formattedText.text)
        assertEquals(25L, stateAfterPoll.latestMessageId)
    }

    @Test
    fun providerErrorsAreCapturedWithoutCrashingAndClearErrorResetsBanner() = runTest {
        var shouldFail = true
        val provider = FakeFeedProvider {
            if (shouldFail) throw IllegalStateException("Guest network timeout")
            """{"chat":null,"messages":[],"has_more":false,"server_time":"2026-11-20T00:00:00Z"}"""
        }

        val repo = TelegramMirrorRepository(feedProvider = provider)
        val failResult = repo.loadInitialFeed()
        assertTrue(failResult.isFailure)
        assertFalse(repo.uiState.value.isLoadingInitial)
        assertNotNull(repo.uiState.value.errorMessage)

        repo.clearError()
        assertNull(repo.uiState.value.errorMessage)

        shouldFail = false
        val okResult = repo.loadInitialFeed()
        assertTrue(okResult.isSuccess)
        assertTrue(repo.uiState.value.messages.isEmpty())
    }

    @Test
    fun uninitializedSupabaseProviderFailsGracefullyForGuestSafetyCheck() = runTest {
        val provider = SupabaseTelegramMirrorFeedProvider(clientProvider = { null })
        val repo = TelegramMirrorRepository(feedProvider = provider)
        val result = repo.loadInitialFeed()
        assertTrue(result.isFailure)
        assertNotNull(repo.uiState.value.errorMessage)
    }
}
