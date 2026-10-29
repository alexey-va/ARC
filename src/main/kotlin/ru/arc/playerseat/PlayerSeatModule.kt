package ru.arc.playerseat

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.ARC
import ru.arc.core.BukkitEventBus
import ru.arc.core.EventBus
import ru.arc.core.EventScope
import ru.arc.core.PluginModule
import java.util.UUID

object PlayerSeatModule : PluginModule {
    override val name = "PlayerSeat"
    override val priority = 85
    private var runtime: PlayerSeatRuntime? = null

    override fun init() {
        shutdown()
        val plugin = ARC.instance
        val canCarry: (Player) -> Boolean =
            if (plugin.server.pluginManager.isPluginEnabled("CMI")) CmiPlayerSeatAccess::allows
            else { _ -> true }
        val seats = PlayerSeatRuntime(BukkitEventBus(plugin), canCarry)
        runtime = seats
        seats.start()
    }

    override fun reload() = init()

    override fun shutdown() { runtime?.close(); runtime = null }
}

/** Paper-thread-only ownership of native player passengers; flight state belongs to the server. */
internal class PlayerSeatRuntime(
    bus: EventBus,
    private val canCarry: (Player) -> Boolean = { true },
) : AutoCloseable {
    private data class Ride(val carrier: Player, val rider: Player)

    private val events = EventScope(bus)
    private val rides = mutableMapOf<UUID, Ride>()

    fun start() {
        events.on<PlayerInteractEntityEvent>(EventPriority.HIGHEST, ignoreCancelled = true, handler = ::interact)
        if (PlayerInteractAtEntityEvent.getHandlerList() !== PlayerInteractEntityEvent.getHandlerList()) {
            events.on<PlayerInteractAtEntityEvent>(EventPriority.HIGHEST, ignoreCancelled = true, handler = ::interact)
        }
        events.on<PlayerToggleSneakEvent>(EventPriority.HIGHEST, ignoreCancelled = true) {
            if (it.isSneaking) dismount(it.player)
        }
        events.on<EntityDismountEvent>(EventPriority.MONITOR, ignoreCancelled = true) { event ->
            val ride = rides[event.entity.uniqueId] ?: return@on
            if (ride.rider.uniqueId == event.entity.uniqueId && ride.carrier.uniqueId == event.dismounted.uniqueId) {
                forget(ride)
            }
        }
        events.on<PlayerQuitEvent>(EventPriority.HIGHEST) { dismount(it.player) }
        events.on<PlayerDeathEvent>(EventPriority.HIGHEST, ignoreCancelled = true) { dismount(it.entity) }
        events.on<PlayerTeleportEvent>(EventPriority.HIGHEST, ignoreCancelled = true) { dismount(it.player) }
        events.on<PlayerChangedWorldEvent>(EventPriority.HIGHEST) { dismount(it.player) }
    }

    private fun interact(event: PlayerInteractEntityEvent) {
        if (event.isCancelled || event.hand != EquipmentSlot.HAND) return
        val rider = event.player
        val carrier = event.rightClicked as? Player ?: return
        if (!rider.inventory.itemInMainHand.type.isAir) return

        val owned = rides[rider.uniqueId]
        if (owned?.rider?.uniqueId == rider.uniqueId && owned.carrier.uniqueId == carrier.uniqueId &&
            owned.rider.vehicle?.uniqueId == carrier.uniqueId
        ) {
            // Paper can send a precise click followed by the generic click for the same mount.
            event.isCancelled = true
            return
        }
        if (rider.uniqueId == carrier.uniqueId || rides.containsKey(rider.uniqueId) || rides.containsKey(carrier.uniqueId)) return
        if (!eligible(rider) || !eligible(carrier)) return
        if (!rider.canSee(carrier) || !carrier.canSee(rider) || !canCarry(carrier)) return
        if (!carrier.addPassenger(rider)) return

        val ride = Ride(carrier, rider)
        rides[carrier.uniqueId] = ride
        rides[rider.uniqueId] = ride
        event.isCancelled = true
    }

    private fun eligible(player: Player): Boolean =
        player.isOnline && !player.isDead && !player.hasMetadata("NPC") &&
            player.gameMode != GameMode.SPECTATOR && !player.isSneaking &&
            !player.isInsideVehicle && player.passengers.isEmpty()

    private fun dismount(player: Player) {
        val ride = rides[player.uniqueId] ?: return
        if (ride.rider.vehicle?.uniqueId == ride.carrier.uniqueId) ride.carrier.removePassenger(ride.rider)
        if (ride.rider.vehicle?.uniqueId != ride.carrier.uniqueId) forget(ride)
    }

    private fun forget(ride: Ride) {
        rides.remove(ride.carrier.uniqueId)
        rides.remove(ride.rider.uniqueId)
    }

    override fun close() {
        events.unregisterAll()
        rides.values.toSet().forEach { dismount(it.carrier) }
        check(rides.isEmpty()) { "Player seats could not dismount ${rides.size / 2} owned rider(s)" }
    }
}
