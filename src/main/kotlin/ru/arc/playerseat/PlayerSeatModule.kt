package ru.arc.playerseat

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
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
import java.nio.ByteBuffer
import java.util.UUID

object PlayerSeatModule : PluginModule {
    private const val CHANNEL = "arc:seat_nametag"
    override val name = "PlayerSeat"
    override val priority = 85
    private var runtime: PlayerSeatRuntime? = null

    override fun init() {
        shutdown()
        val plugin = ARC.instance
        val canCarry: (Player) -> Boolean =
            if (plugin.server.pluginManager.isPluginEnabled("CMI")) CmiPlayerSeatAccess::allows
            else { _ -> true }
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
        val seats = PlayerSeatRuntime(BukkitEventBus(plugin), { rider, carrierId ->
            if (rider.isOnline && plugin.isEnabled) {
                rider.sendPluginMessage(plugin, CHANNEL, seatNameTagPayload(carrierId))
            }
        }, canCarry)
        runtime = seats
        seats.start()
    }

    override fun reload() = init()

    override fun shutdown() {
        val seats = runtime ?: return
        seats.close()
        runtime = null
        val plugin = ARC.instance
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
    }
}

/** Sent through the rider's server connection. An empty payload clears only that viewer's override. */
internal fun seatNameTagPayload(carrierId: UUID?): ByteArray =
    if (carrierId == null) byteArrayOf()
    else ByteBuffer.allocate(16).putLong(carrierId.mostSignificantBits).putLong(carrierId.leastSignificantBits).array()

/** Paper-thread-only ownership of native player passengers; flight state belongs to the server. */
internal class PlayerSeatRuntime(
    bus: EventBus,
    private val updateNameTag: (Player, UUID?) -> Unit = { _, _ -> },
    private val canCarry: (Player) -> Boolean = { true },
) : AutoCloseable {
    private data class Ride(val carrier: Player, val rider: Player)

    private val events = EventScope(bus)
    private val rideByRider = mutableMapOf<UUID, Ride>()
    private val rideByCarrier = mutableMapOf<UUID, Ride>()

    fun start() {
        events.on<PlayerInteractEntityEvent>(EventPriority.HIGHEST, ignoreCancelled = true, handler = ::interact)
        if (PlayerInteractAtEntityEvent.getHandlerList() !== PlayerInteractEntityEvent.getHandlerList()) {
            events.on<PlayerInteractAtEntityEvent>(EventPriority.HIGHEST, ignoreCancelled = true, handler = ::interact)
        }
        events.on<PlayerToggleSneakEvent>(EventPriority.HIGHEST, ignoreCancelled = true) {
            if (it.isSneaking) rideByRider[it.player.uniqueId]?.let(::dismount)
        }
        events.on<EntityDamageEvent>(EventPriority.HIGHEST, ignoreCancelled = true) { event ->
            if (event.cause != EntityDamageEvent.DamageCause.SUFFOCATION) return@on
            val rider = event.entity as? Player ?: return@on
            val ride = rideByRider[rider.uniqueId] ?: return@on
            if (rider.vehicle == ride.carrier) event.isCancelled = true
        }
        events.on<EntityDismountEvent>(EventPriority.MONITOR, ignoreCancelled = true) { event ->
            val ride = rideByRider[event.entity.uniqueId] ?: return@on
            if (ride.carrier.uniqueId == event.dismounted.uniqueId) {
                forget(ride)
            }
        }
        events.on<PlayerQuitEvent>(EventPriority.HIGHEST) { detachPlayer(it.player) }
        events.on<PlayerDeathEvent>(EventPriority.HIGHEST, ignoreCancelled = true) { detachPlayer(it.entity) }
        events.on<PlayerTeleportEvent>(EventPriority.HIGHEST, ignoreCancelled = true) {
            // Paper repositions the dismounting rider. Their own passenger stays attached.
            if (it.cause != PlayerTeleportEvent.TeleportCause.DISMOUNT) detachPlayer(it.player)
        }
        events.on<PlayerChangedWorldEvent>(EventPriority.HIGHEST) { detachPlayer(it.player) }
    }

    private fun interact(event: PlayerInteractEntityEvent) {
        if (event.isCancelled || event.hand != EquipmentSlot.HAND) return
        val rider = event.player
        val target = event.rightClicked as? Player ?: return
        if (!rider.inventory.itemInMainHand.type.isAir || rider.uniqueId == target.uniqueId) return

        val currentRide = activeRide(rideByRider[rider.uniqueId])
        if (currentRide != null) {
            // The generic click can follow a precise click on the same tower.
            if (seatFor(target, rider) == currentRide.carrier) {
                event.isCancelled = true
            }
            return
        }
        if (!eligible(rider) || rider.isSneaking || rider.isInsideVehicle || !rider.canSee(target) || !target.canSee(rider)) return
        val carrier = seatFor(target, rider) ?: return
        // Two towers can join. Their tops must be different to avoid mounting a descendant.
        if (rider.passengers.isNotEmpty()) {
            val riderTop = seatFor(rider) ?: return
            if (riderTop == carrier) return
        }
        if (carrier == rider || !rider.canSee(carrier) || !carrier.canSee(rider) || !canCarry(carrier)) return
        if (!carrier.addPassenger(rider)) return

        val ride = Ride(carrier, rider)
        rideByCarrier[carrier.uniqueId] = ride
        rideByRider[rider.uniqueId] = ride
        event.isCancelled = true
        updateNameTag(rider, carrier.uniqueId)
    }

    private fun eligible(player: Player): Boolean =
        player.isOnline && !player.isDead && !player.hasMetadata("NPC") &&
            player.gameMode != GameMode.SPECTATOR

    private fun activeRide(ride: Ride?): Ride? {
        if (ride == null || ride.rider.vehicle == ride.carrier) return ride
        // A native seat can disappear between clicks. Release only our old link and nametag.
        forget(ride)
        return null
    }

    /** Follow only our native passenger links. Foreign vehicles and passengers keep their click. */
    private fun seatFor(target: Player, rider: Player? = null): Player? {
        var current = target
        while (eligible(current)) {
            if (current.isInsideVehicle && rideByRider[current.uniqueId]?.carrier != current.vehicle) return null
            val passengers = current.passengers
            val next = activeRide(rideByCarrier[current.uniqueId])?.rider
            if (passengers.isEmpty()) return current.takeIf { next == null }
            if (next == null) return null
            if (passengers.singleOrNull() != next) return null
            if (next == rider) return current
            current = next
        }
        return null
    }

    private fun detachPlayer(player: Player) {
        rideByRider[player.uniqueId]?.let(::dismount)
        rideByCarrier[player.uniqueId]?.let(::dismount)
    }

    private fun dismount(ride: Ride) {
        if (ride.rider.vehicle?.uniqueId == ride.carrier.uniqueId) ride.carrier.removePassenger(ride.rider)
        if (ride.rider.vehicle?.uniqueId != ride.carrier.uniqueId) forget(ride)
    }

    private fun forget(ride: Ride) {
        rideByCarrier.remove(ride.carrier.uniqueId, ride)
        if (rideByRider.remove(ride.rider.uniqueId, ride)) updateNameTag(ride.rider, null)
    }

    override fun close() {
        events.unregisterAll()
        rideByRider.values.toList().forEach(::dismount)
        check(rideByRider.isEmpty()) { "Player seats could not dismount ${rideByRider.size} owned rider(s)" }
    }
}
