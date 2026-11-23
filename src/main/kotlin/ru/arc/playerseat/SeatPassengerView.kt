package ru.arc.playerseat

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.attribute.Attributes
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.Property
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.PropertyModifier
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Shows the seated players above each carrier at a smaller scale in that carrier's client. */
internal class SeatPassengerView : AutoCloseable {
    private class TowerView(val carrier: Player, val passengers: Map<Int, Player>)

    // Paper owns the links. Network listeners only read completed viewer snapshots.
    private val rides = mutableMapOf<Player, Player>()
    private val views = ConcurrentHashMap<UUID, TowerView>()
    private val packets = PacketEvents.getAPI()
    // Core applies viewer-specific glow at HIGHEST. Suppress close-up outlines after that.
    private val listener = object : PacketListenerAbstract(PacketListenerPriority.MONITOR) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.isCancelled || views.isEmpty()) return
            val viewerId = event.user.uuid ?: return
            val view = views[viewerId] ?: return
            when (event.packetType) {
                PacketType.Play.Server.ENTITY_METADATA -> {
                    val packet = WrapperPlayServerEntityMetadata(event)
                    if (packet.entityId !in view.passengers) return
                    val adjusted = maskedSeatMetadata(packet.entityMetadata) ?: return
                    packet.entityMetadata = adjusted
                    event.markForReEncode(true)
                }
                PacketType.Play.Server.UPDATE_ATTRIBUTES -> {
                    val packet = WrapperPlayServerUpdateAttributes(event)
                    if (packet.entityId !in view.passengers) return
                    packet.properties = maskedSeatScale(packet.properties) ?: return
                    event.markForReEncode(true)
                }
                PacketType.Play.Server.SPAWN_ENTITY -> {
                    val id = WrapperPlayServerSpawnEntity(event).entityId
                    val rider = view.passengers[id] ?: return
                    // Vanilla can omit the default scale when starting to track again.
                    event.tasksAfterSend += Runnable {
                        if (views[viewerId]?.passengers?.get(id) === rider && !event.isCancelled) {
                            event.user.sendPacket(WrapperPlayServerUpdateAttributes(id, listOf(smallSeatScale())))
                        }
                    }
                }
            }
        }
    }

    init { packets.eventManager.registerListener(listener) }

    /** Called on Paper's main thread. Outgoing native updates pass through the same viewer filter. */
    fun setMounted(carrier: Player, rider: Player, mounted: Boolean) {
        val changed = if (mounted) rides.put(carrier, rider) !== rider else rides.remove(carrier, rider)
        if (!changed) return
        var viewer = carrier
        while (true) {
            val passengers = generateSequence(rides[viewer], rides::get).associateBy(Player::getEntityId)
            val previous = views[viewer.uniqueId]?.passengers.orEmpty()
            if (passengers.isEmpty()) views.remove(viewer.uniqueId)
            else views[viewer.uniqueId] = TowerView(viewer, passengers)
            for ((id, old) in previous) if (passengers[id] !== old) sync(viewer, old)
            for ((id, new) in passengers) if (previous[id] !== new) sync(viewer, new)

            // Shrinking just the middle player would lower the full-size player above into this viewer's face.
            val below = viewer.vehicle as? Player ?: break
            if (rides[below] !== viewer) break
            viewer = below
        }
    }

    private fun sync(carrier: Player, rider: Player) {
        if (!carrier.isOnline || !rider.isOnline || !carrier.canSee(rider) || !rider.isTrackedBy(carrier)) return
        val scale = requireNotNull(rider.getAttribute(Attribute.SCALE))
        packets.playerManager.sendPacket(carrier, WrapperPlayServerUpdateAttributes(rider.entityId, listOf(nativeSeatScale(scale))))
        val metadata = SpigotConversionUtil.getEntityMetadata(rider).filter { it.index == FLAGS }
        packets.playerManager.sendPacket(carrier, WrapperPlayServerEntityMetadata(rider.entityId, metadata))
    }

    override fun close() {
        val previous = views.values.toList()
        rides.clear()
        views.clear()
        try {
            previous.forEach { view -> view.passengers.values.forEach { sync(view.carrier, it) } }
        } finally {
            packets.eventManager.unregisterListener(listener)
        }
    }
}

private const val FLAGS = 0
private const val FIRE_OR_GLOW = 0x01 or 0x40
// The native seated pose keeps the rider's boots in the upper part of the carrier's view.
private const val SEATED_SCALE = 0.4

private fun smallSeatScale() = Property(Attributes.SCALE, SEATED_SCALE, emptyList())

internal fun maskedSeatScale(properties: List<Property>): List<Property>? {
    val index = properties.indexOfFirst { it.attribute == Attributes.SCALE }
    if (index == -1) return null
    val scale = properties[index]
    if (scale.calcValue() <= SEATED_SCALE) return null
    return properties.toMutableList().also { it[index] = smallSeatScale() }
}

internal fun nativeSeatScale(scale: AttributeInstance): Property =
    Property(Attributes.SCALE, scale.baseValue, scale.modifiers.map { modifier ->
        val operation = when (modifier.operation) {
            AttributeModifier.Operation.ADD_NUMBER -> PropertyModifier.Operation.ADDITION
            AttributeModifier.Operation.ADD_SCALAR -> PropertyModifier.Operation.MULTIPLY_BASE
            AttributeModifier.Operation.MULTIPLY_SCALAR_1 -> PropertyModifier.Operation.MULTIPLY_TOTAL
        }
        PropertyModifier(ResourceLocation(modifier.key), modifier.amount, operation)
    })

internal fun maskedSeatMetadata(entries: List<EntityData<*>>): List<EntityData<*>>? {
    val index = entries.indexOfFirst { it.index == FLAGS && it.type == EntityDataTypes.BYTE }
    if (index == -1) return null
    val flags = entries[index].value as Byte
    val adjusted = (flags.toInt() and FIRE_OR_GLOW.inv()).toByte()
    if (flags == adjusted) return null
    return entries.toMutableList().also { it[index] = EntityData(FLAGS, EntityDataTypes.BYTE, adjusted) }
}
