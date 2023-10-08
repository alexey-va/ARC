package arc.arc.board;

import arc.arc.ARC;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class Board {

    private static BukkitTask updateTask;
    private static BukkitTask announceTask;
    private static int count = 0;
    public final Map<UUID, BoardEntry> boardMap = new HashMap<>();
    public final List<CachedItem> itemCache = new ArrayList<>();

    public Board() {
        loadBoard();
        setupTask();
    }

    private void setupTask() {
        if (updateTask != null && !updateTask.isCancelled()) updateTask.cancel();
        if (announceTask != null && !announceTask.isCancelled()) announceTask.cancel();
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                List<UUID> toRemove = new ArrayList<>();
                for (var entry : boardMap.entrySet()) {
                    if (entry.getValue().isExpired()) toRemove.add(entry.getKey());
                }
                toRemove.forEach(b -> deleteBoard(b, true));

                if(count++ > 10){
                    count =0;
                    synchronized (itemCache) {
                        updateItemCache();
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 20L, 60L);

        announceTask = new BukkitRunnable() {
            @Override
            public void run() {
                BoardEntry entry = null;
                for (BoardEntry e : boardMap.values()) {
                    if (entry == null || e.lastShown < entry.lastShown) entry = e;
                }

                if (entry == null) return;
                entry.lastShown = System.currentTimeMillis();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "bossbarmsg all -sec:15 -t:1s -n:board_announce -c:blue -s:1 &7[&6" + entry.playerName + "&7] " + entry.tldr
                );
            }
        }.runTaskTimer(ARC.plugin, 100, 60 * 20L);
    }

    public void addBoard(BoardEntry boardEntry) {
        boardMap.put(boardEntry.uuid, boardEntry);
        synchronized (itemCache) {
            updateItemCache();
        }
        ARC.plugin.redisManager.publishBoardEntry(boardEntry);
    }

    public void saveBoard(BoardEntry boardEntry) {
        synchronized (itemCache) {
            updateItemCache();
        }
        ARC.plugin.redisManager.publishBoardEntry(boardEntry);
    }

    public void loadBoardEntry(UUID uuid) {
        ARC.plugin.redisManager.getBoardEntry(uuid).thenAccept(entry -> {
            synchronized (boardMap) {
                boardMap.put(uuid, entry);
            }
            synchronized (itemCache) {
                updateItemCache();
            }
        });
    }

    public void deleteBoard(UUID uuid, boolean redisSync) {
        BoardEntry entry = boardMap.get(uuid);
        if (entry != null) {
            boardMap.remove(uuid);
            synchronized (itemCache) {
                updateItemCache();
            }
            if (redisSync) ARC.plugin.redisManager.deleteBoardEntry(uuid);
        }
    }

    public void updateItemCache() {
        synchronized (boardMap) {
            itemCache.clear();
            for (BoardEntry entry : boardMap.values()) {
                itemCache.add(new CachedItem(entry));
            }
            itemCache.sort((b1, b2) -> (int) (b2.boardEntry.timestamp - b1.boardEntry.timestamp));
        }

    }

    private void loadBoard() {
        ARC.plugin.redisManager.getBoardEntries().thenAccept(list -> {
            System.out.print("Got this:" + list);
            synchronized (boardMap) {
                boardMap.clear();
                list.forEach(entry -> boardMap.put(entry.uuid, entry));
            }
            synchronized (itemCache) {
                updateItemCache();
            }
        });
    }

    public static class CachedItem {
        public ItemStack stack;
        public BoardEntry boardEntry;

        public CachedItem(BoardEntry boardEntry) {
            this.boardEntry = boardEntry;
            this.stack = boardEntry.getItem();
        }
    }

}
