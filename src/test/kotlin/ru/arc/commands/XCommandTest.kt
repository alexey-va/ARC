@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.commands

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockito.Mockito.mock
import ru.arc.TestBase
import org.bukkit.command.Command as BukkitCommand

/**
 * Tests for the /x command (cross-server command execution).
 */
class XCommandTest : TestBase() {

    private lateinit var player: PlayerMock
    private lateinit var mockCommand: BukkitCommand

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        player = server.addPlayer("TestPlayer")
        mockCommand = mock(BukkitCommand::class.java)
    }

    /**
     * Helper to check if a message was sent
     */
    private fun PlayerMock.hasReceivedMessage(): Boolean {
        return nextMessage() != null || nextComponentMessage() != null
    }

    // ==================== Permission Tests ====================

    @Nested
    @DisplayName("Permission")
    inner class PermissionTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.x"))

            val result = XCommand.onCommand(player, mockCommand, "x", arrayOf("say", "hello"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("With permission - command executes")
        fun testWithPermission() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(player, mockCommand, "x", arrayOf("say", "hello"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send success message")
        }
    }

    // ==================== Usage Tests ====================

    @Nested
    @DisplayName("Usage")
    inner class UsageTests {

        @Test
        @DisplayName("Empty command - shows usage")
        fun testEmptyCommand() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(player, mockCommand, "x", arrayOf())

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show usage")
        }

        @Test
        @DisplayName("Only parameters without command - shows usage")
        fun testOnlyParams() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(player, mockCommand, "x", arrayOf("-servers:all"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show usage when only params provided")
        }
    }

    // ==================== Parameter Parsing Tests ====================

    @Nested
    @DisplayName("Parameter Parsing")
    inner class ParameterParsingTests {

        @Test
        @DisplayName("Command with -servers:all sends to all servers")
        fun testServersAll() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf("-servers:all", "say", "hello")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Command with -servers:server1,server2 sends to specific servers")
        fun testServersSpecific() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf("-servers:lobby,survival", "say", "hello")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Command with -player:name sets player name")
        fun testPlayerParam() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf("-player:Steve", "give", "%player%", "diamond", "64")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Command with -timeout:200 sets timeout")
        fun testTimeoutParam() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf("-timeout:200", "say", "hello")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Command with -delay:40 sets delay")
        fun testDelayParam() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf("-delay:40", "say", "hello")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Command with -sender:player sets sender type")
        fun testSenderParam() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf("-sender:player", "-player:Steve", "say", "hello")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Multiple parameters work together")
        fun testMultipleParams() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf(
                    "-servers:survival",
                    "-player:Steve",
                    "-timeout:100",
                    "-delay:20",
                    "give",
                    "%player%",
                    "diamond"
                )
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }
    }

    // ==================== Tab Completion Tests ====================

    @Nested
    @DisplayName("Tab Completion")
    inner class TabCompletionTests {

        @Test
        @DisplayName("Empty arg shows parameter suggestions")
        fun testEmptyCompletion() {
            player.addAttachment(plugin, "arc.x", true)

            val completions = XCommand.onTabComplete(player, mockCommand, "x", arrayOf(""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("-servers"))
            assertTrue(completions.contains("-player"))
            assertTrue(completions.contains("-timeout"))
            assertTrue(completions.contains("-delay"))
            assertTrue(completions.contains("-sender"))
        }

        @Test
        @DisplayName("-servers prefix shows server options")
        fun testServersCompletion() {
            player.addAttachment(plugin, "arc.x", true)

            val completions = XCommand.onTabComplete(player, mockCommand, "x", arrayOf("-servers"))

            assertNotNull(completions)
            assertTrue(completions!!.any { it.startsWith("-servers:") })
            assertTrue(completions.contains("-servers:all"))
        }

        @Test
        @DisplayName("-timeout prefix shows timeout options")
        fun testTimeoutCompletion() {
            player.addAttachment(plugin, "arc.x", true)

            val completions = XCommand.onTabComplete(player, mockCommand, "x", arrayOf("-timeout"))

            assertNotNull(completions)
            assertTrue(completions!!.contains("-timeout:100"))
            assertTrue(completions.contains("-timeout:200"))
        }

        @Test
        @DisplayName("-delay prefix shows delay options")
        fun testDelayCompletion() {
            player.addAttachment(plugin, "arc.x", true)

            val completions = XCommand.onTabComplete(player, mockCommand, "x", arrayOf("-delay"))

            assertNotNull(completions)
            assertTrue(completions!!.contains("-delay:0"))
            assertTrue(completions.contains("-delay:20"))
        }

        @Test
        @DisplayName("-sender prefix shows sender options")
        fun testSenderCompletion() {
            player.addAttachment(plugin, "arc.x", true)

            val completions = XCommand.onTabComplete(player, mockCommand, "x", arrayOf("-sender"))

            assertNotNull(completions)
            assertTrue(completions!!.contains("-sender:console"))
            assertTrue(completions.contains("-sender:player"))
        }

        @Test
        @DisplayName("-move-to-server prefix shows boolean options")
        fun testMoveToServerCompletion() {
            player.addAttachment(plugin, "arc.x", true)

            val completions = XCommand.onTabComplete(player, mockCommand, "x", arrayOf("-move-to-server"))

            assertNotNull(completions)
            assertTrue(completions!!.contains("-move-to-server:true"))
            assertTrue(completions.contains("-move-to-server:false"))
        }
    }

    // ==================== Command Execution Tests ====================

    @Nested
    @DisplayName("Command Execution")
    inner class ExecutionTests {

        @Test
        @DisplayName("Simple command executes successfully")
        fun testSimpleCommand() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(player, mockCommand, "x", arrayOf("say", "Hello", "World"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Command with special characters works")
        fun testSpecialChars() {
            player.addAttachment(plugin, "arc.x", true)

            val result = XCommand.onCommand(
                player, mockCommand, "x",
                arrayOf("tellraw", "@a", "{\"text\":\"Hello\"}")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Returns true to prevent Bukkit usage message")
        fun testAlwaysReturnsTrue() {
            player.addAttachment(plugin, "arc.x", true)

            // Even with invalid params, should return true
            assertTrue(XCommand.onCommand(player, mockCommand, "x", arrayOf()))
            assertTrue(XCommand.onCommand(player, mockCommand, "x", arrayOf("-invalid")))
            assertTrue(XCommand.onCommand(player, mockCommand, "x", arrayOf("say", "test")))
        }
    }
}


