package ru.arc.sync;

import ru.arc.ARC;
import ru.arc.hooks.DuelsHook;
import ru.arc.hooks.HookRegistry;
import ru.arc.sync.base.Context;
import ru.arc.sync.base.Sync;
import ru.arc.sync.base.SyncRepo;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DuelsSync implements Sync {

    SyncRepo<DuelsHook.DuelsData> syncRepo;
    Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();

    public DuelsSync() {
        this.syncRepo = SyncRepo.builder(DuelsHook.DuelsData.class)
                .key("arc.duels_data")
                .redisManager(ARC.redisManager)
                .dataApplier(this::applyDuelsData)
                .dataProducer(this::getDuelsData)
                .build();
    }

    @Override
    public void forceSave(UUID uuid) {
        if(!loaded.containsKey(uuid)) return;
        Context context = new Context();
        context.put("uuid", uuid);
        syncRepo.saveAndPersistData(context, false);
    }

    @Override
    public void playerQuit(UUID uuid) {
        forceSave(uuid);
        loaded.remove(uuid);
    }

    @Override
    public void playerJoin(UUID uuid) {
        AtomicInteger counter = new AtomicInteger(0);
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    return;
                }
                //log.info("Player not null and online");
                DuelsHook.DuelsData data = HookRegistry.duelsHook.getUserData(uuid);
                //log.info("DuelsData for {} {}", uuid, data);
                if (data == null) {
                    if (counter.incrementAndGet() > 60) {
                        ARC.warn("DuelsData is null for " + uuid + " for 60 cycles. Cancelling task.");
                        cancel();
                    }
                    return;
                }
                cancel();
                syncRepo.loadAndApplyData(uuid, false);
                loaded.put(uuid, true);
            }
        }.runTaskTimer(ARC.plugin, 10L, 5L);
    }

    public DuelsHook.DuelsData getDuelsData(Context context) {
        DuelsHook.DuelsData data = HookRegistry.duelsHook.getUserData(context.get("uuid"));
        //log.info("Getting DuelsData for {} {}", context.get("uuid"), data);
        return data;
    }

    public void applyDuelsData(DuelsHook.DuelsData data) {
        //log.info("Applying DuelsData {}", data);
        HookRegistry.duelsHook.setUserData(data.getUuid(), data);
    }
}
