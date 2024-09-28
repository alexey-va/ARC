package arc.arc.network.repos;

import arc.arc.ARC;
import arc.arc.network.RedisManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Log4j2
@SuppressWarnings({"unchecked", "rawtypes", "unused"})

public class RedisRepo<T extends RepoData> {

    ConcurrentHashMap<String, T> map = new ConcurrentHashMap<>();
    ConcurrentHashMap<String, Long> lastAttempt = new ConcurrentHashMap<>();
    RedisRepoMessager messager;
    Gson gson = new GsonBuilder()
            .registerTypeAdapter(ItemStack.class, new ItemStackSerializer())
            .registerTypeAdapter(ItemList.class, new ItemListSerializer())
            .create();
    Set<String> contextSet = new ConcurrentSkipListSet<>();
    BukkitTask saveTask, backupTask;
    long lastFullRefresh;


    @Setter
    Consumer<T> onUpdate;
    BackupService backupService;
    Class clazz;
    String storageKey, updateChannel, id;
    RedisManager redisManager;
    boolean loadAll;
    long saveInterval;
    boolean saveBackups;

    private RedisRepo(Boolean loadAll, RedisManager redisManager, String storageKey, String updateChannel, Class clazz,
                      Consumer<T> onUpdate, String id, Path backupFolder, Long saveInterval, Boolean saveBackups) {
        this.loadAll = loadAll != null && loadAll;
        this.saveInterval = saveInterval == null ? 20L : saveInterval;
        this.redisManager = redisManager;
        this.storageKey = storageKey;
        this.updateChannel = updateChannel;
        this.clazz = clazz;
        this.onUpdate = onUpdate;
        this.saveBackups = saveBackups != null && saveBackups;

        messager = new RedisRepoMessager(this, redisManager);
        redisManager.registerChannel(updateChannel, messager);
        redisManager.init();
        backupService = new BackupService(id, backupFolder);

        startTasks();
        log.info("Created repo: {}", id);
    }

    public static <T extends RepoData> RedisRepoBuilder<T> builder(Class<T> clazz) {
        return new RedisRepoBuilder<>(clazz);
    }

