package ru.arc.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import java.util.UUID

class ChatListenerTest {
    private lateinit var scheduler: TestTaskScheduler

    @BeforeEach
    fun setUp() {
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
    }

    @AfterEach
    fun tearDown() {
        Tasks.reset()
    }

    @Test
    fun `npc chat handling is moved from async event to the main scheduler`() {
        val player =
            mockk<Player> {
                every { uniqueId } returns UUID.randomUUID()
            }
        val event =
            mockk<AsyncChatEvent>(relaxed = true) {
                every { isAsynchronous } returns true
                every { this@mockk.player } returns player
                every { message() } returns Component.text("hello")
            }
        var handledMessage: String? = null
        var handledPlayer: Player? = null
        val listener =
            ChatListener { message, actualPlayer ->
                handledMessage = message
                handledPlayer = actualPlayer
            }

        listener.onPlayerChat(event)

        assertNull(handledMessage)
        scheduler.executeImmediate()
        assertEquals("hello", handledMessage)
        assertEquals(player, handledPlayer)
    }
}
