package ru.arc.sync.base;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import ru.arc.ARC;
import ru.arc.network.RedisManager;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;

public class SyncRepo<T extends SyncData> {

    final Class<T> clazz;
    final String key;
    final RedisManager redisManager;
    final Consumer<T> dataApplier;
    final Function<Context, T> dataProducer;

    Gson gson = new Gson();

    public SyncRepo(Class<T> clazz, String key, RedisManager redisManager, Consumer<T> dataApplier,
                    Function<Context, T> dataProducer) {
        this.clazz = clazz;
        this.key = key;
        this.redisManager = redisManager;
        this.dataApplier = dataApplier;
        this.dataProducer = dataProducer;
    }

    public static <T extends SyncData> SyncRepoBuilder<T> builder(Class<T> clazz) {
        return new SyncRepoBuilder<>(clazz);
    }

    private CompletableFuture<Void> saveDataPersistently(T data) {
        return CompletableFuture.supplyAsync(() -> gson.toJson(data))
                .thenAccept(json -> redisManager.saveMapEntries(key, data.uuid().toString(), json));
    }

    private CompletableFuture<T> loadData(UUID uuid) {
        return redisManager.loadMapEntries(key, uuid.toString())
                .thenApply(list -> {
                    if (list == null || list.isEmpty() || list.getFirst() == null) return null;
                    return gson.fromJson(list.getFirst(), clazz);
                });
    }

    private void applyData(T data) {
        if (data == null) {
            debug("No data found in database {} (first visit or not yet saved)", key);
            return;
        }
        if (data.server().equals(ARC.serverName)) return;
        dataApplier.accept(data);
    }

    private CompletableFuture<T> produceData(Context context, boolean async) {
        if (async) {
            return CompletableFuture.supplyAsync(() -> dataProducer.apply(context));
        } else {
            return CompletableFuture.completedFuture(dataProducer.apply(context));
        }
    }

    public CompletableFuture<Void> loadAndApplyData(UUID uuid, boolean async) {
        return loadData(uuid).thenAccept(data -> {
            try {
                if (async) applyData(data);
                else {
                    Bukkit.getScheduler().runTask(ARC.getInstance(), () -> applyData(data));
                }
            } catch (Exception e) {
                error("Error loading and applying data", e);
            }
        });
    }

    public CompletableFuture<Void> saveAndPersistData(Context context, boolean async) {
        return produceData(context, async).thenAccept(data -> {
            if (data == null || data.trash()) return;
            saveDataPersistently(data);
        });
    }


}
