package me.shirazu.mizar.kit

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationAttachmentsTest {
    @Test fun `persisted DSH attachment refs render even with no text`() {
        val page = WIRE_JSON.decodeFromString<SessionMessagesResult>("""
            {"sessionId":"s","records":[
              {"type":"user/message","seq":1,"time":1,"data":{"source":{"kind":"user"},"content":[
                {"type":"image","attachment":{"attachmentId":"img1","name":"photo.png","mediaType":"image/png","bytes":42,"width":16,"height":16},"offloaded":true},
                {"type":"file","attachment":{"attachmentId":"file1","name":"notes.pdf","bytes":100}}
              ]}},
              {"type":"turn/start","seq":2,"time":2,"data":{"turn":1}}
            ]}
        """.trimIndent())
        val item = assertIs<ConversationItem.Message>(Conversation.items(page).single())
        assertEquals("", item.text)
        assertEquals(listOf("image", "file"), item.attachments.map { it.kind })
        assertEquals(listOf("photo.png", "notes.pdf"), item.attachments.map { it.name })
        assertEquals(listOf("img1", "file1"), item.attachments.map { it.id })
        assertEquals(42L, item.attachments.first().bytes)
        assertEquals("image/png", item.attachments.first().mediaType)
        assertEquals(item, Conversation.turns(page).single().prompts.single())
    }

    @Test fun `different attachment only messages are not deduplicated by empty text`() {
        val page = WIRE_JSON.decodeFromString<SessionMessagesResult>("""
            {"sessionId":"s","records":[
              {"type":"user/message","seq":1,"data":{"source":{"kind":"user"},"content":[{"type":"file","attachment":{"attachmentId":"a","name":"same.bin","bytes":10}}]}},
              {"type":"user/message","seq":2,"data":{"source":{"kind":"user"},"content":[{"type":"file","attachment":{"attachmentId":"b","name":"same.bin","bytes":10}}]}}
            ]}
        """.trimIndent())
        assertEquals(2, Conversation.items(page).size)
    }

    @Test fun `wrapped assistant attachments retain metadata but never raw data or remote URL`() {
        val page = WIRE_JSON.decodeFromString<SessionMessagesResult>("""
            {"sessionId":"s","records":[{"type":"event","event":{"type":"assistant/message","seq":1,"data":{"message":{"content":[
              {"type":"text","text":"image output"},
              {"type":"image","name":"../../photo.png","data":"DO_NOT_DECODE","url":"https://not-fetched.invalid/private"}
            ]}}}}]}
        """.trimIndent())
        val item = assertIs<ConversationItem.Message>(Conversation.items(page).single())
        assertEquals("image output", item.text)
        assertEquals("photo.png", item.attachments.single().name)
        assertFalse(item.toString().contains("DO_NOT_DECODE"))
        assertFalse(item.toString().contains("not-fetched"))
    }

    @Test fun `runtime user shaped attachment records are still hidden`() {
        val page = WIRE_JSON.decodeFromString<SessionMessagesResult>("""
            {"sessionId":"s","records":[{"type":"user/message","seq":1,"data":{"source":{"kind":"runtime-context"},"content":[
              {"type":"file","attachment":{"attachmentId":"internal","name":"private.log","bytes":10}}
            ]}}]}
        """.trimIndent())
        assertTrue(Conversation.items(page).isEmpty())
    }
}
