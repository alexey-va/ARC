package arc.arc.board;

import arc.arc.ARC;
import arc.arc.configs.BoardConfig;
import arc.arc.configs.Config;
import arc.arc.network.RedisSerializer;
import arc.arc.xserver.announcements.AnnounceManager;
import arc.arc.xserver.announcements.AnnouncementData;
import arc.arc.xserver.announcements.PermissionCondition;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class Board {

    private final Map<UUID, BoardEntry> boardMap = new ConcurrentHashMap<>();
    private final BoardEntryCache cache = new BoardEntryCache();
    private static volatile Board board = new Board();
    BukkitTask updateCacheTask;
    @Setter
    BoardMessager messager;
    BukkitTask evictExpiredTask;
    BukkitTask announceTask;


    public static Board instance() {
        if (board == null) {
            synchronized (Board.class) {
                if (board == null) board = new Board();
            }
        }
        return board;
    }

    private Board() {
        loadBoard();
        setupTask();
    }

    private void setupTask() {
        cancelTasks();
        updateCacheTask = new BukkitRunnable() {
            @Override
            public void run() {
                //System.out.println("Updating items cache");
                updateAllCache();
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, 20 * 60L);

        evictExpiredTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!BoardConfig.mainServer) return;
                boardMap.values().stream()
                        .filter(be -> be.tillExpire() <= 0)
                        .forEach(be -> deleteBoard(be.entryUuid, true));
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, 20L * 60L);

        announceTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (BoardConfig.mainServer) announceNext();
            }
        }.runTaskTimer(ARC.plugin, 120L, 20L * BoardConfig.secondsAnnounce);
    }

    public void announceNext() {
        boardMap.values().stream().min(Comparator.comparingLong(BoardEntry::getLastShown))
                .ifPresent(e -> {
                    e.setLastShown(System.currentTimeMillis());
                    saveBoardEntry(e.entryUuid);

                    AnnouncementData data = AnnouncementData.builder()
                            .message("&7[&6" + e.playerName + "&7]&r " + e.title)
                            .minimessage(false)
                            .originServer(Config.server)
                            .seconds(10)
                            .bossBarColor(e.type.color)
                            .type(AnnouncementData.Type.BOSSBAR)
                            .everywhere(true)
                            .arcConditions(List.of(new PermissionCondition(BoardConfig.receivePermission)))
                            .build();
                    AnnounceManager.announceGlobally(data);
                });
    }

    public void cancelTasks() {
        if (updateCacheTask != null && !updateCacheTask.isCancelled()) updateCacheTask.cancel();
        if (evictExpiredTask != null && !evictExpiredTask.isCancelled()) evictExpiredTask.cancel();
        if (announceTask != null && !announceTask.isCancelled()) announceTask.cancel();
    }

    public List<BoardItem> items() {
        return boardMap.values().stream().map(cache::get).toList();
    }

    public void addBoardEntry(BoardEntry boardEntry, boolean redisSync) {
        if (boardEntry == null) return;
        boardMap.put(boardEntry.entryUuid, boardEntry);
        updateCache(boardEntry.entryUuid);
        if (redisSync) saveBoardEntry(boardEntry.entryUuid);
    }


    private Map<String, String> serialize() {
        Map<String, String> map = new HashMap<>();
        for (var entry : boardMap.entrySet()) {
            map.put(entry.getKey().toString(), RedisSerializer.toJson(entry.getValue()));
        }
        return map;
    }

    public void loadBoardEntry(UUID uuid) {
        ARC.redisManager.loadMapEntry("arc.board", uuid.toString())
                .thenAccept(list -> {
                    System.out.println("Updating "+uuid+" "+list);
                    if (list.isEmpty() || list.get(0) == null || list.get(0).equals("null")) {
                        deleteBoard(uuid, false);
                        return;
                    }
                    BoardEntry boardEntry = RedisSerializer.fromJson(list.get(0), BoardEntry.class);
                    addBoardEntry(boardEntry, false);
                });
    }

    public void saveBoardEntry(UUID uuid) {
        BoardEntry entry = boardMap.get(uuid);
        if (entry == null) {
            //System.out.println("No entry with "+uuid+" found!");
            return;
        }
        //System.out.println("Saving entry "+uuid);
        if (!entry.dirty) return;
        entry.setDirty(false);
        CompletableFuture.supplyAsync(() -> RedisSerializer.toJson(entry))
                .thenAccept(json -> ARC.redisManager.saveMapKey("arc.board", uuid.toString(), json));
        Bukkit.getScheduler().runTaskLater(ARC.plugin, () -> messager.sendUpdate(uuid), 20L);
    }

    public void deleteBoard(UUID uuid, boolean redisSync) {
        boardMap.remove(uuid);
        updateCache(uuid);
        //System.out.println("Deleting: "+uuid);
        if (redisSync) {
            //System.out.println("Syncing delete: "+uuid);
            ARC.redisManager.saveMapKey("arc.board", uuid.toString(), null);
            Bukkit.getScheduler().runTaskLater(ARC.plugin, () -> messager.sendUpdate(uuid), 5L);
        }
    }

    public void updateCache(UUID uuid) {
        BoardEntry boardEntry = boardMap.get(uuid);
        if (boardEntry != null) cache.refresh(boardEntry);
        else cache.remove(uuid);
    }

    public void updateAllCache() {
        cache.clear();
        boardMap.values().forEach(cache::refresh);
    }

    public void saveBoard() {
        CompletableFuture.supplyAsync(this::serialize)
                .thenAccept(map -> {
                    ARC.redisManager.saveMap("arc.board", map);

                });
    }

    private void loadBoard() {
        ARC.redisManager.loadMap("arc.board")
                .thenAccept(map -> {
                    boardMap.clear();
                    //System.out.println("Loaded map: "+map);
                    for (var entry : map.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            BoardEntry boardEntry = RedisSerializer.fromJson(entry.getValue(), BoardEntry.class);
                            //System.out.println("Reading "+boardEntry.entryUuid);

                            if (boardEntry == null) continue;
                            boardEntry.setDirty(false);
                            boardMap.put(uuid, boardEntry);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
    }

}
