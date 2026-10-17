@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Use Core's headless presenter with real history and dispatch.

package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun assertRepeatedHomeEntry(escapePreference: String) {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("HomeEntry")
            val player: Player = spyk(paper.addPlayer("HomeOwner"))
            every { player.isOnline } returns true
            var closes = 0
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
                gateway = mockk(),
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
                    val home = HelpCenterHome("home", "survival", "world", 10, 64, 20)
                    assertTrue(player.isOnline, "The fixture player must be online before opening a dialog")

                    repeat(3) { attempt ->
                        assertTrue(HelpCenterModule.openHomeSettings(player, home))
                        assertEquals(attempt + 1, rendered.size, "Each marker entry must render a fresh home dialog")
                        val screen = rendered.last()
                        assertEquals("help.home", screen.id)
                        val footer = requireNotNull(screen.exitButton)
                        val key = registration.key(footer.id)

                        // The native Escape/footer custom click consumes the real session and history.
                        dialogs.onCustomClick(mockk {
                            every { commonConnection } returns connection
                            every { identifier } returns Key.key(key)
                            every { dialogResponseView } returns null
                        })
                        assertEquals(attempt + 1, closes, "The footer must close the native dialog")
                        assertEquals(attempt + 1, rendered.size, "A direct home entry has no parent dialog to restore")
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
