package arc.arc.sync.base;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;

import java.util.UUID;

public interface Sync {

    default void forceSave(UUID uuid){}
    default void playerJoin(UUID uuid) {}
    default void playerQuit(UUID uuid) {}
    default void processEvent(Event event) {}

}
