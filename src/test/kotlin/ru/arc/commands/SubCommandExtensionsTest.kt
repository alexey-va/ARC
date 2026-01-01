@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.commands

import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.TestBase
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.checkPermission
import ru.arc.commands.arc.onlinePlayerNames
import ru.arc.commands.arc.player
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers

/**
 * Tests for SubCommand interface extensions and helper methods.
 */
class SubCommandExtensionsTest : TestBase() {

    private lateinit var player: PlayerMock

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        player = server.addPlayer("TestPlayer")
    }

    // ==================== tabComplete Extension ====================

    @Nested
    @DisplayName("List.tabComplete()")
    inner class TabCompleteExtensionTests {

        @Test
        @DisplayName("Empty input returns all items sorted by length")
        fun testEmptyInput() {
            val items = listOf("abc", "a", "abcdef", "ab")

            val result = items.tabComplete("")

            assertEquals(listOf("a", "ab", "abc", "abcdef"), result)
        }

        @Test
        @DisplayName("Filters by prefix case-insensitively")
        fun testFiltersByPrefix() {
            val items = listOf("Apple", "Banana", "Apricot", "Cherry")

            val result = items.tabComplete("ap")

            assertEquals(2, result.size)
            assertTrue(result.contains("Apple"))
            assertTrue(result.contains("Apricot"))
        }

        @Test
        @DisplayName("Exact case match comes first")
        fun testExactCaseFirst() {
            val items = listOf("TEST", "test", "Test", "testing")

            val result = items.tabComplete("test")

            // "test" should come first (exact case match)
            assertEquals("test", result.first())
        }

        @Test
        @DisplayName("Shorter completions come before longer ones")
        fun testShorterFirst() {
            val items = listOf("testLong", "test", "testMedium")

            val result = items.tabComplete("test")

            assertEquals("test", result.first())
            assertTrue(result.indexOf("test") < result.indexOf("testLong"))
        }

        @Test
        @DisplayName("Returns empty list when no matches")
        fun testNoMatches() {
            val items = listOf("Apple", "Banana", "Cherry")

            val result = items.tabComplete("xyz")

            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("Works with single item list")
        fun testSingleItem() {
            val items = listOf("only")

            val result = items.tabComplete("on")

            assertEquals(listOf("only"), result)
        }

        @Test
        @DisplayName("Works with empty list")
        fun testEmptyList() {
            val items = emptyList<String>()

            val result = items.tabComplete("test")

            assertTrue(result.isEmpty())
        }
    }

    // ==================== parseFlags Extension ====================

    @Nested
    @DisplayName("SubCommand.parseFlags()")
    inner class ParseFlagsTests {

        // Create a test subcommand to access parseFlags
        private val testSubCmd = object : SubCommand {
            override val configKey = "test"
            override fun execute(sender: CommandSender, args: Array<String>) = true

            fun testParseFlags(args: Array<String>) = parseFlags(args)
        }

        @Test
        @DisplayName("Parses single flag correctly")
        fun testSingleFlag() {
            val args = arrayOf("-key:value")

            val result = testSubCmd.testParseFlags(args)

            assertEquals("value", result["key"])
        }

        @Test
        @DisplayName("Parses multiple flags")
        fun testMultipleFlags() {
            val args = arrayOf("-a:1", "-b:2", "-c:3")

            val result = testSubCmd.testParseFlags(args)

            assertEquals("1", result["a"])
            assertEquals("2", result["b"])
            assertEquals("3", result["c"])
        }

        @Test
        @DisplayName("Ignores non-flag arguments")
        fun testIgnoresNonFlags() {
            val args = arrayOf("command", "-flag:value", "other")

            val result = testSubCmd.testParseFlags(args)

            assertEquals(1, result.size)
            assertEquals("value", result["flag"])
        }

        @Test
        @DisplayName("Handles colons in value")
        fun testColonInValue() {
            val args = arrayOf("-url:http://example.com:8080")

            val result = testSubCmd.testParseFlags(args)

            assertEquals("http://example.com:8080", result["url"])
        }

        @Test
        @DisplayName("Handles empty value")
        fun testEmptyValue() {
            val args = arrayOf("-empty:")

            val result = testSubCmd.testParseFlags(args)

            assertEquals("", result["empty"])
        }

        @Test
        @DisplayName("Ignores flags without colon")
        fun testFlagWithoutColon() {
            val args = arrayOf("-novalue")

            val result = testSubCmd.testParseFlags(args)

            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("Converts key to lowercase")
        fun testKeyToLowercase() {
            val args = arrayOf("-KEY:value")

            val result = testSubCmd.testParseFlags(args)

            assertEquals("value", result["key"])
            assertNull(result["KEY"])
        }
    }

    // ==================== getNonFlagArgs Extension ====================

    @Nested
    @DisplayName("SubCommand.getNonFlagArgs()")
    inner class GetNonFlagArgsTests {

        private val testSubCmd = object : SubCommand {
            override val configKey = "test"
            override fun execute(sender: CommandSender, args: Array<String>) = true

            fun testGetNonFlagArgs(args: Array<String>) = getNonFlagArgs(args)
        }

        @Test
        @DisplayName("Returns all args when no flags")
        fun testNoFlags() {
            val args = arrayOf("a", "b", "c")

            val result = testSubCmd.testGetNonFlagArgs(args)

            assertEquals(listOf("a", "b", "c"), result)
        }

        @Test
        @DisplayName("Filters out flags")
        fun testFiltersFlags() {
            val args = arrayOf("command", "-flag:value", "arg1", "-other:test", "arg2")

            val result = testSubCmd.testGetNonFlagArgs(args)

            assertEquals(listOf("command", "arg1", "arg2"), result)
        }

        @Test
        @DisplayName("Returns empty list when all flags")
        fun testAllFlags() {
            val args = arrayOf("-a:1", "-b:2")

            val result = testSubCmd.testGetNonFlagArgs(args)

            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("Handles empty array")
        fun testEmptyArray() {
            val args = emptyArray<String>()

            val result = testSubCmd.testGetNonFlagArgs(args)

            assertTrue(result.isEmpty())
        }
    }

    // ==================== checkPermission Extension ====================

    @Nested
    @DisplayName("CommandSender.checkPermission()")
    inner class CheckPermissionTests {

        @Test
        @DisplayName("Returns true for null permission")
        fun testNullPermission() {
            val result = player.checkPermission(null)

            assertTrue(result)
        }

        @Test
        @DisplayName("Returns true when player has permission")
        fun testHasPermission() {
            player.addAttachment(plugin, "test.permission", true)

            val result = player.checkPermission("test.permission")

            assertTrue(result)
        }

        @Test
        @DisplayName("Returns false when player lacks permission")
        fun testLacksPermission() {
            assertFalse(player.hasPermission("test.permission"))

            val result = player.checkPermission("test.permission")

            assertFalse(result)
        }
    }

    // ==================== player Extension ====================

    @Nested
    @DisplayName("CommandSender.player")
    inner class PlayerExtensionTests {

        @Test
        @DisplayName("Returns player when sender is Player")
        fun testPlayerSender() {
            val result = player.player

            assertNotNull(result)
            assertEquals(player, result)
        }

        @Test
        @DisplayName("Returns null for console sender")
        fun testConsoleSender() {
            val console = server.consoleSender

            val result = console.player

            assertNull(result)
        }
    }

    // ==================== onlinePlayerNames ====================

    @Nested
    @DisplayName("onlinePlayerNames()")
    inner class OnlinePlayerNamesTests {

        @Test
        @DisplayName("Returns list of online player names")
        fun testReturnsPlayerNames() {
            server.addPlayer("Alice")
            server.addPlayer("Bob")

            val result = onlinePlayerNames()

            assertTrue(result.contains("TestPlayer"))
            assertTrue(result.contains("Alice"))
            assertTrue(result.contains("Bob"))
        }

        @Test
        @DisplayName("Returns sorted list")
        fun testReturnsSortedList() {
            server.addPlayer("Zara")
            server.addPlayer("Alice")

            val result = onlinePlayerNames()

            val sortedResult = result.sorted()
            assertEquals(sortedResult, result)
        }
    }

    // ==================== tabCompletePlayers ====================

    @Nested
    @DisplayName("tabCompletePlayers()")
    inner class TabCompletePlayersTests {

        @Test
        @DisplayName("Filters players by prefix")
        fun testFiltersByPrefix() {
            server.addPlayer("Alice")
            server.addPlayer("Alex")
            server.addPlayer("Bob")

            val result = tabCompletePlayers("Al")

            assertTrue(result.contains("Alice"))
            assertTrue(result.contains("Alex"))
            assertFalse(result.contains("Bob"))
        }

        @Test
        @DisplayName("Returns all when empty prefix")
        fun testEmptyPrefix() {
            server.addPlayer("Alice")
            server.addPlayer("Bob")

            val result = tabCompletePlayers("")

            assertTrue(result.size >= 3) // TestPlayer + Alice + Bob
        }
    }
}

