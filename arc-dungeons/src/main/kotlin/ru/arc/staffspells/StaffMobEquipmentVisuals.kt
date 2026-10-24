package ru.arc.staffspells

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot as PacketEquipmentSlot
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Native hand rendering only: EliteMobs retains the real equipment, attributes and drops. */
internal class StaffMobEquipmentVisuals(private val mob: Mob, item: ItemStack) : AutoCloseable {
    private val entityId: Int
    private val hand: ItemStack
    private val snapshot: StaffMobHandSnapshot
    private var viewers = emptyMap<UUID, Player>()
    private var closed = false

    init {
        check(Bukkit.isPrimaryThread())
        entityId = mob.entityId
        hand = item.clone()
        snapshot = StaffMobHandSnapshot(mob.uniqueId, SpigotConversionUtil.fromBukkitItemStack(hand).copy())
        StaffMobHands.acquire(entityId, snapshot)
        try {
            update(mob, null)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    val isValid get() = !closed && StaffMobHands.owns(entityId, snapshot) && mob.isValid && !mob.isDead

    fun update(mob: Mob, @Suppress("UNUSED_PARAMETER") target: Player?) {
        check(Bukkit.isPrimaryThread())
        if (!isValid) return
        require(mob.uniqueId == snapshot.entityUuid)
        val audience = mob.trackedBy.filter { it.isOnline }.associateBy { it.uniqueId }
        audience.forEach { (id, viewer) ->
            if (viewers[id] !== viewer) viewer.sendEquipmentChange(mob, EquipmentSlot.HAND, hand)
        }
        viewers = audience
    }

    override fun close() {
        check(Bukkit.isPrimaryThread())
        if (closed) return
        closed = true
        viewers = emptyMap()
        if (!StaffMobHands.release(entityId, snapshot)) return
        // Read the current native hand, not the item held when this visual began.
        val actualHand = mob.equipment?.itemInMainHand?.clone()
        mob.trackedBy.filter { it.isOnline }.forEach {
            it.sendEquipmentChange(mob, EquipmentSlot.HAND, actualHand)
        }
    }
}

/** Published once on the main thread; the packet listener never reads a Bukkit object. */
private class StaffMobHandSnapshot(val entityUuid: UUID, val item: PacketItemStack)

/** One feature-owned listener for all bounded dungeon casters, released with the final handle. */
private object StaffMobHands {
    @Volatile private var snapshots = emptyMap<Int, StaffMobHandSnapshot>()
    private var events: EventManager? = null

    private val listener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.isCancelled || snapshots.isEmpty()) return
            when (event.packetType) {
                PacketType.Play.Server.ENTITY_EQUIPMENT -> {
                    val packet = WrapperPlayServerEntityEquipment(event)
                    val snapshot = snapshots[packet.entityId] ?: return
                    packet.equipment = packet.equipment.filter { it.slot != PacketEquipmentSlot.MAIN_HAND } +
                        Equipment(PacketEquipmentSlot.MAIN_HAND, snapshot.item.copy())
                    event.markForReEncode(true)
                }
                PacketType.Play.Server.SPAWN_ENTITY -> {
                    val packet = WrapperPlayServerSpawnEntity(event)
                    val entityId = packet.entityId
                    val snapshot = snapshots[entityId] ?: return
                    if (packet.uuid.orElse(null) != snapshot.entityUuid) return
                    // Empty native equipment produces no equipment packet. Replay only after spawn,
                    // including re-tracking, and never let an old callback outlive its owner.
                    event.tasksAfterSend += Runnable {
                        if (!event.isCancelled && owns(entityId, snapshot)) {
                            event.user.sendPacket(WrapperPlayServerEntityEquipment(entityId,
                                listOf(Equipment(PacketEquipmentSlot.MAIN_HAND, snapshot.item.copy()))))
                        }
                    }
                }
            }
        }
    }

    fun owns(entityId: Int, snapshot: StaffMobHandSnapshot) = snapshots[entityId] === snapshot

    fun acquire(entityId: Int, snapshot: StaffMobHandSnapshot) {
        if (snapshots.isEmpty()) {
            val manager = PacketEvents.getAPI().eventManager
            manager.registerListener(listener)
            events = manager
        }
        snapshots = snapshots + (entityId to snapshot)
    }

    fun release(entityId: Int, snapshot: StaffMobHandSnapshot): Boolean {
        if (!owns(entityId, snapshot)) return false
        snapshots = snapshots - entityId
        if (snapshots.isEmpty()) {
            events?.unregisterListener(listener)
            events = null
        }
        return true
    }
}
