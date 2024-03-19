package arc.arc.network.repos;

import arc.arc.ARC;
import arc.arc.board.BoardEntry;
import arc.arc.network.RedisManager;
import com.google.gson.Gson;
import lombok.*;
import lombok.extern.log4j.Log4j2;
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
public class RedisRepo<T extends RepoData> {

    ConcurrentHashMap<String, T> map = new ConcurrentHashMap<>();
    ConcurrentHashMap<String, Long> lastAttempt = new ConcurrentHashMap<>();
    RedisRepoMessager messager;
    Gson gson = new Gson();
    Set<String> contextSet = new ConcurrentSkipListSet<>();
    BukkitTask saveTask, backupTask;
    long lastFullRefresh;


    @Setter
    Consumer<BoardEntry> onUpdate;
    BackupService backupService;
    Class clazz;
    String storageKey, updateChannel, id;
    RedisManager redisManager;
    boolean loadAll;
    long saveInterval;
    boolean saveBackups;

    private RedisRepo(Boolean loadAll, RedisManager redisManager, String storageKey, String updateChannel, Class clazz,
                      Consumer<BoardEntry> onUpdate, String id, Path backupFolder, Long saveInterval, Boolean saveBackups) {
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
        backupService = new BackupService(id, backupFolder);

        startTasks();
        log.info("Created repo: " + id);
    }

    public static <T extends RepoData> RedisRepoBuilder<T> builder(Class<T> clazz) {
        return new RedisRepoBuilder<>(clazz);
    }

