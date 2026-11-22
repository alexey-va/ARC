package ru.arc.playerseat

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.attribute.Attributes
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
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
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Masks the part of the tower above each carrier in that carrier's client. */
internal class SeatPassengerView : AutoCloseable {
    private class TowerView(val carrier: Player, val passengers: Map<Int, Player>)

    // Paper owns the links. Network listeners only read completed viewer snapshots.
    private val rides = mutableMapOf<Player, Player>()
    private val hidden = ConcurrentHashMap<UUID, TowerView>()
    private val packets = PacketEvents.getAPI()
    // Core applies its viewer-specific glow at HIGHEST. The carrier mask must follow it.
    private val listener = object : PacketListenerAbstract(PacketListenerPriority.MONITOR) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.isCancelled || hidden.isEmpty()) return
            val viewerId = event.user.uuid ?: return
            val view = hidden[viewerId] ?: return
            when (event.packetType) {
                PacketType.Play.Server.ENTITY_METADATA -> {
                    val packet = WrapperPlayServerEntityMetadata(event)
                    if (packet.entityId !in view.passengers) return
                    val adjusted = maskedSeatMetadata(packet.entityMetadata) ?: return
                    packet.entityMetadata = adjusted
                    event.markForReEncode(true)
                }
                PacketType.Play.Server.ENTITY_EQUIPMENT -> {
                    val packet = WrapperPlayServerEntityEquipment(event)
                    if (packet.entityId !in view.passengers || packet.equipment.all { it.item.isEmpty }) return
                    packet.equipment = packet.equipment.map { Equipment(it.slot, ItemStack.EMPTY) }
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
                    // Vanilla can omit default metadata and attributes when starting to track again.
                    event.tasksAfterSend += Runnable {
                        if (hidden[viewerId]?.passengers?.get(id) === rider && !event.isCancelled) {
                            event.user.sendPacket(WrapperPlayServerEntityMetadata(id, listOf(
                                EntityData(FLAGS, EntityDataTypes.BYTE, INVISIBLE.toByte()),
                                EntityData(POSE, EntityDataTypes.ENTITY_POSE, SEATED_VIEW_POSE),
                            )))
                            event.user.sendPacket(WrapperPlayServerUpdateAttributes(id, listOf(smallSeatScale())))
                        }
                    }
                }
            }
        }
    }

    init { packets.eventManager.registerListener(listener) }

    /** Called on Paper's main thread. Outgoing native updates pass through the same viewer filter. */
    fun setHidden(carrier: Player, rider: Player, value: Boolean) {
        val changed = if (value) rides.put(carrier, rider) !== rider else rides.remove(carrier, rider)
        if (!changed) return
        var viewer = carrier
        while (true) {
            val passengers = generateSequence(rides[viewer], rides::get).associateBy(Player::getEntityId)
            val previous = hidden[viewer.uniqueId]?.passengers.orEmpty()
            if (passengers.isEmpty()) hidden.remove(viewer.uniqueId)
            else hidden[viewer.uniqueId] = TowerView(viewer, passengers)
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
        val metadata = SpigotConversionUtil.getEntityMetadata(rider).filter { it.index == FLAGS || it.index == POSE }
        packets.playerManager.sendPacket(carrier, WrapperPlayServerEntityMetadata(rider.entityId, metadata))
        carrier.sendEquipmentChange(rider, PLAYER_SLOTS.associateWith(rider.inventory::getItem))
    }

    override fun close() {
        val views = hidden.values.toList()
        rides.clear()
        hidden.clear()
        try {
            views.forEach { view -> view.passengers.values.forEach { sync(view.carrier, it) } }
        } finally {
            packets.eventManager.unregisterListener(listener)
        }
    }
}

private const val FLAGS = 0
private const val POSE = 6
private const val INVISIBLE = 0x20
private const val FIRE_OR_GLOW = 0x01 or 0x40
private const val MINIMUM_SCALE = 0.0625
// This pose attaches the miniature at its feet. The separate trident spin flag stays untouched.
private val SEATED_VIEW_POSE = EntityPose.SPIN_ATTACK
private val PLAYER_SLOTS = listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
    EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

private fun smallSeatScale() = Property(Attributes.SCALE, MINIMUM_SCALE, emptyList())

internal fun maskedSeatScale(properties: List<Property>): List<Property>? {
    val index = properties.indexOfFirst { it.attribute == Attributes.SCALE }
    if (index == -1) return null
    val scale = properties[index]
    if (scale.value == MINIMUM_SCALE && scale.modifiers.isEmpty()) return null
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
    var changes: MutableList<EntityData<*>>? = null
    for ((i, entry) in entries.withIndex()) {
        val replacement = when {
            entry.index == FLAGS && entry.type == EntityDataTypes.BYTE -> {
                val flags = entry.value as Byte
                val masked = (flags.toInt() and FIRE_OR_GLOW.inv() or INVISIBLE).toByte()
                if (flags == masked) continue
                EntityData(FLAGS, EntityDataTypes.BYTE, masked)
            }
            entry.index == POSE && entry.type == EntityDataTypes.ENTITY_POSE && entry.value != SEATED_VIEW_POSE ->
                EntityData(POSE, EntityDataTypes.ENTITY_POSE, SEATED_VIEW_POSE)
            else -> continue
        }
        if (changes == null) changes = entries.toMutableList()
        changes[i] = replacement
    }
    return changes
}
