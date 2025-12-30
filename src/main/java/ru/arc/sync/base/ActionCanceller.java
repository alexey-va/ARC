package ru.arc.sync.base;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.bukkit.event.Event;

import static ru.arc.util.Logging.debug;

public class ActionCanceller {

    final Map<Class<? extends Event>, Consumer<Event>> cancellers = new ConcurrentHashMap<>();
    Map<UUID, Long> preventUntil = new ConcurrentHashMap<>();
    Future<?> pruneTask;

    public ActionCanceller() {
    }

    public <T extends Event> void registerCanceller(Class<T> clazz, Consumer<T> canceller) {
        cancellers.put(clazz, (Consumer<Event>) canceller);
    }

    public boolean checkAndCancel(UUID playerUuid, Event event) {
        Long until = preventUntil.get(playerUuid);
        if (until != null) {
            if (until > System.currentTimeMillis()) {
                debug("Prevented event " + event.getClass().getSimpleName() + " for " + playerUuid);
                debug("Prevented until " + until);
                debug("Current time " + System.currentTimeMillis());
                var canceller = cancellers.get(event.getClass());
                if (canceller != null){
                    canceller.accept(event);
                    return true;
                }
                return false;
            } else {
                preventUntil.remove(playerUuid);
            }
        }
        return false;
    }

    public void addTemporaryProtection(UUID uuid, long ms) {
        preventUntil.put(uuid, System.currentTimeMillis() + ms);
    }

    public void stopTasks() {
        if (pruneTask != null && !pruneTask.isCancelled()) pruneTask.cancel(true);
    }


}
