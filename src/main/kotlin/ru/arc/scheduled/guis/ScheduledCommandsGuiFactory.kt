package ru.arc.scheduled.guis

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import ru.arc.ARC
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.scheduled.ScheduledCommandEntry
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

object ScheduledCommandsGuiFactory {
    private val pending = mutableMapOf<UUID, PendingOpen>()

    fun openList(player: Player) {
        prepare(player) { request ->
            val settings = ScheduledCommandsManager.settings()
            val source = settings.entries().iterator()
            val entries = mutableListOf<PaperMenuEntry>()
            val prepared = request.nativeWork.submit(request::current) {
                if (source.hasNext()) entries += entryListItem(player, source.next())
                !source.hasNext()
            }
            request.plugin.whenCompleteOnMain(prepared) { _, failure ->
                if (failure != null) request.failed(failure)
                else request.publish {
                    ArcMenus.open(
                        player,
                        ArcMenuSchema.SCHEDULED_LIST,
                        TextUtil.mm(settings.guiTitle, true),
                        elements = mapOf(
                            "refresh" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.SCHEDULED_LIST, "refresh")) {
                                openList(it)
                            },
                        ),
                        regions = mapOf(ArcMenuSchema.SCHEDULED_ENTRIES to entries),
                    )
                }
            }
        }
    }

    fun openEditor(
        player: Player,
        entryId: String,
    ) {
        prepare(player) { request ->
            val entry = ScheduledCommandsManager.entry(entryId)
            request.publish { if (entry != null) EditScheduledCommandGui.open(player, entry) }
        }
    }

    private fun prepare(player: Player, action: (PendingOpen) -> Unit) {
        pending[player.uniqueId]?.close()
        val request = PendingOpen(player)
        pending[player.uniqueId] = request
        request.plugin.server.pluginManager.registerEvents(request, request.plugin)
        val ready = CompletableFuture.allOf(request.plugin.readiness(), ScheduledCommandsManager.readiness())
        request.plugin.whenCompleteOnMain(ready) { _, failure ->
            if (failure != null) request.failed(failure)
            else if (request.current()) {
                runCatching {
                    request.configuration = ArcMenus.current()
                    action(request)
                }.onFailure(request::failed)
            } else request.close()
        }
    }

    /** Only pending navigation is retained; a close, replacement or reload prevents late publication. */
    private class PendingOpen(val player: Player) : Listener, AutoCloseable {
        val plugin = ARC.instance
        val nativeWork = plugin.nativeWork
        var configuration: PaperMenuConfiguration? = null

        fun current(): Boolean = pending[player.uniqueId] === this && player.isOnline &&
            ARC.plugin === plugin && plugin.isEnabled && plugin.nativeWork === nativeWork &&
            (configuration == null || runCatching { ArcMenus.current() === configuration }.getOrDefault(false))

        fun publish(action: () -> Unit) {
            val allowed = current()
            close() // Own publication may now close/replace the previous inventory.
            if (allowed) action()
        }

        fun failed(failure: Throwable) {
            val visible = current()
            close()
            if (failure is CancellationException) return
            plugin.logger.log(Level.WARNING, "Scheduled commands menu preparation failed", failure)
            if (visible) player.sendMessage(TextUtil.mm("<red>Не удалось выполнить", true))
        }

        override fun close() {
            pending.remove(player.uniqueId, this)
            HandlerList.unregisterAll(this)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun opened(event: InventoryOpenEvent) { if (event.player.uniqueId == player.uniqueId) close() }

        @EventHandler(priority = EventPriority.MONITOR)
        fun closed(event: InventoryCloseEvent) { if (event.player.uniqueId == player.uniqueId) close() }

        @EventHandler
        fun quit(event: PlayerQuitEvent) { if (event.player.uniqueId == player.uniqueId) close() }

        @EventHandler
        fun disabled(event: PluginDisableEvent) { if (event.plugin === plugin) close() }
    }

    private fun entryListItem(
        player: Player,
        entry: ScheduledCommandEntry,
    ) = ArcMenus.entry(
        ArcMenus.item(
            if (entry.enabled) "scheduled-entry-enabled" else "scheduled-entry-disabled",
            PaperMenuItemRenderContext(
                values = mapOf(
                    "id" to Component.text(entry.id),
                    "schedule" to Component.text(entry.schedule.describe()),
                    "command" to Component.text(entry.command),
                    "servers" to Component.text(entry.serversLabel()),
                    "action" to TextUtil.mm("<yellow>Нажмите для редактирования", true),
                ),
            ),
        ),
    ) { openEditor(player, entry.id) }
}
