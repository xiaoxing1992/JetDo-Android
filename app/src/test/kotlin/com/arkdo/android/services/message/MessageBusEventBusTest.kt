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

    @Test
    fun extractIncomingHomeTopicId_accepts_latest_and_new_topic_only() {
        val latest = MessageBusEvent(
            type = "message",
            channel = "/latest",
            data = MessageBusData(topicId = 7L, messageType = "latest")
        )
        val created = MessageBusEvent(
            type = "message",
            channel = "/new",
            data = MessageBusData(topicId = 8L, messageType = "new_topic")
        )
        val muted = MessageBusEvent(
            type = "message",
            channel = "/latest",
            data = MessageBusData(topicId = 9L, messageType = "muted")
        )
        val topicChannel = MessageBusEvent(
            type = "message",
            channel = "/topic/7",
            data = MessageBusData(topicId = 7L, messageType = "latest")
        )

        assertEquals(7L, extractIncomingHomeTopicId(latest))
        assertEquals(8L, extractIncomingHomeTopicId(created))
        assertEquals(null, extractIncomingHomeTopicId(muted))
        assertEquals(null, extractIncomingHomeTopicId(topicChannel))
    }
}
