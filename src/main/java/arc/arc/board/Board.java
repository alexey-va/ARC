package arc.arc.board;

import arc.arc.ARC;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Board {

    private final Map<UUID, BoardEntry> boardMap = new ConcurrentHashMap<>();
    private final BoardEntryCache cache = new BoardEntryCache();
    private static volatile Board board = new Board();



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

    }

    public List<BoardItem> items(){
        return boardMap.values().stream().map(cache::get).toList();
    }

    public void addBoard(BoardEntry boardEntry) {
    }

    public void saveBoard(BoardEntry boardEntry) {
    }

    public void loadBoardEntry(UUID uuid) {
    }

    public void deleteBoard(UUID uuid, boolean redisSync) {

    }

    public void updateItemCache() {

    }

    private void loadBoard() {
    }

}
