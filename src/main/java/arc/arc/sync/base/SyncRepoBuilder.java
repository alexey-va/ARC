package arc.arc.sync.base;

import arc.arc.network.RedisManager;
import org.bukkit.event.Event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class SyncRepoBuilder<T extends SyncData> {
    Class<T> clazz;
    String key;
    RedisManager redisManager;
    Consumer<T> dataApplier;
    Function<Context, T> dataProducer;

    public SyncRepoBuilder(Class<T> clazz) {
        this.clazz = clazz;
    }

    public SyncRepoBuilder<T> key(String key) {
        this.key = key;
        return this;
    }

    public SyncRepoBuilder<T> redisManager(RedisManager redisManager) {
        this.redisManager = redisManager;
        return this;
    }

    public SyncRepoBuilder<T> dataApplier(Consumer<T> dataSetter) {
        this.dataApplier = dataSetter;
        return this;
    }

    public SyncRepoBuilder<T> dataProducer(Function<Context, T> dataProducer) {
        this.dataProducer = dataProducer;
        return this;
    }


    public SyncRepo<T> build() {
        return new SyncRepo<>(clazz, key, redisManager, dataApplier, dataProducer);
    }
}
