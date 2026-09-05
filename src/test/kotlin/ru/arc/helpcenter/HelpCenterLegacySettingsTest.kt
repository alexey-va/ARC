package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HelpCenterLegacySettingsTest {
    @Test
    fun `entries expose canonical states and execute only typed actions`() {
        val player = mockk<Player>()
        val backend = FakeBackend()
        every { player.uniqueId } returns UUID.randomUUID()
        backend.permissions["arc.chat.notify"] = true
        backend.permissions["tab.scoreboard3"] = true
        backend.permissions["tab.group.admin"] = true

        val settings = HelpCenterLegacySettings(backend)
        assertEquals("3", settings.entries(player).first { it.id == "scoreboard" }.state)
        assertEquals("on", settings.entries(player).first { it.id == "notifications" }.state)

        assertTrue(settings.execute(player, "notifications").join())
        assertEquals(false, backend.permissions["arc.chat.notify"])
        assertTrue(settings.execute(player, "flight-recharge").join())
        assertEquals(HelpCenterLegacySettings.PlayerCommand.FLIGHT_RECHARGE, backend.commands.single())
        assertEquals(null, settings.entries(player).first { it.id == "shift-sign-edit" }.state)
        assertTrue(settings.execute(player, "admin").join())
        assertEquals(HelpCenterLegacySettings.ConsoleCommand.OPEN_ADMIN_SETTINGS, backend.consoleCommands.single())
    }

    @Test
    fun `unknown ids cannot execute arbitrary commands`() {
        val player = mockk<Player>()
        val backend = FakeBackend()
        val settings = HelpCenterLegacySettings(backend)
        assertEquals(false, settings.execute(player, "console rm -rf /").join())
        assertTrue(backend.commands.isEmpty())
    }

    private class FakeBackend : HelpCenterLegacySettings.Backend {
        val permissions = mutableMapOf<String, Boolean>()
        val commands = mutableListOf<HelpCenterLegacySettings.PlayerCommand>()
        val consoleCommands = mutableListOf<HelpCenterLegacySettings.ConsoleCommand>()
        override fun hasPermission(player: Player, node: String) = permissions[node] == true
        override fun meta(player: Player, key: String) = null
        override fun cmiOption(player: Player, option: HelpCenterLegacySettings.CmiOption) = null
        override fun flightState(player: Player) = null
        override fun jobsBossbar(player: Player) = null
        override fun tpaEnabled(player: Player) = null
        override fun setPermission(player: Player, node: String, enabled: Boolean) = CompletableFuture.completedFuture(permissions.put(node, enabled) == null || true)
        override fun setExclusiveMode(player: Player, prefix: String, mode: Int?) = CompletableFuture.completedFuture(true)
        override fun setMeta(player: Player, key: String, value: String) = CompletableFuture.completedFuture(true)
        override fun command(player: Player, command: HelpCenterLegacySettings.PlayerCommand) = CompletableFuture.completedFuture(commands.add(command).let { true })
        override fun consoleCommand(player: Player, command: HelpCenterLegacySettings.ConsoleCommand) = CompletableFuture.completedFuture(consoleCommands.add(command).let { true })
    }
}
