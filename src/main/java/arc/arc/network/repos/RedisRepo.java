package arc.arc.network.repos;

import arc.arc.ARC;
import arc.arc.board.BoardEntry;
import arc.arc.network.RedisManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.*;
import lombok.extern.log4j.Log4j2;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Log4j2
public class RedisRepo<T extends RepoData> {

    ConcurrentHashMap<String, T> map;
    ConcurrentHashMap<String, Long> lastAttempt = new ConcurrentHashMap<>();
    boolean loadAll;
    boolean loadedAll = false;
    RedisManager redisManager;
    RedisRepoMessager messager;
    String storageKey, updateChannel;
    Gson gson = new Gson();
    Set<String> contextSet = new ConcurrentSkipListSet<>();
    BukkitTask saveTask;
    Class clazz;
    @Setter
    Consumer<BoardEntry> onUpdate;

    int count = 100;

    public RedisRepo(boolean loadAll, RedisManager redisManager, String storageKey, String updateChannel, Class clazz) {
        this.loadAll = loadAll;
        this.redisManager = redisManager;
        this.storageKey = storageKey;
        this.updateChannel = updateChannel;
        this.clazz = clazz;

        map = new ConcurrentHashMap<>();
        messager = new RedisRepoMessager(this, redisManager);
        redisManager.registerChannel(updateChannel, messager);

        startTasks();
    }

    public void cancelTasks() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
    }

    public void startTasks() {
        cancelTasks();
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveOrDeleteAll();
                loadOrUnloadNecessary();
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, 20L);
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

    void loadAll(List<String> keys) {
        if (keys.isEmpty()) return;
        log.trace("Loading all: " + keys);
        redisManager.loadMapEntries(storageKey, keys.toArray(String[]::new))
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
                            if (onUpdate != null) onUpdate.accept((BoardEntry) t);
                        } catch (Exception e) {
                            log.debug("Could not parse: " + entry);
                            e.printStackTrace();
                        }
                    }
                });
    }

    void loadOrUnloadNecessary() {
        if (loadAll && !loadedAll) {
            loadAll();
        } else {
            Set<String> uniqueToLoaded = new HashSet<>(map.keySet());
            Set<String> uniqueToContext = new HashSet<>(contextSet);

            uniqueToLoaded.removeAll(contextSet);
            uniqueToContext.removeAll(map.keySet());

            deleteEntries(uniqueToLoaded);

            loadAll(uniqueToContext.stream()
                    .filter(s -> System.currentTimeMillis() - lastAttempt.getOrDefault(s, 0L) > 1000 * 60)
                    .toList());
        }
    }

    void saveOrDeleteAll() {
        List<T> toDelete = new ArrayList<>();
        List<T> toSave = new ArrayList<>();
        for (var entry : map.entrySet()) {
            if (entry.getValue().isDirty()) {
                toSave.add(entry.getValue());
                entry.getValue().dirty = false;
            } else if (entry.getValue().isRemove()) toDelete.add(entry.getValue());
        }
        //System.out.println("Saving these: " + toSave);
        if (!toDelete.isEmpty()) deleteInStorage(toDelete);
        deleteEntries(toDelete.stream().map(T::id).toList());
        if (!toSave.isEmpty()) saveInStorage(toSave);
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
        loadedAll = true;
    }

    void deleteInStorage(Collection<T> ts) {
        log.trace("Deleting in storage: " + ts);
        var arr = ts.stream().flatMap(t -> Stream.of(t.id(), null))
                .toArray(String[]::new);
        redisManager.saveMapEntries(storageKey, arr)
                .thenAccept((o) -> ts.forEach(t -> announceUpdate(t.id())));
    }

    void saveInStorage(Collection<T> ts) {
        log.trace("Saving in storage: " + ts);
        for (T t : ts) t.dirty = false;
        var arr = ts.stream().flatMap(t -> Stream.of(t.id(), gson.toJson(t))).toArray(String[]::new);
        redisManager.saveMapEntries(storageKey, arr)
                .thenAccept((o) -> {
                    System.out.println("Saved in storage: " + ts);
                    ts.forEach(t -> announceUpdate(t.id()));
                });
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

    void receiveUpdate(String message) {
        log.trace("Receiving update: " + message);
        Update update = gson.fromJson(message, Update.class);
        if (!loadAll && !contextSet.contains(update.id)) {
            log.trace("Not in context: " + update.id);
            return;
        }
        redisManager.loadMapEntries(storageKey, update.id)
                .thenAccept(list -> {
                    System.out.println("Update list: " + list);
                    if (list == null || list.isEmpty() || list.get(0) == null) {
                        System.out.println("Deleting entry!");
                        deleteEntry(update.id);
                        return;
                    }
                    T t = (T) gson.fromJson(list.get(0), clazz);
                    t.dirty = false;
                    map.put(t.id(), t);
                    System.out.println("New entry to map: "+map);
                    if (onUpdate != null) onUpdate.accept((BoardEntry) t);
                });
    }

    void deleteEntries(Collection<String> ids) {
        ids.forEach(this::deleteEntry);
    }

    void deleteEntry(String id) {
        map.remove(id);
    }

    public void createNewEntry(T t) {
        System.out.println("Creating new entry: " + t.id());
        map.put(t.id(), t);
        contextSet.add(t.id());
        saveInStorage(List.of(t));
    }

    public void deleteEntry(T t) {
        System.out.println("Deleting entry: "+t.id());
        deleteEntry(t.id());
        contextSet.remove(t.id());
        deleteInStorage(List.of(t));
    }

    public T get(String string) {
        return map.get(string);
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class Update {
        String id;
        long l; // last time updated at
    }

}
