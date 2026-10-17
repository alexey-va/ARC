@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Use Core's headless presenter with real history and dispatch.

package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.gui.ArcMenus
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogSessionRegistration
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Path

class HelpCenterHomeEntryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `home marker entry reopens after the default history footer ends the flow`() {
        assertRepeatedHomeEntry("back")
    }

    @Test
    fun `home marker entry reopens after the close preference ends the flow`() {
        assertRepeatedHomeEntry("close")
    }

    @Test
    fun `home publicity uses actor command dispatch once and reopens with default history`() {
        assertRepeatedHomeEntry("back", changeVisibility = true)
    }

    @Test
    fun `home publicity uses actor command dispatch once and reopens with close preference`() {
        assertRepeatedHomeEntry("close", changeVisibility = true)
    }

    private fun assertRepeatedHomeEntry(escapePreference: String, changeVisibility: Boolean = false) {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("HomeEntry")
            val player: Player = spyk(paper.addPlayer("HomeOwner"))
            every { player.isOnline } returns true
            player.addAttachment(plugin).also {
                it.setPermission("huskhomes.command.edithome", true)
                it.setPermission("huskhomes.command.edithome.privacy", true)
            }
            var closes = 0
            var isPublic = false
            val commands = mutableListOf<List<String>>()
            paper.server.commandMap.register("huskhomes", object : Command("edithome") {
                override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
                    assertSame(player, sender, "Home privacy must be changed as the owner, never as console")
                    assertEquals("huskhomes:edithome", label)
                    assertEquals(commands.size + 1, closes, "The dialog must close once before provider dispatch")
                    val target = if (isPublic) "private" else "public"
                    assertEquals(listOf("home", "privacy", target), args.toList())
                    commands += args.toList()
                    isPublic = target == "public"
                    return true
                }
            })
            // HuskHomes shades a pre-dialog Audience on the test compile path; the test runtime uses Paper's API.
            every { player invokeNoArgs "closeDialog" } answers { closes++; Unit }
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            val rendered = mutableListOf<PaperDialogScreen>()
            lateinit var registration: PaperDialogSessionRegistration
            val controllerField = HelpCenterModule.javaClass.getDeclaredField("controller").apply { isAccessible = true }
            val dialogField = ArcMenus.javaClass.getDeclaredField("dialogRuntime").apply { isAccessible = true }
            val previousController = controllerField.get(HelpCenterModule)
            val previousRuntime = dialogField.get(ArcMenus)
            val previousLuckPerms = HookRegistry.luckPermsHook

            ConfigManager.clear()
            Tasks.install(BukkitTaskScheduler(plugin))
            val inventoryReturn = HelpCenterInventoryReturnRuntime(plugin)
            val controller = HelpCenterController(
                settings = HelpCenterConfig.load(directory).snapshot(),
                gateway = BukkitHelpCenterGateway(),
                openLands = {},
                inventoryReturn = inventoryReturn,
                inviteToLand = { _, _ -> },
                navigation = HelpCenterNavigation(plugin, inventoryReturn::cancel),
                enchantmentsGuide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot(),
            )
            try {
                HookRegistry.luckPermsHook = mockk {
                    every { getCachedMeta(player.uniqueId, MenuEscapeBehavior.META_KEY) } returns escapePreference
                }
                PaperDialogRuntime(plugin) { _, screen, registered ->
                    rendered += screen
                    registration = registered
                }.use { dialogs ->
                    controllerField.set(HelpCenterModule, controller)
                    dialogField.set(ArcMenus, dialogs)
                    assertTrue(player.isOnline, "The fixture player must be online before opening a dialog")

                    repeat(3) { attempt ->
                        val home = HelpCenterHome("home", "survival", "world", 10, 64, 20, isPublic)
                        assertTrue(HelpCenterModule.openHomeSettings(player, home))
                        assertEquals(attempt + 1, rendered.size, "Each marker entry must render a fresh home dialog")
                        val screen = rendered.last()
                        assertEquals("help.home", screen.id)
                        val action = if (changeVisibility) screen.buttons.single { it.id.value == "visibility" }
                            else requireNotNull(screen.exitButton)
                        val key = registration.key(action.id)

                        // The native custom click consumes the real session and reaches Bukkit's command dispatcher.
                        repeat(2) {
                            dialogs.onCustomClick(mockk {
                                every { commonConnection } returns connection
                                every { identifier } returns Key.key(key)
                                every { dialogResponseView } returns null
                            })
                        }
                        assertEquals(attempt + 1, closes, "A stale repeated click must not close or execute twice")
                        assertEquals(attempt + 1, rendered.size, "A direct home entry has no parent dialog to restore")
                        assertEquals(if (changeVisibility) attempt + 1 else 0, commands.size)
                    }
                }
            } finally {
                controllerField.set(HelpCenterModule, previousController)
                dialogField.set(ArcMenus, previousRuntime)
                HookRegistry.luckPermsHook = previousLuckPerms
                controller.close()
                Tasks.reset()
                ConfigManager.clear()
            }
        }
    }
}
