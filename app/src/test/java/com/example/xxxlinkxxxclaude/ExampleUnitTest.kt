package com.example.p2pcodec2

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun retainedMessage_isSkippedOnlyAfterLocalPersistence() {
        assertTrue(shouldProcessRetainedMessage(isPersistedLocally = false))
        assertFalse(shouldProcessRetainedMessage(isPersistedLocally = true))
    }

    @Test
    fun notificationRouting_prefersGroupAndFallsBackToSender() {
        assertEquals("g:GROUP", notificationChatId("sender-123", "g:GROUP"))
        assertEquals("sender-123", notificationChatId("sender-123", null))
        assertEquals("sender-123", notificationChatId("sender-123", ""))
    }

    @Test
    fun groupFanout_usesCanonicalMessageId() {
        assertEquals(
            "sender-123-1",
            canonicalCloudMessageId("sender-123-1-ABCD1234", "ABCD1234", "g:GROUP")
        )
        assertEquals(
            "sender-123-1",
            canonicalCloudMessageId("sender-123-1", "ABCD1234", null)
        )
    }

    @Test
    fun inFlightDedup_allowsRetryAfterProcessingFinishes() {
        val deduplicator = InFlightMessageDeduplicator()

        assertTrue(deduplicator.tryStart("message-1"))
        assertFalse(deduplicator.tryStart("message-1"))
        deduplicator.finish("message-1")
        assertTrue(deduplicator.tryStart("message-1"))
    }

    @Test
    fun acknowledgement_requiresConfirmedLocalPersistence() {
        assertFalse(shouldAcknowledgeCloudMessage(localPersistenceSucceeded = false))
        assertTrue(shouldAcknowledgeCloudMessage(localPersistenceSucceeded = true))
    }
}
