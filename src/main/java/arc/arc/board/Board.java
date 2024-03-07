package arc.arc.board;

import arc.arc.ARC;
import arc.arc.configs.BoardConfig;
import arc.arc.configs.Config;
import arc.arc.network.repos.RedisRepo;
import arc.arc.xserver.announcements.AnnounceManager;
import arc.arc.xserver.announcements.AnnouncementData;
import arc.arc.xserver.announcements.PermissionCondition;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class Board {

    //private final Map<UUID, BoardEntry> boardMap = new ConcurrentHashMap<>();
    private final BoardEntryCache cache = new BoardEntryCache();
    private static volatile Board board = new Board();
    RedisRepo<BoardEntry> repo;
    BukkitTask updateCacheTask;
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
        createRepo();
        setupTask();
    }

    private void createRepo() {
        repo = new RedisRepo<>(true, ARC.redisManager, "arc.board", "arc.board_update", BoardEntry.class);
        repo.setOnUpdate(entry -> updateCache(entry.entryUuid));
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
        announceTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (BoardConfig.mainServer) announceNext();
            }
        }.runTaskTimer(ARC.plugin, 120L, 20L * BoardConfig.secondsAnnounce);
    }

    public void announceNext() {
        repo.all().stream().min(Comparator.comparingLong(BoardEntry::getLastShown))
                .ifPresent(e -> {
                    e.setLastShown(System.currentTimeMillis());
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
        if (announceTask != null && !announceTask.isCancelled()) announceTask.cancel();
    }

    public List<BoardItem> items() {
        return repo.all().stream()
                .map(cache::get)
                .toList();
    }

    public void addBoardEntry(BoardEntry boardEntry) {
        if (boardEntry == null) return;
        repo.createNewEntry(boardEntry);
        updateCache(boardEntry.entryUuid);
    }

    public void deleteBoardEntry(BoardEntry entry) {
        repo.deleteEntry(entry);
        updateCache(entry.entryUuid);
    }

    public void updateCache(UUID uuid) {
        BoardEntry boardEntry = repo.get(uuid.toString());
        if (boardEntry != null) cache.refresh(boardEntry);
        else cache.remove(uuid);
    }

    public void updateAllCache() {
        cache.clear();
        repo.all().forEach(cache::refresh);
    }
}
