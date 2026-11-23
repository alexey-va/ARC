package ru.arc.playerseat

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.ARC
import ru.arc.core.BukkitEventBus
import ru.arc.core.EventBus
import ru.arc.core.EventScope
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
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
        }, SeatDirectionDisplay(plugin), SeatPassengerView(), canCarry)
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

internal interface SeatDirections : AutoCloseable {
    fun show(carrier: Player, rider: Player, yaw: Float, pitch: Float, caption: String)
    fun turn(carrier: Player, rider: Player, yaw: Float, pitch: Float)
    fun hide(riderId: UUID)
}

/** Paper-thread-only ownership of native player passengers; flight state belongs to the server. */
internal class PlayerSeatRuntime(
    bus: EventBus,
    private val updateNameTag: (Player, UUID?) -> Unit = { _, _ -> },
    private val directions: SeatDirections? = null,
    private val passengerView: SeatPassengerView? = null,
    private val canCarry: (Player) -> Boolean = { true },
) : AutoCloseable {
    private class Ride(val carrier: Player, val rider: Player) {
        var nextCaption = 0
        var lastPointTick: Int? = null
        var otherSwingTick: Int? = null
        var steering: ScheduledTask? = null
    }

    private val events = EventScope(bus)
    private val rideByRider = mutableMapOf<UUID, Ride>()
    private val rideByCarrier = mutableMapOf<UUID, Ride>()

    fun start() {
        events.on<PlayerInteractEntityEvent>(EventPriority.HIGHEST, ignoreCancelled = true, handler = ::interact)
        if (PlayerInteractAtEntityEvent.getHandlerList() !== PlayerInteractEntityEvent.getHandlerList()) {
            events.on<PlayerInteractAtEntityEvent>(EventPriority.HIGHEST, ignoreCancelled = true, handler = ::interact)
        }
        // Paper may pre-cancel a genuine air click. Observe input without changing vanilla actions.
        events.on<PlayerInteractEvent>(EventPriority.MONITOR) { event ->
            when (event.action) {
                Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> if (event.hand == EquipmentSlot.HAND) {
                    point(event.player, swing = true)
                }
                Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> recordOtherSwing(event.player)
                else -> Unit
            }
        }
        // Using or dropping the last item can also produce a swing packet with an empty hand.
        events.on<PlayerDropItemEvent>(EventPriority.MONITOR) { recordOtherSwing(it.player) }
        events.on<PrePlayerAttackEntityEvent>(EventPriority.MONITOR) { point(it.player) }
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

    private fun recordOtherSwing(player: Player) {
        rideByRider[player.uniqueId]?.otherSwingTick = player.ticksLived
    }

    private fun point(rider: Player, swing: Boolean = false) {
        val display = directions ?: return
        val ride = activeRide(rideByRider[rider.uniqueId]) ?: return
        if (!rider.inventory.itemInMainHand.type.isAir) return
        val tick = rider.ticksLived
        // A use and its client animation can land on adjacent server ticks, including the mount click.
        if (swing && ride.otherSwingTick?.let { tick - it in 0..1 } == true) return
        if (ride.lastPointTick?.let { tick - it in 0 until 4 } == true) return
        display.show(ride.carrier, rider, rider.yaw, rider.pitch, CAPTIONS[ride.nextCaption])
        ride.lastPointTick = tick
        ride.nextCaption = (ride.nextCaption + 1) % CAPTIONS.size
    }

    private fun interact(event: PlayerInteractEntityEvent) {
        val rider = event.player
        recordOtherSwing(rider)
        if (event.isCancelled || event.hand != EquipmentSlot.HAND) return
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

        val ride = Ride(carrier, rider).apply { otherSwingTick = rider.ticksLived }
        rideByCarrier[carrier.uniqueId] = ride
        rideByRider[rider.uniqueId] = ride
        event.isCancelled = true
        passengerView?.setMounted(carrier, rider, true)
        updateNameTag(rider, carrier.uniqueId)
        if (directions != null || passengerView != null) {
            ride.steering = Tasks.scheduler.runTimer(1, 1) {
                // Reconcile the private view even if Paper lost a mount before the first signal.
                if (activeRide(ride) != null && ride.lastPointTick != null) directions?.turn(carrier, rider, rider.yaw, rider.pitch)
            }
        }
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
        if (rideByRider.remove(ride.rider.uniqueId, ride)) {
            ride.steering?.cancel()
            if (ride.lastPointTick != null) directions?.hide(ride.rider.uniqueId)
            passengerView?.setMounted(ride.carrier, ride.rider, false)
            updateNameTag(ride.rider, null)
        }
    }

    override fun close() {
        events.unregisterAll()
        try {
            rideByRider.values.toList().forEach(::dismount)
        } finally {
            // Paper can deny a dismount. Retain its link for a retry, but release the client views.
            rideByRider.values.forEach {
                it.steering?.cancel()
                it.steering = null
                it.lastPointTick = null
            }
            directions?.close()
            passengerView?.close()
        }
        check(rideByRider.isEmpty()) { "Player seats could not dismount ${rideByRider.size} owned rider(s)" }
    }

    private companion object {
        val CAPTIONS = listOf("Туда Нахуй!", "Пиздуй туда!", "Живее двигай!", "Газуй туда!", "Давай, гони!")
    }
}
