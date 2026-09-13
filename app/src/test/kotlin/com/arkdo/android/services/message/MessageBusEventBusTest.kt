package com.arkdo.android.services.message

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBusEventBusTest {
    @Test
    fun extractMessageBusTopicId_ignores_nonTopic_channels() {
        val event = MessageBusEvent(
            type = "message",
            channel = "/notification/7",
            data = MessageBusData(topicId = 42L)
        )

        assertEquals(null, extractMessageBusTopicId(event))
    }

    @Test
    fun extractMessageBusTopicId_accepts_topic_channels() {
        val event = MessageBusEvent(
            type = "message",
            channel = "/topic/42",
            data = MessageBusData(topicId = 42L)
        )

        assertEquals(42L, extractMessageBusTopicId(event))
    }
}
