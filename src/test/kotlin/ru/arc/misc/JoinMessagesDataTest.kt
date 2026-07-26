package ru.arc.misc

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JoinMessagesDataTest {
    @Test
    fun `updates join and leave selections independently`() {
        val data = JoinMessagesData("player")

        assertTrue(data.updateMessage("joined", isJoin = true, selected = true))
        assertTrue(data.updateMessage("left", isJoin = false, selected = true))

        assertTrue("joined" in data.joinMessages)
        assertFalse("joined" in data.leaveMessages)
        assertTrue("left" in data.leaveMessages)
        assertFalse("left" in data.joinMessages)
    }

    @Test
    fun `duplicate selection is a no-op`() {
        val data = JoinMessagesData("player", joinMessages = mutableSetOf("joined"))

        assertFalse(data.updateMessage("joined", isJoin = true, selected = true))
    }

    @Test
    fun `removes unavailable messages in one update`() {
        val data =
            JoinMessagesData(
                "player",
                joinMessages = mutableSetOf("valid", "removed-one", "removed-two"),
            )

        assertTrue(
            data.removeMessages(
                setOf("removed-one", "removed-two", "not-selected"),
                isJoin = true,
            ),
        )

        assertTrue(data.joinMessages == setOf("valid"))
    }
}
