package arc.arc.board;

import arc.arc.ARC;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoardEntryCache {

    private final BukkitTask cacheUpdateTask;
    private final Map<UUID, BoardItem> map = new ConcurrentHashMap<>();

    public BoardEntryCache(){
        cacheUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                map.entrySet().forEach(e -> e.setValue(generate(e.getValue().getEntry())));
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 100L, 100L);
    }

    public void cancel(){
        if(cacheUpdateTask != null && !cacheUpdateTask.isCancelled()) cacheUpdateTask.cancel();
    }

    public BoardItem get(BoardEntry entry){
        BoardItem item = map.get(entry.entryUuid);
        if(item == null){
            item = generate(entry);
            map.put(entry.entryUuid, item);
        }
        return item;
    }

    public void remove(UUID uuid){
        map.remove(uuid);
    }

    private BoardItem generate(BoardEntry entry){
        return BoardItem.builder()
                .entry(entry)
                .stack(entry.item()).build();
    }

}
