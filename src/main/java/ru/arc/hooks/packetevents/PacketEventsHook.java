package ru.arc.hooks.packetevents;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.arc.ARC;

import static ru.arc.util.Logging.debug;

public class PacketEventsHook {


    public List<Integer> createDisplayBlocks(final List<BlockDisplayReq> requests, Player player) {
        if (requests.isEmpty()) {
            return List.of();
        }
        debug("Creating display blocks for player {}", player.getName());
        final List<Integer> entityIds = requests.stream()
                .map(request -> ThreadLocalRandom.current().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE))
                .collect(Collectors.toList());
        runOnMainThread(() -> {
            if (!player.isOnline()) {
                return;
            }
            int i = 0;
            for (BlockDisplayReq request : requests) {
                int entityId = entityIds.get(i++);
                WrapperPlayServerSpawnEntity spawnEntityPacket = new WrapperPlayServerSpawnEntity(
                        entityId,
                        UUID.randomUUID(),
                        EntityTypes.BLOCK_DISPLAY,
                        SpigotConversionUtil.fromBukkitLocation(request.location()),
                        0f,
                        0,
                        null
                );

                WrapperPlayServerEntityMetadata entityMetadataPacket = new WrapperPlayServerEntityMetadata(
                        entityId,
                        Collections.singletonList(
                                new EntityData(23, EntityDataTypes.BLOCK_STATE, SpigotConversionUtil.fromBukkitBlockData(request.data()).getGlobalId())
                        )
                );

                spawnEntityPacket.setEntityId(entityId);
                spawnEntityPacket.setUUID(Optional.of(UUID.randomUUID()));
                entityMetadataPacket.setEntityId(entityId);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawnEntityPacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, entityMetadataPacket);
            }
        });
        return entityIds;
    }

    public void removeDisplayBlocks(List<Integer> entityIds, Player player) {
        if (entityIds == null || entityIds.isEmpty()) {
            return;
        }
        debug("Removing {} display blocks for player {}", entityIds.size(), player.getName());
        runOnMainThread(() -> {
            if (!player.isOnline()) {
                return;
            }
            var packet = new WrapperPlayServerDestroyEntities(entityIds.stream().mapToInt(i -> i).toArray());
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        });
    }

    private void runOnMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(ARC.getInstance(), action);
        }
    }

}
