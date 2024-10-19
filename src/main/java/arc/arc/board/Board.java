package arc.arc.board;

import arc.arc.ARC;
import arc.arc.configs.BoardConfig;
import arc.arc.network.repos.RedisRepo;
import arc.arc.xserver.XCondition;
import arc.arc.xserver.XMessage;
import arc.arc.xserver.announcements.AnnounceManager;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class Board {

    private static final BoardEntryCache cache = new BoardEntryCache();
    static RedisRepo<BoardEntry> repo;
    static BukkitTask updateCacheTask;
    static BukkitTask announceTask;

    public static void init() {
        createRepo();
        setupTask();
    }

    private static void createRepo() {
        if (repo != null) repo.close();
        repo = RedisRepo.builder(BoardEntry.class)
                .loadAll(true)
                .redisManager(ARC.redisManager)
                .storageKey("arc.board")
                .updateChannel("arc.board_update")
                .clazz(BoardEntry.class)
                .onUpdate(entry -> updateCache(entry.entryUuid))
                .id("board")
                .backupFolder(ARC.plugin.getDataFolder().toPath().resolve("backups/board"))
                .saveInterval(20L)
                .saveBackups(false)
                .build();
    }

    private static void setupTask() {
        cancelTasks();
        updateCacheTask = new BukkitRunnable() {
            @Override
            public void run() {
                //System.out.println("Updating items cache");
                updateAllCache();
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, 20 * 60L);
        announceTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (BoardConfig.mainServer) announceNext();
            }
        }.runTaskTimer(ARC.plugin, 120L, 20L * BoardConfig.secondsAnnounce);
    }

    public static void announceNext() {
        repo.all().stream().min(Comparator.comparingLong(BoardEntry::getLastShown))
                .ifPresent(e -> {
                    e.changeLastShown(System.currentTimeMillis());
                    XMessage xMessage = XMessage.builder()
                            .serializedMessage("&7[&6" + e.playerName + "&7]&r " + e.title)
                            .serializationType(XMessage.SerializationType.LEGACY)
                            .type(XMessage.Type.BOSS_BAR)
                            .barColor(e.type.color)
                            .seconds(10)
                            .conditions(List.of(XCondition.ofPermission(BoardConfig.receivePermission)))
                            .build();
                    AnnounceManager.announce(xMessage);
                });
    }

    public static void cancelTasks() {
        if (updateCacheTask != null && !updateCacheTask.isCancelled()) updateCacheTask.cancel();
        if (announceTask != null && !announceTask.isCancelled()) announceTask.cancel();
    }

    public static List<BoardItem> items() {
        return repo.all().stream()
                .map(cache::get)
                .toList();
    }

    public static void addBoardEntry(BoardEntry boardEntry) {
        if (boardEntry == null) return;
        repo.create(boardEntry);
        updateCache(boardEntry.entryUuid);
    }

    public static void deleteBoardEntry(BoardEntry entry) {
        repo.delete(entry);
        updateCache(entry.entryUuid);
    }

    public static void updateCache(UUID uuid) {
        BoardEntry boardEntry = repo.getNow(uuid.toString());
        if (boardEntry != null) cache.refresh(boardEntry);
        else cache.remove(uuid);
    }

    public static void updateAllCache() {
        cache.clear();
        repo.all().forEach(cache::refresh);
    }
}
