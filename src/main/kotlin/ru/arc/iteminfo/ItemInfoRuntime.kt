package ru.arc.iteminfo

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.hooks.luckperms.LuckPermsHook
import ru.arc.onboarding.OnboardingModule
import ru.arc.util.Logging.error

internal class ItemInfoRuntime(
    settings: ItemInfoSettings,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val failedViewers = mutableSetOf<java.util.UUID>()
    private val preferencesReader = if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) LuckPermsHook() else null
    private val resolver = BukkitItemInfoTargetResolver(settings.targetDistance)
    private val controller = ItemInfoController(
        preferences = { player ->
            ItemInfoPreferences.fromStored { key -> preferencesReader?.getCachedMeta(player.uniqueId, key) }
        },
        target = { player ->
            if (player.isDead || player.gameMode == GameMode.SPECTATOR || OnboardingModule.claimGuide?.hasHologram(player) == true) null
            else resolver.resolve(player)
        },
        renderer = BukkitItemInfoRenderer(settings),
    )
    private var tick = 0L

    fun start() {
        tasks.runTimer(1L, 1L) {
            tick++
            Bukkit.getOnlinePlayers().forEach { player ->
                if (player.uniqueId in failedViewers) return@forEach
                try {
                    if (tick == 1L || tick % 5L == 0L) controller.update(player)
                    controller.follow(player)
                } catch (failure: Exception) {
                    controller.reset(player)
                    failedViewers += player.uniqueId
                    error("Item info failed for {} in {}; disabled until reconnect", player.name, player.world.name, failure)
                }
            }
        }
    }

    @EventHandler fun joined(event: PlayerJoinEvent) {
        failedViewers.remove(event.player.uniqueId)
        refreshSoon(event.player)
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        controller.reset(event.player)
        failedViewers.remove(event.player.uniqueId)
    }

    @EventHandler fun changedWorld(event: PlayerChangedWorldEvent) = resetAndRefresh(event.player)

    @EventHandler fun teleported(event: PlayerTeleportEvent) = resetAndRefresh(event.player)

    @EventHandler fun died(event: PlayerDeathEvent) = controller.reset(event.entity)

    private fun resetAndRefresh(player: Player) {
        controller.reset(player)
        refreshSoon(player)
    }

    private fun refreshSoon(player: Player) {
        tasks.runLater(1L) {
            if (player.isOnline && player.uniqueId !in failedViewers) controller.update(player)
        }
    }

    override fun close() {
        tasks.close()
        controller.close()
        failedViewers.clear()
    }
}
