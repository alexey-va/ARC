package arc.arc.sync.base;

import arc.arc.ARC;
import arc.arc.configs.MainConfig;
import arc.arc.network.RedisManager;
import com.google.gson.Gson;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

@Log4j2
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
                .thenAccept(json -> {
                    log.debug("Saving json: "+json);
                    redisManager.saveMapEntries(key, data.uuid().toString(), json);
                });
    }

    private CompletableFuture<T> loadData(UUID uuid) {
        return redisManager.loadMapEntries(key, uuid.toString())
                .thenApply(list -> {
                    if (list == null || list.isEmpty() || list.get(0) == null) return null;
                    return gson.fromJson(list.get(0), clazz);
                });
    }

    private void applyData(T data) {
        if (data == null){
            log.warn("No data found in database "+key);
            return;
        }
        log.trace("Applying data: "+data.getClass()+" "+data.uuid());
        if (data.server().equals(MainConfig.server)) return;
        dataApplier.accept(data);
    }

    private CompletableFuture<T> produceData(Context context, boolean async) {
        if (async) {
            return CompletableFuture.supplyAsync(() -> {
                log.trace("Producing data asynchronously "+context+" "+key);
                return dataProducer.apply(context);
            });
        } else {
            log.trace("Producing data synchronously "+context+" "+key);
            return CompletableFuture.completedFuture(dataProducer.apply(context));
        }
    }

    public void loadAndApplyData(UUID uuid, boolean async) {
        loadData(uuid).thenAccept(data -> {
            log.trace("Loaded data, now applying "+async+": "+data.getClass()+" "+data.uuid());
            if (async) applyData(data);
            else Bukkit.getScheduler().runTask(ARC.plugin, () -> applyData(data));
        });
    }

    public void saveAndPersistData(Context context, boolean async) {
        produceData(context, async).thenAccept(data -> {
            log.debug("Saving data: "+data);
            if (data == null || data.trash()) return;
            saveDataPersistently(data);
        });
    }


}
