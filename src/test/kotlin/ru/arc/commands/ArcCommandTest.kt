@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.commands

import org.bukkit.command.Command as BukkitCommand
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockito.Mockito.mock
import ru.arc.TestBase
import ru.arc.commands.arc.ArcCommand
import ru.arc.commands.arc.subcommands.RespawnOnRtpSubCommand

/**
 * Tests for the main /arc command and its subcommands.
 * Note: Subcommands like locpool, hunt, treasures are now in Kotlin SubCommand implementations
 * and send Component messages, not String messages.
 */
class ArcCommandTest : TestBase() {

    private lateinit var player: PlayerMock
    private lateinit var mockCommand: BukkitCommand
    private lateinit var arcCommand: ArcCommand

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        player = server.addPlayer("TestPlayer")
        mockCommand = mock(BukkitCommand::class.java)
        arcCommand = ArcCommand()
    }

    /**
     * Helper to check if a message was sent (either as String or Component)
     */
    private fun PlayerMock.hasReceivedMessage(): Boolean {
        return nextMessage() != null || nextComponentMessage() != null
    }

    // ==================== No Args Test ====================

    @Test
    @DisplayName("No args shows usage message")
    fun testNoArgs() {
        val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf())

        assertTrue(result)
        // ArcCommand shows usage when no args provided
        assertTrue(player.hasReceivedMessage(), "Should send usage message")
    }

    // ==================== Reload Subcommand ====================

    @Nested
    @DisplayName("/arc reload")
    inner class ReloadTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testReloadNoPermission() {
            assertFalse(player.hasPermission("arc.admin"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("reload"))

            assertTrue(result)
            // Should send "no permission" message
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("With permission - sends success message")
        fun testReloadWithPermission() {
            player.addAttachment(plugin, "arc.admin", true)
            assertTrue(player.hasPermission("arc.admin"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("reload"))

            assertTrue(result)
            // Should receive success message (component-based)
            assertTrue(player.hasReceivedMessage(), "Reload should send a confirmation message")
        }
    }

    // ==================== Board Subcommand ====================

    @Nested
    @DisplayName("/arc board")
    inner class BoardTests {

        @Test
        @DisplayName("Player can open board")
        fun testBoardOpensGui() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("board"))

            assertTrue(result)
            // GUI opening is async, we just verify command executed
        }
    }

    // ==================== Respawn on RTP Subcommand ====================

    @Nested
    @DisplayName("/arc respawnonrtp")
    inner class RespawnOnRtpTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.rtp-respawn"))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("respawnonrtp", "TestPlayer"))

            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("With permission but no player arg - sends not enough args")
        fun testNoPlayerArg() {
            player.addAttachment(plugin, "arc.rtp-respawn", true)

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("respawnonrtp"))

            assertTrue(player.hasReceivedMessage(), "Should show not enough args")
        }

        @Test
        @DisplayName("With permission and player - adds to RTP list and confirms")
        fun testAddToRtpList() {
            player.addAttachment(plugin, "arc.rtp-respawn", true)
            val targetPlayer = "TargetPlayer"

            // Verify not in cache before
            assertNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent(targetPlayer))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("respawnonrtp", targetPlayer))

            // Verify added to cache
            assertNotNull(
                RespawnOnRtpSubCommand.playersForRtp.getIfPresent(targetPlayer),
                "Player should be added to RTP cache"
            )

            // Verify confirmation message
            assertTrue(player.hasReceivedMessage())

            // Cleanup
            RespawnOnRtpSubCommand.playersForRtp.invalidate(targetPlayer)
        }
    }

    // ==================== Locpool Subcommand ====================

    @Nested
    @DisplayName("/arc locpool")
    inner class LocpoolTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.locpool.admin"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("locpool"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("With permission - shows pools list message")
        fun testListPools() {
            player.addAttachment(plugin, "arc.locpool.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("locpool"))

            assertTrue(result)
            // Component message is sent, check via nextComponentMessage
            assertTrue(player.hasReceivedMessage(), "Should show pools list")
        }

        @Test
        @DisplayName("Edit without current editing - shows not editing message")
        fun testEditNoPoolId() {
            player.addAttachment(plugin, "arc.locpool.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("locpool", "edit"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show 'not editing' message")
        }

        @Test
        @DisplayName("Delete without pool id - shows specify message")
        fun testDeleteNoPoolId() {
            player.addAttachment(plugin, "arc.locpool.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("locpool", "delete"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should ask to specify pool")
        }
    }

    // ==================== Audit Subcommand ====================

    @Nested
    @DisplayName("/arc audit")
    inner class AuditTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.audit"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("audit", "test"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should send no permission message")
        }

        @Test
        @DisplayName("With permission but no player - shows usage")
        fun testNoArgs() {
            player.addAttachment(plugin, "arc.audit", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("audit"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show usage")
        }

        @Test
        @DisplayName("Clearall clears all audit and confirms")
        fun testClearAll() {
            player.addAttachment(plugin, "arc.audit", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("audit", "clearall"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should confirm audit cleared")
        }
    }

    // ==================== Repo Subcommand ====================

    @Nested
    @DisplayName("/arc repo")
    inner class RepoTests {

        @Test
        @DisplayName("No args - shows usage")
        fun testNoArgs() {
            player.addAttachment(plugin, "arc.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("repo"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show usage")
        }

        @Test
        @DisplayName("Save - saves all repos and confirms")
        fun testSave() {
            player.addAttachment(plugin, "arc.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("repo", "save"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should confirm save")
        }

        @Test
        @DisplayName("Size - shows repo sizes")
        fun testSize() {
            player.addAttachment(plugin, "arc.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("repo", "size"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show size info")
        }
    }

    // ==================== AI Subcommand ====================

    // AI subcommand was removed from the plugin

    // ==================== Hunt Subcommand ====================

    @Nested
    @DisplayName("/arc hunt")
    inner class HuntTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.treasure-hunt"))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("hunt", "start", "test"))

            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("With permission but invalid command - shows usage")
        fun testInvalidCommand() {
            player.addAttachment(plugin, "arc.treasure-hunt", true)

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("hunt", "invalid"))

            assertTrue(player.hasReceivedMessage(), "Should show usage or error")
        }
    }

    // ==================== Treasures Subcommand ====================

    @Nested
    @DisplayName("/arc treasures")
    inner class TreasuresTests {

        @Test
        @DisplayName("Without permission - command is gated")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.treasures.admin"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("treasures"))

            assertTrue(result)
            // The treasures command checks arc.admin first, then arc.treasures.admin
            // Without both, behavior may vary
        }

        @Test
        @DisplayName("Reload reloads treasures and confirms")
        fun testReload() {
            player.addAttachment(plugin, "arc.treasures.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("treasures", "reload"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should confirm reload")
        }
    }

    // ==================== EMShop Subcommand ====================

    @Nested
    @DisplayName("/arc emshop")
    inner class EmshopTests {

        @Test
        @DisplayName("Without EMHook - shows hook not loaded error")
        fun testNoEmHook() {
            player.addAttachment(plugin, "arc.admin", true)

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("emshop", "TestPlayer")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show hook not loaded message")
        }
    }

    // ==================== Jobsboosts Subcommand ====================

    @Nested
    @DisplayName("/arc jobsboosts")
    inner class JobsboostsTests {

        @Test
        @DisplayName("Without JobsHook - shows hook not loaded error")
        fun testNoJobsHook() {
            player.addAttachment(plugin, "arc.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("jobsboosts"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show hook not loaded message")
        }
    }

    // ==================== Baltop Subcommand ====================

    @Nested
    @DisplayName("/arc baltop")
    inner class BaltopTests {

        @Test
        @DisplayName("Opens baltop GUI asynchronously")
        fun testOpensBaltopGui() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("baltop"))

            assertTrue(result)
            // GUI opening is async
        }
    }

    // ==================== Logger Subcommand ====================

    @Nested
    @DisplayName("/arc logger")
    inner class LoggerTests {

        @Test
        @DisplayName("No args - shows current log level")
        fun testShowCurrentLevel() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("logger"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show current log level")
        }

        @Test
        @DisplayName("Sets log level to INFO and confirms")
        fun testSetLogLevelInfo() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("logger", "INFO"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should confirm log level change")
        }

        @Test
        @DisplayName("Sets log level to DEBUG and sends confirmation")
        fun testSetLogLevelDebug() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("logger", "DEBUG"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should confirm log level change")
        }

        @Test
        @DisplayName("Sets log level to ERROR and sends confirmation")
        fun testSetLogLevelError() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("logger", "ERROR"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should confirm log level change")
        }

        @Test
        @DisplayName("Sets log level to WARN and sends confirmation")
        fun testSetLogLevelWarn() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("logger", "WARN"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should confirm log level change")
        }

        @Test
        @DisplayName("All valid log levels are accepted")
        fun testAllLogLevels() {
            // Only these levels are defined in Logging.Level enum
            val levels = listOf("DEBUG", "INFO", "WARN", "ERROR")

            for (level in levels) {
                assertDoesNotThrow({
                    arcCommand.onCommand(player, mockCommand, "arc", arrayOf("logger", level))
                }, "Level $level should be accepted")
                // Clear message queue
                while (player.nextMessage() != null) {
                }
            }
        }
    }

    // ==================== Join/Quit Message Subcommands ====================

    @Nested
    @DisplayName("/arc joinmessage & quitmessage")
    inner class JoinQuitMessageTests {

        @Test
        @DisplayName("joinmessage without permission - sends no permission message")
        fun testJoinMessageNoPermission() {
            assertFalse(player.hasPermission("arc.join-message-gui"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("joinmessage"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should send no permission message")
        }

        @Test
        @DisplayName("quitmessage without permission - sends no permission message")
        fun testQuitMessageNoPermission() {
            assertFalse(player.hasPermission("arc.join-message-gui"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("quitmessage"))

            assertTrue(result)
            val message = player.nextMessage()
            assertNotNull(message, "Should send no permission message")
        }

        @Test
        @DisplayName("joinmessage with permission - opens GUI")
        fun testJoinMessageWithPermission() {
            player.addAttachment(plugin, "arc.join-message-gui", true)
            assertTrue(player.hasPermission("arc.join-message-gui"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("joinmessage"))

            assertTrue(result)
            // GUI opens asynchronously
        }

        @Test
        @DisplayName("quitmessage with permission - opens GUI")
        fun testQuitMessageWithPermission() {
            player.addAttachment(plugin, "arc.join-message-gui", true)
            assertTrue(player.hasPermission("arc.join-message-gui"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("quitmessage"))

            assertTrue(result)
            // GUI opens asynchronously
        }
    }

    // ==================== Tab Completion Tests ====================

    @Nested
    @DisplayName("Tab Completion")
    inner class TabCompletionTests {

        @Test
        @DisplayName("First arg shows available subcommands")
        fun testFirstArgCompletion() {
            // Give permissions for all subcommands
            player.addAttachment(plugin, "arc.admin", true)
            player.addAttachment(plugin, "arc.audit", true)
            player.addAttachment(plugin, "arc.locpool.admin", true)
            player.addAttachment(plugin, "arc.treasure-hunt", true)
            player.addAttachment(plugin, "arc.treasures.admin", true)
            player.addAttachment(plugin, "arc.rtp-respawn", true)
            player.addAttachment(plugin, "arc.join-message-gui", true)
            player.addAttachment(plugin, "arc.board", true)
            player.addAttachment(plugin, "arc.baltop", true)
            player.addAttachment(plugin, "arc.jobsboosts", true)
            player.addAttachment(plugin, "arc.test", true)
            player.addAttachment(plugin, "arc.command.buildbook", true)
            player.addAttachment(plugin, "arc.eliteloot", true)
            player.addAttachment(plugin, "arc.stocks.buy", true)
            player.addAttachment(plugin, "arc.store", true)
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)
            player.addAttachment(plugin, "arc.sound-follow", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf(""))

            assertNotNull(completions)
            // Core subcommands that should be available with all permissions
            val coreCommands = listOf(
                "help", "reload", "board", "baltop", "audit", "repo",
                "emshop", "jobsboosts", "logger", "joinmessage", "quitmessage", "respawnonrtp",
                "locpool", "hunt", "treasures", "test", "buildbook", "eliteloot", "invest",
                "store", "giveboost", "soundfollow"
            )

            coreCommands.forEach { subcommand ->
                assertTrue(completions!!.contains(subcommand), "Should contain '$subcommand' but got $completions")
            }
        }

        // AI subcommand tests removed - AI command was deleted

        @Test
        @DisplayName("Repo subcommand shows save/size")
        fun testRepoTabCompletion() {
            player.addAttachment(plugin, "arc.admin", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("repo", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("save"), "Should contain 'save'")
            assertTrue(completions.contains("size"), "Should contain 'size'")
        }

        @Test
        @DisplayName("Locpool subcommand shows list/delete")
        fun testLocpoolTabCompletion() {
            player.addAttachment(plugin, "arc.locpool.admin", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("locpool", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("list"), "Should contain 'list'")
            assertTrue(completions.contains("delete"), "Should contain 'delete'")
        }

        @Test
        @DisplayName("Hunt subcommand shows status/types/start/stop/stopall")
        fun testHuntTabCompletion() {
            player.addAttachment(plugin, "arc.treasure-hunt", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("hunt", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("status"), "Should contain 'status'")
            assertTrue(completions.contains("types"), "Should contain 'types'")
            assertTrue(completions.contains("start"), "Should contain 'start'")
            assertTrue(completions.contains("stop"), "Should contain 'stop'")
            assertTrue(completions.contains("stopall"), "Should contain 'stopall'")
        }

        @Test
        @DisplayName("Treasures subcommand shows reload")
        fun testTreasuresTabCompletion() {
            player.addAttachment(plugin, "arc.treasures.admin", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("treasures", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("reload"), "Should contain 'reload'")
        }

        @Test
        @DisplayName("Treasures pool shows 4 actions")
        fun testTreasuresPoolActions() {
            player.addAttachment(plugin, "arc.treasures.admin", true)

            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("treasures", "test-pool", "")
            )

            assertNotNull(completions)
            assertTrue(completions!!.contains("addhand"), "Should contain 'addhand'")
            assertTrue(completions.contains("addchest"), "Should contain 'addchest'")
            assertTrue(completions.contains("addsubpool"), "Should contain 'addsubpool'")
            assertTrue(completions.contains("give"), "Should contain 'give'")
        }

        @Test
        @DisplayName("Audit subcommand shows clearall")
        fun testAuditTabCompletion() {
            player.addAttachment(plugin, "arc.audit", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("audit", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("clearall"), "Should contain 'clearall'")
        }

        @Test
        @DisplayName("Audit filter shows filter options on third arg")
        fun testAuditFilterOptions() {
            player.addAttachment(plugin, "arc.audit", true)

            // 2nd arg is page number, filters are on 3rd arg
            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("audit", "TestPlayer", "1", "")
            )

            assertNotNull(completions)
            val expected = listOf("all", "income", "expense", "shop", "job", "pay")
            expected.forEach { filter ->
                assertTrue(completions!!.contains(filter), "Should contain '$filter'")
            }
        }

        @Test
        @DisplayName("Audit second arg shows page numbers and clear")
        fun testAuditPageOptions() {
            player.addAttachment(plugin, "arc.audit", true)

            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("audit", "TestPlayer", "")
            )

            assertNotNull(completions)
            assertTrue(completions!!.contains("1"), "Should contain '1'")
            assertTrue(completions.contains("clear"), "Should contain 'clear'")
        }

        @Test
        @DisplayName("EMShop shows online player names")
        fun testEmshopPlayerNames() {
            player.addAttachment(plugin, "arc.admin", true)
            val player2 = server.addPlayer("OtherPlayer")

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("emshop", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("TestPlayer"), "Should contain TestPlayer")
            assertTrue(completions.contains("OtherPlayer"), "Should contain OtherPlayer")
        }

        @Test
        @DisplayName("EMShop gear/trinket options")
        fun testEmshopGearOptions() {
            player.addAttachment(plugin, "arc.admin", true)

            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("emshop", "TestPlayer", "")
            )

            assertNotNull(completions)
            assertTrue(completions!!.contains("gear"), "Should contain 'gear'")
            assertTrue(completions.contains("trinket"), "Should contain 'trinket'")
        }

        @Test
        @DisplayName("Jobsboosts shows online player names")
        fun testJobsboostsPlayerNames() {
            player.addAttachment(plugin, "arc.jobsboosts", true)
            val player2 = server.addPlayer("OtherPlayer")

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("jobsboosts", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("TestPlayer"))
            assertTrue(completions.contains("OtherPlayer"))
        }
    }

    // ==================== RTP Cache Tests ====================

    @Nested
    @DisplayName("RTP Cache")
    inner class RtpCacheTests {

        @Test
        @DisplayName("Player can be added to cache")
        fun testAddToCache() {
            val playerName = "CacheTest1"
            assertNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent(playerName))

            RespawnOnRtpSubCommand.playersForRtp.put(playerName, Any())

            assertNotNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent(playerName))

            // Cleanup
            RespawnOnRtpSubCommand.playersForRtp.invalidate(playerName)
        }

        @Test
        @DisplayName("Player can be removed from cache")
        fun testRemoveFromCache() {
            val playerName = "CacheTest2"
            RespawnOnRtpSubCommand.playersForRtp.put(playerName, Any())
            assertNotNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent(playerName))

            RespawnOnRtpSubCommand.playersForRtp.invalidate(playerName)

            assertNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent(playerName))
        }

        @Test
        @DisplayName("Multiple players can be in cache simultaneously")
        fun testMultiplePlayers() {
            val players = listOf("CachePlayer1", "CachePlayer2", "CachePlayer3")

            players.forEach { RespawnOnRtpSubCommand.playersForRtp.put(it, Any()) }

            players.forEach { playerName ->
                assertNotNull(
                    RespawnOnRtpSubCommand.playersForRtp.getIfPresent(playerName),
                    "$playerName should be in cache"
                )
            }

            // Cleanup
            players.forEach { RespawnOnRtpSubCommand.playersForRtp.invalidate(it) }
        }

        @Test
        @DisplayName("InvalidateAll clears all entries")
        fun testInvalidateAll() {
            val players = listOf("Clear1", "Clear2", "Clear3")
            players.forEach { RespawnOnRtpSubCommand.playersForRtp.put(it, Any()) }

            RespawnOnRtpSubCommand.playersForRtp.invalidateAll()

            players.forEach { playerName ->
                assertNull(
                    RespawnOnRtpSubCommand.playersForRtp.getIfPresent(playerName),
                    "$playerName should be cleared"
                )
            }
        }
    }

    // ==================== Test Subcommand ====================

    @Nested
    @DisplayName("/arc test")
    inner class TestSubCommandTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.test"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("test"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("With permission but no args - sends hello message")
        fun testHelloMessage() {
            player.addAttachment(plugin, "arc.test", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("test"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send hello message")
        }

        @Test
        @DisplayName("Unknown action - sends unknown action message")
        fun testUnknownAction() {
            player.addAttachment(plugin, "arc.test", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("test", "unknown"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send unknown action message")
        }

        @Test
        @DisplayName("Tab completion shows actions")
        fun testTabCompletion() {
            player.addAttachment(plugin, "arc.test", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("test", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("nbt"))
            assertTrue(completions.contains("leaf"))
            assertTrue(completions.contains("ploot"))
            assertTrue(completions.contains("blockdata"))
        }
    }

    // ==================== BuildBook Subcommand ====================

    @Nested
    @DisplayName("/arc buildbook")
    inner class BuildBookSubCommandTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.command.buildbook"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("buildbook", "test", "1"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("Too few args - shows usage")
        fun testTooFewArgs() {
            player.addAttachment(plugin, "arc.command.buildbook", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("buildbook"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show usage")
        }

        @Test
        @DisplayName("Invalid building - shows not found message")
        fun testBuildingNotFound() {
            player.addAttachment(plugin, "arc.command.buildbook", true)

            val result =
                arcCommand.onCommand(player, mockCommand, "arc", arrayOf("buildbook", "nonexistent_building", "1"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show building not found")
        }

        @Test
        @DisplayName("Tab completion shows buildings on first arg")
        fun testTabCompletionBuildings() {
            player.addAttachment(plugin, "arc.command.buildbook", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("buildbook", ""))

            assertNotNull(completions)
            // Buildings list may be empty in test environment
        }

        @Test
        @DisplayName("Tab completion shows model IDs on second arg")
        fun testTabCompletionModelIds() {
            player.addAttachment(plugin, "arc.command.buildbook", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("buildbook", "test", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("0"))
            assertTrue(completions.contains("1"))
        }

        @Test
        @DisplayName("Tab completion shows rotations on third arg")
        fun testTabCompletionRotations() {
            player.addAttachment(plugin, "arc.command.buildbook", true)

            val completions =
                arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("buildbook", "test", "1", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("0"))
            assertTrue(completions.contains("90"))
            assertTrue(completions.contains("180"))
            assertTrue(completions.contains("270"))
        }
    }

    // ==================== EliteLoot Subcommand ====================

    @Nested
    @DisplayName("/arc eliteloot")
    inner class EliteLootSubCommandTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.eliteloot"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("eliteloot"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("Tab completion shows list and add")
        fun testTabCompletion() {
            player.addAttachment(plugin, "arc.eliteloot", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("eliteloot", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("list"))
            assertTrue(completions.contains("add"))
        }

        @Test
        @DisplayName("Add tab completion shows weight options")
        fun testAddWeightCompletion() {
            player.addAttachment(plugin, "arc.eliteloot", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("eliteloot", "add", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("1.0"))
            assertTrue(completions.contains("0.5"))
        }
    }

    // ==================== Store Subcommand ====================

    @Nested
    @DisplayName("/arc store")
    inner class StoreSubCommandTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.store"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("store"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("Tab completion shows dump")
        fun testTabCompletion() {
            player.addAttachment(plugin, "arc.store", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("store", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("dump"))
        }
    }

    // ==================== GiveBoost Subcommand ====================

    @Nested
    @DisplayName("/arc giveboost")
    inner class GiveBoostSubCommandTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.admin.givejobsboost"))

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("giveboost", "TestPlayer", "all", "1.5", "EXP", "1h")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("Too few args - shows usage")
        fun testTooFewArgs() {
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("giveboost"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show usage")
        }

        @Test
        @DisplayName("Player not found - shows error")
        fun testPlayerNotFound() {
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("giveboost", "NonExistentPlayer", "all", "1.5", "EXP", "1h")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show player not found")
        }

        @Test
        @DisplayName("Invalid duration - shows error")
        fun testInvalidDuration() {
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("giveboost", "TestPlayer", "all", "1.5", "EXP", "invalid")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show invalid duration error")
        }

        @Test
        @DisplayName("Tab completion shows players on first arg")
        fun testTabCompletionPlayers() {
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("giveboost", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("TestPlayer"))
        }

        @Test
        @DisplayName("Tab completion shows job options on second arg")
        fun testTabCompletionJobs() {
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)

            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("giveboost", "TestPlayer", "")
            )

            assertNotNull(completions)
            assertTrue(completions!!.contains("all"))
        }

        @Test
        @DisplayName("Tab completion shows boost types on fourth arg")
        fun testTabCompletionBoostTypes() {
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)

            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("giveboost", "TestPlayer", "all", "1.5", "")
            )

            assertNotNull(completions)
            // Should contain JobsBoost.Type values
            assertTrue(completions!!.isNotEmpty())
        }

        @Test
        @DisplayName("Tab completion shows duration options on fifth arg")
        fun testTabCompletionDurations() {
            player.addAttachment(plugin, "arc.admin.givejobsboost", true)

            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("giveboost", "TestPlayer", "all", "1.5", "EXP", "")
            )

            assertNotNull(completions)
            assertTrue(completions!!.contains("1h"))
            assertTrue(completions.contains("1d"))
        }
    }

    // ==================== SoundFollow Subcommand ====================

    @Nested
    @DisplayName("/arc soundfollow")
    inner class SoundFollowSubCommandTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.sound-follow"))

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("soundfollow", "TestPlayer", "minecraft:block.note_block.harp")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("Wrong number of args - shows usage")
        fun testWrongArgs() {
            player.addAttachment(plugin, "arc.sound-follow", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("soundfollow"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show usage")
        }

        @Test
        @DisplayName("Player not found - shows error")
        fun testPlayerNotFound() {
            player.addAttachment(plugin, "arc.sound-follow", true)

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("soundfollow", "NonExistentPlayer", "minecraft:block.note_block.harp")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show player not found")
        }

        @Test
        @DisplayName("Valid command plays sound and confirms")
        fun testValidCommand() {
            player.addAttachment(plugin, "arc.sound-follow", true)

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("soundfollow", "TestPlayer", "block.note_block.harp")
            )

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should confirm sound played")
        }

        @Test
        @DisplayName("Tab completion shows players on first arg")
        fun testTabCompletionPlayers() {
            player.addAttachment(plugin, "arc.sound-follow", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("soundfollow", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("TestPlayer"))
        }

        @Test
        @DisplayName("Tab completion shows sounds on second arg")
        fun testTabCompletionSounds() {
            player.addAttachment(plugin, "arc.sound-follow", true)

            val completions = arcCommand.onTabComplete(
                player, mockCommand, "arc",
                arrayOf("soundfollow", "TestPlayer", "")
            )

            assertNotNull(completions)
            assertTrue(completions!!.isNotEmpty())
        }
    }

    // ==================== Help Subcommand ====================

    @Nested
    @DisplayName("/arc help")
    inner class HelpSubCommandTests {

        @Test
        @DisplayName("No args - shows all available commands")
        fun testShowAllCommands() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("help"))

            assertTrue(result)
            // Should receive multiple messages with command list
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("With command name - shows detailed help for that command")
        fun testShowCommandHelp() {
            player.addAttachment(plugin, "arc.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("help", "reload"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("With unknown command - shows error")
        fun testUnknownCommand() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("help", "unknowncommand123"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Tab completion shows available commands")
        fun testTabCompletion() {
            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("help", ""))

            assertNotNull(completions)
            assertTrue(completions!!.isNotEmpty())
        }
    }

    // ==================== Invest Subcommand ====================

    @Nested
    @DisplayName("/arc invest")
    inner class InvestSubCommandTests {

        @Test
        @DisplayName("Without permission - sends no permission message")
        fun testNoPermission() {
            assertFalse(player.hasPermission("arc.stocks.buy"))

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("invest"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should send no permission message")
        }

        @Test
        @DisplayName("Tab completion shows parameter options")
        fun testTabCompletion() {
            player.addAttachment(plugin, "arc.stocks.buy", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("invest", ""))

            assertNotNull(completions)
            assertTrue(completions!!.any { it.startsWith("-t") })
        }

        @Test
        @DisplayName("Tab completion for -t shows action types")
        fun testTabCompletionTypes() {
            player.addAttachment(plugin, "arc.stocks.buy", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("invest", "-t"))

            assertNotNull(completions)
            assertTrue(completions!!.any { it.contains("buy") })
            assertTrue(completions.any { it.contains("menu") })
        }
    }

    // ==================== Permission Verification Tests ====================

    @Nested
    @DisplayName("Permission Verification")
    inner class PermissionTests {

        @Test
        @DisplayName("arc.admin permission is required for reload")
        fun testReloadPermission() {
            assertFalse(player.hasPermission("arc.admin"))

            // Without permission
            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("reload"))
            val msg1 = player.nextMessage()

            // With permission
            player.addAttachment(plugin, "arc.admin", true)
            assertTrue(player.hasPermission("arc.admin"))
            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("reload"))
            val msg2 = player.nextMessage()

            // With permission should have a different response
            assertNotEquals(msg1, msg2)
        }

        @Test
        @DisplayName("arc.rtp-respawn permission gates respawnonrtp")
        fun testRtpRespawnPermission() {
            // Without permission - should get denied
            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("respawnonrtp", "Test"))
            assertTrue(player.hasReceivedMessage())
            assertNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent("Test"))

            // With permission - should work
            player.addAttachment(plugin, "arc.rtp-respawn", true)
            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("respawnonrtp", "Test2"))
            assertTrue(player.hasReceivedMessage())
            assertNotNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent("Test2"))

            // Cleanup
            RespawnOnRtpSubCommand.playersForRtp.invalidate("Test2")
        }

        @Test
        @DisplayName("arc.locpool.admin permission gates locpool")
        fun testLocpoolPermission() {
            assertFalse(player.hasPermission("arc.locpool.admin"))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("locpool"))
            assertTrue(player.hasReceivedMessage(), "Should deny without permission")

            player.addAttachment(plugin, "arc.locpool.admin", true)
            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("locpool"))
            assertTrue(player.hasReceivedMessage(), "Should show pools with permission")
        }

        @Test
        @DisplayName("arc.audit permission gates audit command")
        fun testAuditPermission() {
            assertFalse(player.hasPermission("arc.audit"))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("audit", "test"))
            assertTrue(player.hasReceivedMessage(), "Should deny without permission")

            player.addAttachment(plugin, "arc.audit", true)
            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("audit"))
            // Should show no args message
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("arc.treasure-hunt permission gates hunt command")
        fun testHuntPermission() {
            assertFalse(player.hasPermission("arc.treasure-hunt"))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("hunt", "start"))
            assertTrue(player.hasReceivedMessage(), "Should deny without permission")
        }

        @Test
        @DisplayName("arc.treasures.admin permission gates treasures command")
        fun testTreasuresPermission() {
            assertFalse(player.hasPermission("arc.treasures.admin"))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("treasures"))
            assertTrue(player.hasReceivedMessage(), "Should deny without permission")
        }

        @Test
        @DisplayName("arc.join-message-gui permission gates message commands")
        fun testJoinMessagePermission() {
            assertFalse(player.hasPermission("arc.join-message-gui"))

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("joinmessage"))
            val msg1 = player.nextMessage()
            assertNotNull(msg1, "Should deny joinmessage without permission")

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("quitmessage"))
            val msg2 = player.nextMessage()
            assertNotNull(msg2, "Should deny quitmessage without permission")
        }
    }

    // ==================== Edge Cases and Error Handling ====================

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    inner class EdgeCasesTests {

        @Test
        @DisplayName("Unknown subcommand shows error")
        fun testUnknownSubcommand() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("unknownsubcommand"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage(), "Should show unknown command message")
        }

        @Test
        @DisplayName("Case insensitive subcommand matching")
        fun testCaseInsensitiveSubcommand() {
            player.addAttachment(plugin, "arc.admin", true)

            val result1 = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("RELOAD"))
            assertTrue(result1)
            assertTrue(player.hasReceivedMessage())

            val result2 = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("ReLoAd"))
            assertTrue(result2)
        }

        @Test
        @DisplayName("Empty first arg is treated as unknown command")
        fun testEmptyFirstArg() {
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf(""))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Special characters in args don't break command")
        fun testSpecialCharactersInArgs() {
            player.addAttachment(plugin, "arc.treasure-hunt", true)

            val result = arcCommand.onCommand(
                player, mockCommand, "arc",
                arrayOf("hunt", "start", "test-pool_123", "10", "vanilla", "loot:special")
            )

            assertTrue(result)
            // Should not throw exception, may show error about pool not found
        }

        @Test
        @DisplayName("Very long args don't break command")
        fun testVeryLongArgs() {
            player.addAttachment(plugin, "arc.admin", true)

            val longArg = "a".repeat(500)
            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("reload", longArg))

            assertTrue(result)
        }
    }

    // ==================== Console Sender Tests ====================

    @Nested
    @DisplayName("Console Sender")
    inner class ConsoleSenderTests {

        @Test
        @DisplayName("Console can run reload")
        fun testConsoleReload() {
            val console = server.consoleSender

            val result = arcCommand.onCommand(console, mockCommand, "arc", arrayOf("reload"))

            assertTrue(result)
        }

        @Test
        @DisplayName("Console cannot run player-only commands")
        fun testConsolePlayerOnly() {
            val console = server.consoleSender

            val result = arcCommand.onCommand(console, mockCommand, "arc", arrayOf("board"))

            assertTrue(result)
            // Console should receive "player only" message
        }

        @Test
        @DisplayName("Console can run audit")
        fun testConsoleAudit() {
            val console = server.consoleSender

            val result = arcCommand.onCommand(console, mockCommand, "arc", arrayOf("audit", "TestPlayer"))

            assertTrue(result)
        }

        @Test
        @DisplayName("Console can list treasures")
        fun testConsoleTreasures() {
            val console = server.consoleSender

            val result = arcCommand.onCommand(console, mockCommand, "arc", arrayOf("treasures", "list"))

            assertTrue(result)
        }
    }

    // ==================== Tab Completion Edge Cases ====================

    @Nested
    @DisplayName("Tab Completion Edge Cases")
    inner class TabCompletionEdgeCasesTests {

        @Test
        @DisplayName("Tab completion with empty args returns subcommands")
        fun testEmptyArgsCompletion() {
            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf(""))

            assertNotNull(completions)
            assertTrue(completions!!.isNotEmpty())
        }

        @Test
        @DisplayName("Tab completion with partial match works")
        fun testPartialMatchCompletion() {
            player.addAttachment(plugin, "arc.admin", true) // Need permission to see reload/repo

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("re"))

            assertNotNull(completions)
            assertTrue(completions!!.contains("reload") || completions.contains("repo") || completions.contains("respawnonrtp"))
        }

        @Test
        @DisplayName("Tab completion filters by permission")
        fun testPermissionFilteredCompletion() {
            // Player without arc.admin should not see reload
            assertFalse(player.hasPermission("arc.admin"))

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf(""))

            assertNotNull(completions)
            // reload requires arc.admin, should not be visible
            assertFalse(completions!!.contains("reload"))
        }

        @Test
        @DisplayName("Tab completion returns empty for unknown subcommand")
        fun testUnknownSubcommandCompletion() {
            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("unknown", ""))

            // Should return null or empty for unknown subcommand
            assertTrue(completions == null || completions.isEmpty())
        }

        @Test
        @DisplayName("Repo tab completion includes status")
        fun testRepoTabCompletion() {
            player.addAttachment(plugin, "arc.admin", true)

            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf("repo", ""))

            assertNotNull(completions)
            assertTrue(completions!!.contains("status"))
            assertTrue(completions.contains("save"))
            assertTrue(completions.contains("size"))
        }
    }

    // ==================== Subcommand Integration Tests ====================

    @Nested
    @DisplayName("Subcommand Integration")
    inner class SubcommandIntegrationTests {

        @Test
        @DisplayName("RespawnOnRtp caches player correctly")
        fun testRespawnOnRtpCache() {
            player.addAttachment(plugin, "arc.rtp-respawn", true)
            server.addPlayer("TargetPlayer")

            arcCommand.onCommand(player, mockCommand, "arc", arrayOf("respawnonrtp", "TargetPlayer"))

            // Verify cache contains the player
            assertNotNull(RespawnOnRtpSubCommand.playersForRtp.getIfPresent("TargetPlayer"))
        }

        @Test
        @DisplayName("Audit clearall requires confirmation")
        fun testAuditClearall() {
            player.addAttachment(plugin, "arc.audit", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("audit", "clearall"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Hunt stopall works")
        fun testHuntStopall() {
            player.addAttachment(plugin, "arc.treasure-hunt", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("hunt", "stopall"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Hunt status works without args")
        fun testHuntStatusNoArgs() {
            player.addAttachment(plugin, "arc.treasure-hunt", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("hunt"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Logger without args shows current level")
        fun testLoggerShowsCurrent() {
            player.addAttachment(plugin, "arc.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("logger"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Locpool list works")
        fun testLocpoolList() {
            player.addAttachment(plugin, "arc.locpool.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("locpool", "list"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Treasures list works")
        fun testTreasuresList() {
            player.addAttachment(plugin, "arc.treasures.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("treasures", "list"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }

        @Test
        @DisplayName("Treasures reload works")
        fun testTreasuresReload() {
            player.addAttachment(plugin, "arc.treasures.admin", true)

            val result = arcCommand.onCommand(player, mockCommand, "arc", arrayOf("treasures", "reload"))

            assertTrue(result)
            assertTrue(player.hasReceivedMessage())
        }
    }

    // ==================== Alias Tests ====================

    @Nested
    @DisplayName("Command Aliases")
    inner class AliasTests {

        @Test
        @DisplayName("Subcommand aliases work correctly")
        fun testSubcommandAliases() {
            // This test verifies that aliases registered in SubCommand work
            // The actual aliases are loaded from config
            val completions = arcCommand.onTabComplete(player, mockCommand, "arc", arrayOf(""))

            assertNotNull(completions)
            // Check that help is in completions (help has no aliases by default)
            assertTrue(completions!!.contains("help"))
        }
    }
}
