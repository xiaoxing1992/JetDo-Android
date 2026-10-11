package com.jetdo.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationItemTest {
    @Test
    fun copy_changes_only_read_state() {
        val item = NotificationItem(
            id = 7L,
            type = 2,
            read = false,
            createdAt = "2026-09-12T00:00:00Z",
            actorName = "Ada",
            actorUsername = "ada",
            actionText = "回复了你",
            title = "Architecture",
            iconId = "reply",
            iconColor = "#3B82F6"
        )

        val readItem = item.copy(read = true)

        assertEquals(true, readItem.read)
        assertEquals(item.copy(read = true), readItem)
        assertEquals(item.id, readItem.id)
        assertEquals(item.title, readItem.title)
    }
}
