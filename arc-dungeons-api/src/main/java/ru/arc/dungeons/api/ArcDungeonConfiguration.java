package ru.arc.dungeons.api;
import java.nio.file.Path;
import ru.arc.paper.api.ArcSqlSettings;
/** Canonical legacy storage identity, owned by ARC. Never log the SQL credentials. */
public interface ArcDungeonConfiguration {
    /** Host preparation is asynchronous. Read configuration only after successful readiness. */
    default java.util.concurrent.CompletableFuture<Void> ready() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    Path legacyDataPath();
    String serverName();
    boolean sharedRedisAvailable();
    /** Changes only when the host replaces or removes its primary Redis binding. */
    long sharedRedisGeneration();
    ArcSqlSettings lostLootSqlSettings();
}