    public void cancelTasks() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
        if (backupTask != null && !backupTask.isCancelled()) backupTask.cancel();
    }

    public void startTasks() {
        cancelTasks();
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveDirty();
                deleteUnnecessary();
                loadNecessary();
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, 20L);

        if (saveBackups)
            backupTask = new BukkitRunnable() {
                @Override
                public void run() {
                    backupService.saveBackup(map);
                }
            }.runTaskTimerAsynchronously(ARC.plugin, 20L * 60, 20L * 60 * 60);
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
                            log.trace("Loading: " + entry);
                            T t = (T) gson.fromJson(entry.getValue(), clazz);
                            t.setDirty(false);
                            map.put(t.id(), t);
                            contextSet.add(t.id());
                            if (onUpdate != null) onUpdate.accept((BoardEntry) t);
                        } catch (Exception e) {
                            log.debug("Could not parse: " + entry);
                            e.printStackTrace();
                        }
                    }
                });
    }

    CompletableFuture<Void> load(List<String> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(null);
        log.trace("Loading all: " + keys);
        return redisManager.loadMapEntries(storageKey, keys.toArray(String[]::new))
                .thenAccept(list -> {
                    for (int i = 0; i < list.size(); i++) {
                        var entry = list.get(i);
                        if (entry == null) {
                            log.debug("Could not find entry in storage: " + keys);
                            lastAttempt.put(keys.get(i), System.currentTimeMillis());
                            continue;
                        }
                        try {
                            T t = (T) gson.fromJson(entry, clazz);
                            t.setDirty(false);
                            map.put(t.id(), t);
                            contextSet.add(t.id());
                            lastAttempt.remove(t.id());
                            if (onUpdate != null) onUpdate.accept((BoardEntry) t);
                        } catch (Exception e) {
                            log.debug("Could not parse: " + entry);
                            e.printStackTrace();
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
        return saveInStorage(toSave);
    }

    CompletableFuture<Void> deleteUnnecessary() {
        List<T> toDelete = map.values().stream().filter(T::isRemove).toList();
        log.trace("Deleting unnecessary: {}" , toDelete);
        return toDelete.stream().map(this::delete).collect(() -> CompletableFuture.completedFuture(null), CompletableFuture::allOf, CompletableFuture::allOf);
    }


    CompletableFuture<Void> deleteInStorage(Collection<T> ts) {
        if (ts.isEmpty()) return CompletableFuture.completedFuture(null);
        log.trace("Deleting in storage: " + ts);
        return CompletableFuture.supplyAsync(() -> ts.stream()
                        .flatMap(t -> Stream.of(t.id(), null))
                        .toArray(String[]::new))
                .thenCompose(arr -> redisManager.saveMapEntries(storageKey, arr))
                .thenAccept((o) -> ts.forEach(t -> announceDelete(t.id())));
    }

    CompletableFuture<Void> saveInStorage(Collection<T> ts) {
        if (ts.isEmpty()) return CompletableFuture.completedFuture(null);
        log.trace("Saving in storage: " + ts);
        for (T t : ts) t.dirty = false;
        return CompletableFuture.supplyAsync(() -> ts.stream()
                        .flatMap(t -> Stream.of(t.id(), gson.toJson(t)))
                        .toArray(String[]::new))
                .thenCompose(arr -> redisManager.saveMapEntries(storageKey, arr))
                .thenAccept((o) -> ts.forEach(t -> announceUpdate(t.id())));
    }

    public void forceSave() {
        saveDirty();
    }

    void announceUpdate(String id) {
        log.trace("Announcing update: " + id);
        T t = map.get(id);
        if (t == null) {
            log.debug("Could not find " + id + " in storage while announcing update!");
            return;
        }
        Update update = new Update(id, 0);
        redisManager.publish(updateChannel, gson.toJson(update));
    }

    void announceDelete(String id) {
        log.trace("Announcing delete: " + id);
        Update update = new Update(id, 0);
        redisManager.publish(updateChannel, gson.toJson(update));
    }

    void receiveUpdate(String message) {
        log.trace("Receiving update: " + message);
        Update update = gson.fromJson(message, Update.class);
        if (!loadAll && !contextSet.contains(update.id)) {
            log.trace("Not in context: " + update.id);
            return;
        }
        redisManager.loadMapEntries(storageKey, update.id)
                .thenAccept(list -> {
                    if (list == null || list.isEmpty() || list.get(0) == null) {
                        System.out.println("Deleting entry!");
                        deleteEntry(update.id);
                        System.out.println("Map: " + map);
                        return;
                    }
                    T t = (T) gson.fromJson(list.get(0), clazz);
                    T current = map.get(update.id);
                    if (current != null) current.merge(t);
                    else {
                        map.put(t.id(), t);
                        contextSet.add(t.id());
                    }
                    t.dirty = false;
                    if (onUpdate != null) onUpdate.accept((BoardEntry) t);
                });
    }

    void deleteEntries(Collection<String> ids) {
        ids.forEach(this::deleteEntry);
    }

    void deleteEntry(String id) {
        map.remove(id);
    }

    @NotNull
    public CompletableFuture<T> getOrCreate(@NotNull String id, @NotNull Supplier<T> supplier) {
        if (map.containsKey(id)) return CompletableFuture.completedFuture(map.get(id));
        return redisManager.loadMapEntries(storageKey, id)
                .thenApply(list -> {
                    if (list == null || list.isEmpty() || list.get(0) == null) {
                        T t = supplier.get();
                        create(t);
                        return t;
                    }
                    T t = (T) gson.fromJson(list.get(0), clazz);
                    map.put(t.id(), t);
                    contextSet.add(t.id());
                    return t;
                }).orTimeout(5, TimeUnit.SECONDS);
    }

    @NotNull
    public CompletableFuture<T> getOrNull(String id) {
        if (map.containsKey(id)) return CompletableFuture.completedFuture(map.get(id));
        return redisManager.loadMapEntries(storageKey, id)
                .thenApply(list -> {
                    if (list == null || list.isEmpty() || list.get(0) == null) {
                        return null;
                    }
                    T t = (T) gson.fromJson(list.get(0), clazz);
                    map.put(t.id(), t);
                    contextSet.add(t.id());
                    return t;
                }).orTimeout(5, TimeUnit.SECONDS);
    }

    @Nullable
    public T getNow(String string) {
        return map.get(string);
    }

    public void create(@NotNull T t) {
        map.put(t.id(), t);
        contextSet.add(t.id());
        saveInStorage(List.of(t));
    }

    public CompletableFuture<Void> delete(@NotNull T t) {
        log.trace("Deleting entry: " + t.id());
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
        private Consumer<BoardEntry> onUpdate;
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

        public RedisRepoBuilder<T> onUpdate(Consumer<BoardEntry> onUpdate) {
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
