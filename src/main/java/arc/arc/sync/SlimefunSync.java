package arc.arc.sync;

import arc.arc.ARC;
import arc.arc.sync.base.*;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.api.researches.Research;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import lombok.*;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class SlimefunSync implements Sync {

    SyncRepo<SlimefunDataDTO> syncRepo;

    ActionCanceller actionCanceller;
    Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();

    public SlimefunSync() {
        this.syncRepo = SyncRepo.builder(SlimefunDataDTO.class)
                .key("arc.slimefun_data")
                .redisManager(ARC.redisManager)
                .dataApplier(this::deserializeAndSavePlayerData)
                .dataProducer(this::serializePlayerData)
                .build();

        this.actionCanceller = new ActionCanceller();
        actionCanceller.registerCanceller(PlayerRightClickEvent.class, (event) -> {
            if (event.getItem().getType() == Material.AIR) return;
            ItemStack item = event.getItem();
            var sfItem = SlimefunItem.getByItem(item);
            if (sfItem == null) return;
            if (sfItem.getId().contains("BACKPACK")) {
                event.cancel();
            }
        });
    }

    @Override
    public void playerJoin(UUID uuid) {
        actionCanceller.addTemporaryProtection(uuid, 2000);
        new BukkitRunnable() {
            @Override
            public void run() {
                log.trace("Calling load data SF for " + uuid);
                syncRepo.loadAndApplyData(uuid, false)
                        .whenComplete((data, ex) -> loaded.put(uuid, true));
            }
        }.runTaskLater(ARC.plugin, 20L);
    }

    @Override
    public void playerQuit(UUID uuid) {
        Context context = new Context();
        context.put("uuid", uuid);
        if (loaded.getOrDefault(uuid, false)) syncRepo.saveAndPersistData(context, false);
        else log.warn("Player data not loaded for " + uuid + ". Skipping save");
        loaded.remove(uuid);
    }

    @Override
    public void processEvent(Event event) {
        if (event instanceof PlayerRightClickEvent playerRightClickEvent) {
            actionCanceller.checkAndCancel(playerRightClickEvent.getPlayer().getUniqueId(), playerRightClickEvent);
        }
    }

    @Override
    public void forceSave(UUID uuid) {
        Context context = new Context();
        context.put("uuid", uuid);
        if (loaded.getOrDefault(uuid, false)) syncRepo.saveAndPersistData(context, false);
        else log.warn("Player data not loaded for " + uuid+". Skipping save");
    }


    private SlimefunDataDTO serializePlayerData(Context context) {
        UUID uuid = context.get("uuid");
        if (uuid == null) {
            log.error("Could not extract uuid for slimefun sync: {}", context);
            return null;
        }
        PlayerProfile playerProfile = Slimefun.getRegistry().getPlayerProfiles().get(uuid);
        if (playerProfile == null) {
            log.error("PlayerProfile not found for {}", uuid);
            log.error("Trying to fetch from disk");
        }
        CompletableFuture<SlimefunDataDTO> future = new CompletableFuture<>();
        PlayerProfile.fromUUID(uuid, (pp) -> {
            SlimefunDataDTO slimefunDataDTO = new SlimefunDataDTO();
            slimefunDataDTO.setUuid(uuid);
            slimefunDataDTO.setTimestamp(System.currentTimeMillis());
            slimefunDataDTO.setServer(ARC.serverName);

            // Serializing researches
            slimefunDataDTO.setResearches(pp.getResearches().stream().map(Research::getID).toList());

            // Serializing backpacks
            Map<String, SlimefunDataDTO.Backpack> backpacks = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                var bp = pp.getBackpack(i);
                if (bp.isEmpty()) break;

                var backpack = bp.get();
                SlimefunDataDTO.Backpack backpackDto = new SlimefunDataDTO.Backpack();
                backpackDto.setSize(backpack.getSize());

                ItemStack[] stacks = backpack.getInventory().getContents();
                Map<String, String> invMap = new HashMap<>();
                for (int j = 0; j < backpack.getSize(); j++) {
                    ItemStack stack = stacks[j];
                    if (stack != null && stack.getType() != Material.AIR) {
                        invMap.put(String.valueOf(j), Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
                    }
                }
                backpackDto.setContents(invMap);
                backpacks.put(String.valueOf(i), backpackDto);
            }
            slimefunDataDTO.setBackpacks(backpacks);
            future.complete(slimefunDataDTO);
        });
        return future.join();
    }

    private void deserializeAndSavePlayerData(SlimefunDataDTO dto) {
        PlayerProfile.fromUUID(dto.uuid, (pp) -> {
            log.trace("Deserializing player data for " + dto.uuid);
            if (pp == null) {
                log.trace("PlayerProfile not found for " + dto.uuid);
                return;
            }

            Set<Integer> researches = new HashSet<>(dto.researches);
            Slimefun.getRegistry().getResearches().stream()
                    .filter(r -> researches.contains(r.getID()) && !pp.hasUnlocked(r))
                    .forEach(r -> pp.setResearched(r, true));

            for (Map.Entry<String, SlimefunDataDTO.Backpack> entry : dto.backpacks.entrySet()) {
                int index = Integer.parseInt(entry.getKey());
                SlimefunDataDTO.Backpack backpack = entry.getValue();
                var bp = pp.getBackpack(index);
                if (bp.isEmpty()) {
                    for (int i = 0; i < 100; i++) {
                        PlayerBackpack newBackpack = pp.createBackpack(backpack.getSize());
                        if (newBackpack.getId() >= index) {
                            bp = Optional.of(newBackpack);
                            break;
                        }
                    }
                }
                bp.ifPresent(b -> {
                    if (b.getId() != index) {
                        log.error("Backpack id mismatch, expected " + index + " but got " + b.getId());
                        return;
                    }
                    if (b.getSize() != backpack.size) b.setSize(backpack.size);
                    for (int i = 0; i < backpack.size; i++) {
                        String s = backpack.contents.get(i + "");
                        if (s == null) {
                            b.getInventory().setItem(i, new ItemStack(Material.AIR));
                            continue;
                        }
                        ItemStack stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
                        b.getInventory().setItem(i, stack);
                    }
                    b.markDirty();
                });
            }
        });
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static class SlimefunDataDTO implements SyncData {

        UUID uuid;
        long timestamp;
        String server;
        List<Integer> researches;
        Map<String, Backpack> backpacks;

        @Override
        public long timestamp() {
            return timestamp;
        }

        @Override
        public String server() {
            return server;
        }

        @Override
        public UUID uuid() {
            return uuid;
        }

        @Override
        public boolean trash() {
            return researches.isEmpty() && backpacks.isEmpty();
        }

        @Setter
        @Getter
        public static class Backpack {
            // Getters and Setters
            int size;
            Map<String, String> contents;

        }
    }
}


