package ru.arc.sidebar

import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.paper.api.ArcSidebarRegistrationSnapshot
import ru.arc.paper.api.ArcSidebarSelectionSnapshot
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.sidebar.PaperArcSidebarService

/** Player section policy around the shared renderer; Core still owns priority and scoreboards.
 * Calls and refresh callbacks run on the primary thread. Provider frames remain unmodified.
 */
internal class SectionedSidebarService(
    private val host: Plugin,
    private val native: ArcSidebarService = PaperArcSidebarService(host),
) : ArcSidebarService, AutoCloseable, Listener {
    private val sources = linkedMapOf<Pair<String, String>, Source>()
    internal var refreshPlayer: ((Player) -> Unit)? = null

    init { Bukkit.getPluginManager().registerEvents(this, host) }

    override fun register(owner: Plugin, id: String, priority: Int): ArcSidebarHandle {
        val key = owner.name to id
        val source = Source(owner, native.register(owner, id, priority), owner === host && id == "base", priority)
        sources[key] = source
        return object : ArcSidebarHandle {
            override fun show(player: Player, frame: ArcSidebarFrame) {
                source.frames[player.uniqueId] = frame
                source.handle.show(player, frame)
                if (!source.base) refreshPlayer?.invoke(player)
            }
            override fun hide(playerId: UUID) {
                source.frames.remove(playerId)
                source.handle.hide(playerId)
                Bukkit.getPlayer(playerId)?.let { player ->
                    if (source.base) restoreActivity(player) else refreshPlayer?.invoke(player)
                }
            }
            override fun close() {
                val affected = source.frames.keys.toList()
                sources.remove(key)
                source.frames.clear()
                source.handle.close()
                affected.mapNotNull(Bukkit::getPlayer).forEach { player ->
                    if (source.base) restoreActivity(player) else refreshPlayer?.invoke(player)
                }
            }
        }
    }

    internal fun activity(playerId: UUID): ArcSidebarFrame? = activitySource(playerId)?.frames?.get(playerId)

    /** Repaint only Core's winning provider, preserving its hidden-name team and diagnostics. */
    internal fun present(player: Player, frame: ArcSidebarFrame, mergeActivity: Boolean) {
        val source = activitySource(player.uniqueId) ?: return
        val original = requireNotNull(source.frames[player.uniqueId])
        source.handle.show(player, if (mergeActivity) ArcSidebarFrame(frame.title, frame.rows, original.hiddenNameEntries) else original)
    }

    private fun restoreActivity(player: Player) {
        activitySource(player.uniqueId)?.let { source -> source.frames[player.uniqueId]?.let { source.handle.show(player, it) } }
    }

    private fun activitySource(playerId: UUID): Source? = native.active(playerId)?.let { active ->
        sources[active.owner to active.id]?.takeIf { !it.base && it.priority > ArcSidebarPriorities.BASE }
    }

    override fun registrations(): List<ArcSidebarRegistrationSnapshot> = native.registrations()
    override fun active(playerId: UUID): ArcSidebarSelectionSnapshot? = native.active(playerId)

    @EventHandler fun onQuit(event: PlayerQuitEvent) { sources.values.forEach { it.frames.remove(event.player.uniqueId) } }
    @EventHandler(priority = EventPriority.MONITOR) fun onDisable(event: PluginDisableEvent) {
        val affected = sources.values.filter { it.owner === event.plugin }.flatMap { it.frames.keys }.distinct()
        sources.entries.removeIf { it.value.owner === event.plugin }
        affected.mapNotNull(Bukkit::getPlayer).forEach { refreshPlayer?.invoke(it) }
    }

    override fun close() {
        refreshPlayer = null
        HandlerList.unregisterAll(this)
        sources.values.forEach { it.frames.clear() }
        sources.clear()
        (native as? AutoCloseable)?.close()
    }

    private class Source(val owner: Plugin, val handle: ArcSidebarHandle, val base: Boolean, val priority: Int) {
        val frames = mutableMapOf<UUID, ArcSidebarFrame>()
    }
}
