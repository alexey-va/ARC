package ru.arc.dungeons.api;
import java.util.UUID;
/** ARC's existing staff cooldown clock; main-thread calls only. */
public interface ArcStaffCooldowns {
    long remainingTicks(UUID player);
    void start(UUID player, long ticks);
    void clear(UUID player);
}
