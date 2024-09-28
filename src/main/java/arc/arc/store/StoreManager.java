package arc.arc.store;

import arc.arc.ARC;
import arc.arc.network.repos.RedisRepo;
import org.bukkit.Material;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

public class StoreManager {

    private static RedisRepo<Store> repo;

    public static void init() {
        repo = RedisRepo.builder(Store.class)
                .backupFolder(ARC.plugin.getDataFolder().toPath().resolve("backups/store"))
                .loadAll(true)
                .redisManager(ARC.redisManager)
                .storageKey("arc.store")
                .updateChannel("arc.store_update")
                .clazz(Store.class)
                .id("store")
                .saveInterval(20L)
                .saveBackups(true)
                .build();
    }

    public static CompletableFuture<Store> getStore(UUID playerUuid) {
        return repo.getOrCreate(playerUuid.toString(), () -> new Store(playerUuid))
                .thenApply(store -> {
                    if (store.getLock() == null) store.setLock(new ReentrantLock());
                    store.getItemList().removeIf(
                            storeItem -> storeItem == null ||
                                    storeItem.getType() == Material.AIR
                    );
                    store.size = 9;
                    return store;
                });
    }

    public static void saveAll() {
        repo.forceSave();
    }
}
