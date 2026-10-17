package ru.arc.homemarkers

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.logging.Level

object HomeMarkersModule : PluginModule {
    override val name = "HomeMarkers"
    override val priority = 87
    private var runtime: HomeMarkerController? = null

    override fun init() = start()
    override fun reload() = start()
    override fun shutdown() { runtime?.close(); runtime = null }

    private fun start() {
        shutdown()
        val plugin = ARC.instance
        val settings = HomeMarkerConfig.load(plugin.dataPath).snapshot()
        lateinit var controller: HomeMarkerController
        val scene = HomeMarkerScene(plugin, settings) { player, marker -> controller.activate(player, marker) }
        controller = HomeMarkerController(plugin, settings, HomeMarkerGateway(), scene)
        runtime = controller
    }
}

/** Owns provider snapshots only. Core owns packet audiences, input geometry and connection replay. */
internal class HomeMarkerController(
    private val plugin: Plugin,
    private val settings: HomeMarkerSettings,
    private val gateway: HomeMarkerGateway,
    private val scene: HomeMarkerScene,
) : Listener, AutoCloseable {
    private class Session(val player: Player) {
        var markers = emptyList<HomeMarker>()
        var nextRefresh = 0L
        var refreshing = false
        var opening = false
        var failures = 0
    }

    private val tasks = LifecycleTaskScope()
    private val sessions = mutableMapOf<UUID, Session>()
    private var tick = 0L

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(1, 10, ::refresh)
    }

    private fun refresh() {
        tick += 10
        for (player in plugin.server.onlinePlayers) {
            if (player.isDead) continue
            val session = sessions.getOrPut(player.uniqueId) { Session(player) }
            if (!session.refreshing && tick >= session.nextRefresh) {
                session.refreshing = true
                session.nextRefresh = tick + settings.refreshTicks
                gateway.load(player).whenCompleteSync(tasks) { markers, failure ->
                    if (!current(session)) return@whenCompleteSync
                    session.refreshing = false
                    if (failure != null) {
                        failed(session, "refresh", failure)
                        session.markers = emptyList()
                        scene.clear(player.uniqueId)
                    } else {
                        recovered(session)
                        session.markers = markers.orEmpty()
                        render(session)
                    }
                }
            }
            render(session)
        }
    }

    fun activate(player: Player, expected: HomeMarker) {
        val session = sessions[player.uniqueId] ?: return
        if (!current(session) || !player.isSneaking || expected.ownerId != player.uniqueId || session.opening) return
        session.opening = true
        // Read the owner plugin again: a visible snapshot never authorizes an old/deleted/transferred point.
        gateway.load(player).whenCompleteSync(tasks) { markers, failure ->
            if (!current(session)) return@whenCompleteSync
            session.opening = false
            if (failure != null) {
                failed(session, "open-settings", failure)
                player.sendMessage(settings.failureMessage)
                return@whenCompleteSync
            }
            recovered(session)
            session.markers = markers.orEmpty()
            if (!render(session)) return@whenCompleteSync
            // The scene validated the click ray. Neither releasing Shift nor looking away cancels an accepted gesture.
            if (expected !in session.markers || player.world.name != expected.world) return@whenCompleteSync
            runCatching { gateway.openSettings(player, expected) }
                .onFailure { failed(session, "open-settings", it) }
                .getOrDefault(false)
                .also { if (!it) player.sendMessage(settings.failureMessage) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) = scene.updateSneaking(event.player, event.isSneaking)

    @EventHandler fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = clear(event.player.uniqueId)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = clear(event.entity.uniqueId)

    private fun current(session: Session): Boolean =
        sessions[session.player.uniqueId] === session && session.player.isOnline && !session.player.isDead &&
            plugin.server.getPlayer(session.player.uniqueId) === session.player

    private fun clear(playerId: UUID) {
        sessions.remove(playerId)
        scene.clear(playerId)
    }

    private fun render(session: Session): Boolean = runCatching {
        scene.reconcile(session.player, session.markers)
    }.onFailure { failure -> failed(session, "render", failure) }.isSuccess

    private fun failed(session: Session, operation: String, failure: Throwable) {
        if (session.failures++ == 0) plugin.logger.log(
            Level.WARNING, "Home markers failed: operation=$operation player=${session.player.uniqueId}; retrying on refresh", failure,
        )
    }

    private fun recovered(session: Session) {
        if (session.failures > 0) {
            plugin.logger.info("Home markers recovered: player=${session.player.uniqueId} failures=${session.failures}")
            session.failures = 0
        }
    }

    override fun close() {
        tasks.close()
        HandlerList.unregisterAll(this)
        sessions.clear()
        scene.close()
    }
}
