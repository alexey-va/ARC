package arc.arc.board;

import arc.arc.ARC;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import lombok.Setter;
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



    public static Board instance(){
        if(board == null){
            synchronized (Board.class){
                if(board == null) board = new Board();
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
                updateAllCache();
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, 20 * 60L);
    }

    public void cancelTasks(){
        if(updateCacheTask != null && !updateCacheTask.isCancelled()) updateCacheTask.cancel();
    }

    public List<BoardItem> items(){
        return boardMap.values().stream().map(cache::get).toList();
    }

    public void addBoardEntry(BoardEntry boardEntry, boolean redisSync) {
        if(boardEntry == null) return;
        boardMap.put(boardEntry.entryUuid, boardEntry);
        updateCache(boardEntry.entryUuid);
        saveBoardEntry(boardEntry.entryUuid);
    }



    private Map<String, String> serialize(){
        Map<String, String> map = new HashMap<>();
        for(var entry : boardMap.entrySet()){
            map.put(entry.getKey().toString(), RedisSerializer.toJson(entry.getValue()));
        }
        return map;
    }

    public void loadBoardEntry(UUID uuid) {
        ARC.redisManager.loadMapEntry("arc.board", uuid.toString())
                .thenAccept(list -> {
                    if(list.isEmpty() || list.get(0) == null || list.get(0).equals("null")){
                        deleteBoard(uuid, false);
                        return;
                    }
                    BoardEntry boardEntry = RedisSerializer.fromJson(list.get(0), BoardEntry.class);
                    addBoardEntry(boardEntry, false);
                });
    }

    public void saveBoardEntry(UUID uuid){
        BoardEntry entry = boardMap.get(uuid);
        CompletableFuture.supplyAsync(() -> RedisSerializer.toJson(entry))
                        .thenAccept(json ->ARC.redisManager.saveMapKey("arc.board", uuid.toString(), json));

    }

    public void deleteBoard(UUID uuid, boolean redisSync) {
        boardMap.remove(uuid);
        updateCache(uuid);
        if(redisSync){
            messager.sendUpdate(uuid);
        }
    }

    public void updateCache(UUID uuid){
        BoardEntry boardEntry = boardMap.get(uuid);
        if(boardEntry != null) cache.refresh(boardEntry);
        else cache.remove(uuid);
    }

    public void updateAllCache() {
        cache.clear();
        boardMap.values().forEach(cache::refresh);
    }

    public void saveBoard() {
        CompletableFuture.supplyAsync(this::serialize)
                .thenAccept(map -> ARC.redisManager.saveMap("arc.board", map));
    }

    private void loadBoard() {
        ARC.redisManager.loadMap("arc.board")
                .thenAccept(map -> {
                    boardMap.clear();
                    for(var entry : map.entrySet()){
                        UUID uuid = UUID.fromString(entry.getKey());
                        BoardEntry boardEntry = RedisSerializer.fromJson(entry.getValue(), BoardEntry.class);
                        if(boardEntry == null) continue;
                        boardMap.put(uuid, boardEntry);
                    }
                });
    }

}
