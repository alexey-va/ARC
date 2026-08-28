package ru.arc.hooks.packetevents

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import net.kyori.adventure.util.TriState
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import ru.arc.core.sync
import ru.arc.util.Logging.debug
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class PacketEventsHook {

    /** Sends common entity flags to one viewer without changing server-side entity state. */
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

    /** Sends a viewer-only glow outline without changing the entity for other players. */
    fun setEntityGlowingFor(entity: LivingEntity, player: Player, glowing: Boolean) {
        runOnMainThread {
            if (!player.isOnline || !entity.isValid) return@runOnMainThread
            val metadata =
                WrapperPlayServerEntityMetadata(
                    entity.entityId,
                    listOf(EntityData(0, EntityDataTypes.BYTE, commonEntityFlags(entity, glowingForViewer = glowing))),
                )
            PacketEvents.getAPI().playerManager.sendPacket(player, metadata)
        }
    }

    fun createDisplayBlocks(requests: List<BlockDisplayReq>, player: Player): List<Int> {
        if (requests.isEmpty()) return emptyList()
        debug("Creating display blocks for player {}", player.name)

        val entityIds = requests.map { ThreadLocalRandom.current().nextInt(Int.MIN_VALUE, Int.MAX_VALUE) }

        runOnMainThread {
            if (!player.isOnline) return@runOnMainThread
            requests.forEachIndexed { i, request ->
                val entityId = entityIds[i]
                val spawnEntityPacket = WrapperPlayServerSpawnEntity(
                    entityId,
                    UUID.randomUUID(),
                    EntityTypes.BLOCK_DISPLAY,
                    SpigotConversionUtil.fromBukkitLocation(request.location),
                    0f, 0, null,
                )
                val entityMetadataPacket = WrapperPlayServerEntityMetadata(
                    entityId,
                    listOf(EntityData(23, EntityDataTypes.BLOCK_STATE, SpigotConversionUtil.fromBukkitBlockData(request.data).globalId)),
                )
                spawnEntityPacket.entityId = entityId
                spawnEntityPacket.uuid = Optional.of(UUID.randomUUID())
                entityMetadataPacket.entityId = entityId
                PacketEvents.getAPI().playerManager.sendPacket(player, spawnEntityPacket)
                PacketEvents.getAPI().playerManager.sendPacket(player, entityMetadataPacket)
            }
        }
        return entityIds
    }

    fun removeDisplayBlocks(entityIds: List<Int>?, player: Player) {
        if (entityIds.isNullOrEmpty()) return
        debug("Removing {} display blocks for player {}", entityIds.size, player.name)
        runOnMainThread {
            if (!player.isOnline) return@runOnMainThread
            val packet = WrapperPlayServerDestroyEntities(*entityIds.toIntArray())
            PacketEvents.getAPI().playerManager.sendPacket(player, packet)
        }
    }

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
