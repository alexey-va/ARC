package ru.arc.hooks.packetevents

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import net.kyori.adventure.util.TriState
import org.bukkit.Bukkit
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import ru.arc.core.sync
import ru.arc.ARC
import ru.arc.paper.entity.PaperViewerEntityGlow

class PacketEventsHook : AutoCloseable {
    private val glow = PaperViewerEntityGlow(ARC.instance, "entity-highlights")


    /**
     * Native mounted-entity visibility correction. Keep it ordered with passenger
     * synchronization; it is not an independent cosmetic display frame.
     */
    fun setEntityInvisibleFor(entity: LivingEntity, player: Player, invisible: Boolean) {
        runOnMainThread {
            if (!player.isOnline || !entity.isValid) return@runOnMainThread
            val metadata =
                WrapperPlayServerEntityMetadata(
                    entity.entityId,
                    listOf(EntityData(0, EntityDataTypes.BYTE, commonEntityFlags(entity, invisibleForViewer = invisible))),
                )
            PacketEvents.getAPI().playerManager.sendPacket(player, metadata)
        }
    }

    /** Replays native riding links after every entity in a nested seat has been tracked. */
    fun synchronizePassengersFor(player: Player, vehicles: List<LivingEntity>) {
        runOnMainThread {
            if (!player.isOnline || vehicles.any { !it.isValid }) return@runOnMainThread
            vehicles.forEach { vehicle ->
                val packet = WrapperPlayServerSetPassengers(
                    vehicle.entityId,
                    vehicle.passengers.map { it.entityId }.toIntArray(),
                )
                PacketEvents.getAPI().playerManager.sendPacket(player, packet)
            }
        }
    }

    /** Budgeted, latest-state viewer highlight with native metadata restoration. */
    fun setEntityGlowingFor(entity: LivingEntity, player: Player, glowing: Boolean) {
        runOnMainThread { glow.set(player, entity, glowing) }
    }

    fun setDisplayGlowingFor(entity: Display, player: Player, glowing: Boolean) {
        runOnMainThread { glow.set(player, entity, glowing) }
    }

    override fun close() = glow.close()

    private fun runOnMainThread(action: () -> Unit) {
        if (Bukkit.isPrimaryThread()) action()
        else sync(action)
    }
}

internal fun commonEntityFlags(
    entity: LivingEntity,
    invisibleForViewer: Boolean? = null,
    glowingForViewer: Boolean? = null,
): Byte {
    var flags = 0
    val invisible = invisibleForViewer ?: entity.isInvisible
    val glowing = glowingForViewer ?: entity.isGlowing
    if (entity.fireTicks > 0 || entity.visualFire == TriState.TRUE) flags = flags or 0x01
    if (entity.isSneaking) flags = flags or 0x02
    if (entity.isSwimming) flags = flags or 0x10
    if (invisible) flags = flags or 0x20
    if (!invisible && glowing) flags = flags or 0x40
    if (entity.isGliding) flags = flags or 0x80
    return flags.toByte()
}
