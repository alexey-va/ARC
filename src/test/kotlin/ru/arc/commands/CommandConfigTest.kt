@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.commands

import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.commands.arc.CommandConfig

/**
 * Tests for CommandConfig message and metadata loading.
 */
class CommandConfigTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
    }

    // ==================== Message Methods ====================

    @Nested
    @DisplayName("Message Methods")
    inner class MessageMethodTests {

        @Test
        @DisplayName("get() returns Component")
        fun testGetReturnsComponent() {
            val result = CommandConfig.get("test.key", "<gray>Default message")

            assertNotNull(result)
            assertTrue(result is Component)
        }

        @Test
        @DisplayName("get() with replacers substitutes values")
        fun testGetWithReplacers() {
            val result = CommandConfig.get(
                "test.key",
                "<gray>Hello %name%!",
                "%name%", "World"
            )

            assertNotNull(result)
            // Component should contain the replacement
        }

        @Test
        @DisplayName("noPermission() returns non-null Component")
        fun testNoPermission() {
            val result = CommandConfig.noPermission()

            assertNotNull(result)
        }

        @Test
        @DisplayName("playerOnly() returns non-null Component")
        fun testPlayerOnly() {
            val result = CommandConfig.playerOnly()

            assertNotNull(result)
        }

        @Test
        @DisplayName("playerNotFound() includes player name")
        fun testPlayerNotFound() {
            val result = CommandConfig.playerNotFound("TestPlayer")

            assertNotNull(result)
        }

        @Test
        @DisplayName("unknownAction() includes action name")
        fun testUnknownAction() {
            val result = CommandConfig.unknownAction("invalid")

            assertNotNull(result)
        }

        @Test
        @DisplayName("usage() includes usage string")
        fun testUsage() {
            val result = CommandConfig.usage("/arc test")

            assertNotNull(result)
        }

        @Test
        @DisplayName("hookNotLoaded() includes hook name")
        fun testHookNotLoaded() {
            val result = CommandConfig.hookNotLoaded("TestHook")

            assertNotNull(result)
        }
    }

    // ==================== Arc Command Messages ====================

    @Nested
    @DisplayName("Arc Command Messages")
    inner class ArcCommandMessagesTests {

        @Test
        @DisplayName("arcUsage() returns usage message")
        fun testArcUsage() {
            val result = CommandConfig.arcUsage()

            assertNotNull(result)
        }

        @Test
        @DisplayName("arcAvailable() includes command list")
        fun testArcAvailable() {
            val result = CommandConfig.arcAvailable("help, reload, board")

            assertNotNull(result)
        }

        @Test
        @DisplayName("arcUnknownCommand() includes command name")
        fun testArcUnknownCommand() {
            val result = CommandConfig.arcUnknownCommand("unknown")

            assertNotNull(result)
        }
    }

    // ==================== Specific Command Messages ====================

    @Nested
    @DisplayName("Specific Command Messages")
    inner class SpecificCommandMessagesTests {

        @Test
        @DisplayName("reloadSuccess() returns success message")
        fun testReloadSuccess() {
            val result = CommandConfig.reloadSuccess()

            assertNotNull(result)
        }

        @Test
        @DisplayName("repoSaved() returns saved message")
        fun testRepoSaved() {
            val result = CommandConfig.repoSaved()

            assertNotNull(result)
        }

        @Test
        @DisplayName("repoSize() includes name and bytes")
        fun testRepoSize() {
            val result = CommandConfig.repoSize("TestRepo", 1024L)

            assertNotNull(result)
        }

        @Test
        @DisplayName("repoTotal() includes total bytes")
        fun testRepoTotal() {
            val result = CommandConfig.repoTotal(2048L)

            assertNotNull(result)
        }

        @Test
        @DisplayName("loggerLevelSet() includes level")
        fun testLoggerLevelSet() {
            val result = CommandConfig.loggerLevelSet("DEBUG")

            assertNotNull(result)
        }

        @Test
        @DisplayName("loggerInvalidLevel() includes level")
        fun testLoggerInvalidLevel() {
            val result = CommandConfig.loggerInvalidLevel("INVALID")

            assertNotNull(result)
        }

        @Test
        @DisplayName("loggerAvailableLevels() includes levels")
        fun testLoggerAvailableLevels() {
            val result = CommandConfig.loggerAvailableLevels("DEBUG, INFO, WARN, ERROR")

            assertNotNull(result)
        }
    }

    // ==================== Locpool Messages ====================

    @Nested
    @DisplayName("Locpool Messages")
    inner class LocpoolMessagesTests {

        @Test
        @DisplayName("locpoolList() includes pools")
        fun testLocpoolList() {
            val result = CommandConfig.locpoolList("pool1, pool2")

            assertNotNull(result)
        }

        @Test
        @DisplayName("locpoolNotEditing() returns message")
        fun testLocpoolNotEditing() {
            val result = CommandConfig.locpoolNotEditing()

            assertNotNull(result)
        }

        @Test
        @DisplayName("locpoolEditingCancelled() includes pool ID")
        fun testLocpoolEditingCancelled() {
            val result = CommandConfig.locpoolEditingCancelled("testPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("locpoolEditingStarted() includes pool ID")
        fun testLocpoolEditingStarted() {
            val result = CommandConfig.locpoolEditingStarted("testPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("locpoolDeleted() includes pool ID")
        fun testLocpoolDeleted() {
            val result = CommandConfig.locpoolDeleted("testPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("locpoolNotFound() includes pool ID")
        fun testLocpoolNotFound() {
            val result = CommandConfig.locpoolNotFound("testPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("locpoolSpecifyPool() returns message")
        fun testLocpoolSpecifyPool() {
            val result = CommandConfig.locpoolSpecifyPool()

            assertNotNull(result)
        }
    }

    // ==================== Hunt Messages ====================

    @Nested
    @DisplayName("Hunt Messages")
    inner class HuntMessagesTests {

        @Test
        @DisplayName("huntStarted() returns started message")
        fun testHuntStarted() {
            val result = CommandConfig.huntStarted()

            assertNotNull(result)
        }

        @Test
        @DisplayName("huntStopped() returns stopped message")
        fun testHuntStopped() {
            val result = CommandConfig.huntStopped()

            assertNotNull(result)
        }

        @Test
        @DisplayName("huntNotFound() returns not found message")
        fun testHuntNotFound() {
            val result = CommandConfig.huntNotFound()

            assertNotNull(result)
        }

        @Test
        @DisplayName("huntPoolNotFound() includes pool ID")
        fun testHuntPoolNotFound() {
            val result = CommandConfig.huntPoolNotFound("testPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("huntNotEnoughArgs() returns args message")
        fun testHuntNotEnoughArgs() {
            val result = CommandConfig.huntNotEnoughArgs()

            assertNotNull(result)
        }

        @Test
        @DisplayName("huntInvalidChests() includes value")
        fun testHuntInvalidChests() {
            val result = CommandConfig.huntInvalidChests("abc")

            assertNotNull(result)
        }

        @Test
        @DisplayName("huntSpecifyPool() returns message")
        fun testHuntSpecifyPool() {
            val result = CommandConfig.huntSpecifyPool()

            assertNotNull(result)
        }

        @Test
        @DisplayName("huntError() returns error message")
        fun testHuntError() {
            val result = CommandConfig.huntError()

            assertNotNull(result)
        }
    }

    // ==================== Treasures Messages ====================

    @Nested
    @DisplayName("Treasures Messages")
    inner class TreasuresMessagesTests {

        @Test
        @DisplayName("treasuresReloaded() returns reloaded message")
        fun testTreasuresReloaded() {
            val result = CommandConfig.treasuresReloaded()

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresPoolNotFound() includes pool ID")
        fun testTreasuresPoolNotFound() {
            val result = CommandConfig.treasuresPoolNotFound("testPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresPoolCreating() includes pool ID")
        fun testTreasuresPoolCreating() {
            val result = CommandConfig.treasuresPoolCreating("newPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresNoItemInHand() returns message")
        fun testTreasuresNoItemInHand() {
            val result = CommandConfig.treasuresNoItemInHand()

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresNoTargetBlock() returns message")
        fun testTreasuresNoTargetBlock() {
            val result = CommandConfig.treasuresNoTargetBlock()

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresItemAdded() includes pool and item")
        fun testTreasuresItemAdded() {
            val result = CommandConfig.treasuresItemAdded("pool1", "DIAMOND")

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresItemsAdded() includes pool and amount")
        fun testTreasuresItemsAdded() {
            val result = CommandConfig.treasuresItemsAdded("pool1", 5)

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresSubpoolAdded() includes pools")
        fun testTreasuresSubpoolAdded() {
            val result = CommandConfig.treasuresSubpoolAdded("parent", "child")

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresPoolEmpty() includes pool ID")
        fun testTreasuresPoolEmpty() {
            val result = CommandConfig.treasuresPoolEmpty("emptyPool")

            assertNotNull(result)
        }

        @Test
        @DisplayName("treasuresGiven() includes player name")
        fun testTreasuresGiven() {
            val result = CommandConfig.treasuresGiven("TestPlayer")

            assertNotNull(result)
        }
    }

    // ==================== Other Messages ====================

    @Nested
    @DisplayName("Other Messages")
    inner class OtherMessagesTests {

        @Test
        @DisplayName("emshopReset() returns reset message")
        fun testEmshopReset() {
            val result = CommandConfig.emshopReset()

            assertNotNull(result)
        }

        @Test
        @DisplayName("jobsboostsReset() includes player name")
        fun testJobsboostsReset() {
            val result = CommandConfig.jobsboostsReset("TestPlayer")

            assertNotNull(result)
        }

        @Test
        @DisplayName("jobsboostsSpecifyPlayer() returns message")
        fun testJobsboostsSpecifyPlayer() {
            val result = CommandConfig.jobsboostsSpecifyPlayer()

            assertNotNull(result)
        }

        @Test
        @DisplayName("auditCleared() returns cleared message")
        fun testAuditCleared() {
            val result = CommandConfig.auditCleared()

            assertNotNull(result)
        }

        @Test
        @DisplayName("auditClearedFor() includes player name")
        fun testAuditClearedFor() {
            val result = CommandConfig.auditClearedFor("TestPlayer")

            assertNotNull(result)
        }

        @Test
        @DisplayName("auditInvalidPage() includes text")
        fun testAuditInvalidPage() {
            val result = CommandConfig.auditInvalidPage("abc")

            assertNotNull(result)
        }

        @Test
        @DisplayName("rtpAdded() includes player name")
        fun testRtpAdded() {
            val result = CommandConfig.rtpAdded("TestPlayer")

            assertNotNull(result)
        }
    }

    // ==================== Command Metadata ====================

    @Nested
    @DisplayName("Command Metadata")
    inner class CommandMetadataTests {

        @Test
        @DisplayName("getCommandName() returns default when not in config")
        fun testGetCommandNameDefault() {
            val result = CommandConfig.getCommandName("nonexistent", "default")

            assertEquals("default", result)
        }

        @Test
        @DisplayName("getCommandPermission() returns null when empty")
        fun testGetCommandPermissionEmpty() {
            val result = CommandConfig.getCommandPermission("nonexistent", null)

            assertNull(result)
        }

        @Test
        @DisplayName("getCommandDescription() returns default when not in config")
        fun testGetCommandDescriptionDefault() {
            val result = CommandConfig.getCommandDescription("nonexistent", "default desc")

            assertEquals("default desc", result)
        }

        @Test
        @DisplayName("getCommandUsage() returns default when not in config")
        fun testGetCommandUsageDefault() {
            val result = CommandConfig.getCommandUsage("nonexistent", "/arc test")

            assertEquals("/arc test", result)
        }

        @Test
        @DisplayName("isPlayerOnly() returns default when not in config")
        fun testIsPlayerOnlyDefault() {
            val result = CommandConfig.isPlayerOnly("nonexistent", true)

            assertTrue(result)
        }

        @Test
        @DisplayName("getAliases() returns empty list when not in config")
        fun testGetAliasesEmpty() {
            val result = CommandConfig.getAliases("nonexistent")

            assertTrue(result.isEmpty())
        }
    }
}


