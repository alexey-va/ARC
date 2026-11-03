package ru.arc.graves

import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.audit.AuditConfig

class GravesUnavailableTest : TestBase() {
    @Test
    fun `missing SQL leaves graves unavailable without registering inventory recovery guards`() {
        assertNull(AuditConfig.load().mysql)
        val player = server.addPlayer("NoSqlGraves").apply { isOp = true }
        assertFalse(GravesModule.open(player))
        assertFalse(HandlerList.getRegisteredListeners(plugin).any { it.listener is NativeGraves || it.listener is GravesRuntime })
        val command = PlayerCommandPreprocessEvent(player, "/arc version")
        server.pluginManager.callEvent(command)
        assertFalse(command.isCancelled)
    }
}
