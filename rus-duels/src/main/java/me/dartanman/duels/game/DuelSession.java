package me.dartanman.duels.game;

import java.util.UUID;
import me.dartanman.duels.model.Arena;
import me.dartanman.duels.model.DuelMode;
import org.bukkit.scheduler.BukkitTask;

public final class DuelSession {
    public enum State { COUNTDOWN, ACTIVE, ENDING }

    private final UUID first;
    private final UUID second;
    private final Arena arena;
    private final DuelMode mode;
    private State state = State.COUNTDOWN;
    private BukkitTask countdownTask;

    public DuelSession(UUID first, UUID second, Arena arena, DuelMode mode) {
        this.first = first;
        this.second = second;
        this.arena = arena;
        this.mode = mode;
    }

    public UUID first() { return first; }
    public UUID second() { return second; }
    public Arena arena() { return arena; }
    public DuelMode mode() { return mode; }
    public State state() { return state; }
    public void state(State state) { this.state = state; }
    public BukkitTask countdownTask() { return countdownTask; }
    public void countdownTask(BukkitTask task) { this.countdownTask = task; }

    public UUID opponent(UUID playerId) {
        if (first.equals(playerId)) return second;
        if (second.equals(playerId)) return first;
        throw new IllegalArgumentException("Player is not in this duel");
    }

    public void cancelCountdown() {
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = null;
    }
}
