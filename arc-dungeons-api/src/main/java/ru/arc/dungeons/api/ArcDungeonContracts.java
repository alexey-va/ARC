package ru.arc.dungeons.api;
import java.util.Set;
import java.util.UUID;
/** The contract ledger stays in ARC; dungeon events keep its admission and recovery gates. */
public interface ArcDungeonContracts {
    enum Admission { NOT_PROTECTED, AUTHORIZED, DENIED }
    Admission authorizeInstance(String blueprintWorld, String instanceWorld);
    void cancelInstance(String instanceWorld);
    /** Null means an ordinary world; false denies admission to a protected instance. */
    Boolean playerAuthorized(String instanceWorld, UUID player);
    String runId(String instanceWorld);
    void started(String runId, String world, Set<String> participants, String instanceWorld);
    void completed(String runId, String world, Set<String> participants, String instanceWorld);
    void resumeRewards(UUID player);
}
