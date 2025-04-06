package ru.arc.board;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoardEntryCache {

    private final Map<UUID, BoardItem> map = new ConcurrentHashMap<>();

    public BoardEntryCache(){
    }


    public BoardItem get(BoardEntry entry){
        BoardItem item = map.get(entry.entryUuid);
        if(item == null){
            item = generate(entry);
            map.put(entry.entryUuid, item);
        }
        return item;
    }

    public void refresh(BoardEntry boardEntry){
        BoardItem item = generate(boardEntry);
        map.put(boardEntry.entryUuid, item);
    }

    public void clear(){
        map.clear();
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