    public void cancelTasks() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
        if (backupTask != null && !backupTask.isCancelled()) backupTask.cancel();
    }

    public void close() {
        cancelTasks();
        saveDirty().join();
        deleteUnnecessary().join();
        backupService.saveBackup(map);
        redisManager.unregisterChannel(updateChannel, messager);
    }

    public void startTasks() {
        cancelTasks();
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    saveDirty();
                    deleteUnnecessary();
                    loadNecessary();
                } catch (Exception e) {
                    log.error("Error in save task: {}", e.getMessage());
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, saveInterval);

        if (saveBackups)
            backupTask = new BukkitRunnable() {
                @Override
                public void run() {
                    backupService.saveBackup(map);
                }
            }.runTaskTimerAsynchronously(ARC.plugin, 20L * 60 * 60 * 3, 20L * 60 * 60 * 3);
    }

    public void addContext(String context) {
        contextSet.add(context);
    }

    public void removeContext(String context) {
        contextSet.remove(context);
    }

    public Collection<T> all() {
        return map.values();
    }

    void loadAll() {
        log.trace("Loading all");
        redisManager.loadMap(storageKey)
                .thenAccept(redisMap -> {
                    for (var entry : redisMap.entrySet()) {
                        try {
                            log.trace("Loading: {}", entry);
                            T t = (T) gson.fromJson(entry.getValue(), clazz);
                            t.setDirty(false);
                            map.put(t.id(), t);
                            contextSet.add(t.id());
                            if (onUpdate != null) onUpdate.accept(t);
                        } catch (Exception e) {
                            log.error("Error: {}", e.getMessage());
                        }
                    }
                });
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<Void> load(List<String> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(null);
        log.trace("Loading all: {}", keys);
        return redisManager.loadMapEntries(storageKey, keys.toArray(String[]::new))
                .thenAccept(list -> {
                    for (int i = 0; i < list.size(); i++) {
                        var entry = list.get(i);
                        if (entry == null) {
                            log.debug("Could not find entry in storage: {}", keys);
                            lastAttempt.put(keys.get(i), System.currentTimeMillis());
                            continue;
                        }
                        try {
                            T t = (T) gson.fromJson(entry, clazz);
                            t.setDirty(false);
                            map.put(t.id(), t);
                            contextSet.add(t.id());
                            lastAttempt.remove(t.id());
                            if (onUpdate != null) onUpdate.accept(t);
                        } catch (Exception e) {
                            log.error("Could not parse: {} {}", entry, e.getMessage());
                        }
                    }
                });
    }

    CompletableFuture<Void> loadNecessary() {
        if (loadAll) {
            if (System.currentTimeMillis() - lastFullRefresh < 1000 * 60 * 5)
                return CompletableFuture.completedFuture(null);
            log.trace("Loading all (full refresh)");
            return saveDirty().thenAccept((o) -> {
                lastFullRefresh = System.currentTimeMillis();
                loadAll();
            });
        } else return loadContext();
    }

    CompletableFuture<Void> loadContext() {
        Set<String> uniqueToLoaded = new HashSet<>(map.keySet());
        Set<String> uniqueToContext = new HashSet<>(contextSet);

        uniqueToLoaded.removeAll(contextSet);
        uniqueToContext.removeAll(map.keySet());

        deleteEntries(uniqueToLoaded);

        return load(uniqueToContext.stream()
                .filter(s -> System.currentTimeMillis() - lastAttempt.getOrDefault(s, 0L) > 1000 * 60)
                .toList());
    }

    CompletableFuture<Void> saveDirty() {
        List<T> toSave = map.values().stream().filter(T::isDirty).toList();
        log.trace("Saving dirty: {}", toSave);
        return saveInStorage(toSave);
    }

    CompletableFuture<Void> deleteUnnecessary() {
        List<T> toDelete = map.values().stream().filter(T::isRemove).toList();
        if (toDelete.isEmpty()) return CompletableFuture.completedFuture(null);
        log.trace("Deleting unnecessary: {}", toDelete);
        return toDelete.stream().map(this::delete).collect(() -> CompletableFuture.completedFuture(null), CompletableFuture::allOf, CompletableFuture::allOf);
    }


    CompletableFuture<Void> deleteInStorage(Collection<T> ts) {
        if (ts.isEmpty()) return CompletableFuture.completedFuture(null);
        log.trace("Deleting in storage: {}", ts);
        return CompletableFuture.supplyAsync(() -> ts.stream()
                        .flatMap(t -> Stream.of(t.id(), null))
                        .toArray(String[]::new))
                .thenCompose(arr -> redisManager.saveMapEntries(storageKey, arr))
                .thenAccept((o) -> ts.forEach(t -> announceDelete(t.id())));
    }

    CompletableFuture<Void> saveInStorage(Collection<T> ts) {
        try {
            if (ts.isEmpty()) return CompletableFuture.completedFuture(null);
            log.trace("Saving in storage: {}", ts);
            for (T t : ts) t.dirty = false;
            return CompletableFuture.supplyAsync(() -> {
                        try {
                            String[] array = ts.stream()
                                    .flatMap(t -> Stream.of(t.id(), gson.toJson(t)))
                                    .toArray(String[]::new);
                            log.trace("Saving: {}", Arrays.toString(array));
                            return array;
                        } catch (Exception e) {
                            log.error("Could not save: {}", ts);
                            return new String[]{};
                        }
                    }).thenCompose(arr -> redisManager.saveMapEntries(storageKey, arr))
                    .thenAccept((o) -> ts.forEach(t -> announceUpdate(t.id())));
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    public void forceSave() {
        saveDirty();
    }

    void announceUpdate(String id) {
        log.trace("Announcing update: {}", id);
        T t = map.get(id);
        if (t == null) {
            log.debug("Could not find {} in storage while announcing update!", id);
            return;
        }
        Update update = new Update(id, 0);
        redisManager.publish(updateChannel, gson.toJson(update));
    }

    void announceDelete(String id) {
        log.trace("Announcing delete: {}", id);
        Update update = new Update(id, 0);
        redisManager.publish(updateChannel, gson.toJson(update));
    }

    @SuppressWarnings("unchecked")
    void receiveUpdate(String message) {
        //System.out.println("Received update: " + message);
        Update update = gson.fromJson(message, Update.class);
        if (!loadAll && !contextSet.contains(update.id)) {
            log.trace("Not in context: {}", update.id);
            return;
        }
        redisManager.loadMapEntries(storageKey, update.id)
                .thenAccept(list -> {
                    if (list == null || list.isEmpty() || list.getFirst() == null) {
                        //System.out.println("Deleting entry!");
                        deleteEntry(update.id);
                        //System.out.println("Map: " + map);
                        return;
                    }
                    //log.info("Received: " + list.get(0));
                    T t = (T) gson.fromJson(list.getFirst(), clazz);
                    T current = map.get(update.id);
                    if (current != null) current.merge(t);
                    else {
                        log.debug("Current is null when merging! Update {}", t);
                        map.put(t.id(), t);
                        contextSet.add(t.id());
                    }
                    t.dirty = false;
                    if (onUpdate != null) onUpdate.accept(t);
                });
    }

    void deleteEntries(Collection<String> ids) {
        ids.forEach(this::deleteEntry);
    }

    void deleteEntry(String id) {
        map.remove(id);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    public CompletableFuture<T> getOrCreate(@NotNull String id, @NotNull Supplier<T> supplier) {
        if (map.containsKey(id)) return CompletableFuture.completedFuture(map.get(id));
        return redisManager.loadMapEntries(storageKey, id)
                .thenApply(list -> {
                    log.trace("Received: {}", list);
                    if (list == null || list.isEmpty() || list.getFirst() == null) {
                        T t = supplier.get();
                        log.trace("Creating: {}", t);
                        create(t).join();
                        log.trace("Created: {}", t);
                        return t;
                    }
                    T t = (T) gson.fromJson(list.getFirst(), clazz);
                    map.put(t.id(), t);
                    contextSet.add(t.id());
                    return t;
                }).orTimeout(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    public CompletableFuture<T> getOrNull(String id) {
        if (map.containsKey(id)) return CompletableFuture.completedFuture(map.get(id));
        if (lastAttempt.getOrDefault(id, 0L) > System.currentTimeMillis() - 1000 * 60)
            return CompletableFuture.completedFuture(null);
        return redisManager.loadMapEntries(storageKey, id)
                .thenApply(list -> {
                    if (list == null || list.isEmpty() || list.getFirst() == null) {
                        lastAttempt.put(id, System.currentTimeMillis());
                        return null;
                    }
                    //log.info("Received: " + list.get(0));
                    T t = (T) gson.fromJson(list.getFirst(), clazz);
                    map.put(t.id(), t);
                    contextSet.add(t.id());
                    return t;
                }).orTimeout(5, TimeUnit.SECONDS);
    }

    @Nullable
    public T getNow(String string) {
        return map.get(string);
    }

    public CompletableFuture<Void> create(@NotNull T t) {
        map.put(t.id(), t);
        contextSet.add(t.id());
        return saveInStorage(List.of(t));
    }

    public CompletableFuture<Void> delete(@NotNull T t) {
        log.trace("Deleting entry: {}", t.id());
        deleteEntry(t.id());
        contextSet.remove(t.id());
        return deleteInStorage(List.of(t));
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class Update {
        String id;
        long l; // last time updated at
    }


    public static class RedisRepoBuilder<T extends RepoData> {
        private Boolean loadAll;
        private RedisManager redisManager;
        private String storageKey;
        private String updateChannel;
        private Class clazz;
        private Consumer<T> onUpdate;
        private String id;
        private Path backupFolder;
        private Long saveInterval;
        private Boolean saveBackups;

        RedisRepoBuilder(Class<T> clazz) {
            this.clazz = clazz;
        }

        public RedisRepoBuilder<T> loadAll(Boolean loadAll) {
            this.loadAll = loadAll;
            return this;
        }

        public RedisRepoBuilder<T> redisManager(RedisManager redisManager) {
            this.redisManager = redisManager;
            return this;
        }

        public RedisRepoBuilder<T> storageKey(String storageKey) {
            this.storageKey = storageKey;
            return this;
        }

        public RedisRepoBuilder<T> updateChannel(String updateChannel) {
            this.updateChannel = updateChannel;
            return this;
        }

        public RedisRepoBuilder<T> clazz(Class clazz) {
            this.clazz = clazz;
            return this;
        }

        public RedisRepoBuilder<T> onUpdate(Consumer<T> onUpdate) {
            this.onUpdate = onUpdate;
            return this;
        }

        public RedisRepoBuilder<T> id(String id) {
            this.id = id;
            return this;
        }

        public RedisRepoBuilder<T> backupFolder(Path backupFolder) {
            this.backupFolder = backupFolder;
            return this;
        }

        public RedisRepoBuilder<T> saveInterval(Long saveInterval) {
            this.saveInterval = saveInterval;
            return this;
        }

        public RedisRepoBuilder<T> saveBackups(Boolean saveBackups) {
            this.saveBackups = saveBackups;
            return this;
        }

        public RedisRepo<T> build() {
            return new RedisRepo<>(loadAll, redisManager, storageKey, updateChannel, clazz, onUpdate, id, backupFolder, saveInterval, saveBackups);
        }
    }

}
