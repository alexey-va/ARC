package ru.arc.helpcenter

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/** One navigation generation shared by all main-menu screens and async completions. */
internal class HelpCenterNavigation(plugin: Plugin, private val onNavigate: (Player) -> Unit = {}) : Listener, AutoCloseable {
    private data class Visit(val token: Long, val reopen: (() -> Unit)?)
    private var serial = 0L
    private val visits = mutableMapOf<UUID, Visit>()

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun visit(player: Player, reopen: (() -> Unit)? = null): Long = (++serial).also {
        onNavigate(player)
        visits[player.uniqueId] = Visit(it, reopen)
    }

    fun isCurrent(player: Player, token: Long): Boolean = visits[player.uniqueId]?.token == token

    fun returnTarget(player: Player): (() -> Unit)? = visits[player.uniqueId]?.reopen

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { visits.remove(event.player.uniqueId) }

    override fun close() {
        visits.clear()
        HandlerList.unregisterAll(this)
    }
}
