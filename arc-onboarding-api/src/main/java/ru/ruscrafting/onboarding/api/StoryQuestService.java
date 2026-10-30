package ru.ruscrafting.onboarding.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * ARC-owned authored quests, exposed to the shared journal without copying quest state.
 * Resolve through Bukkit ServicesManager and call on the Paper thread. ARC owns these
 * classes at runtime; consumers compile against the API without shading it.
 */
public interface StoryQuestService {
    enum Status { AVAILABLE, ACTIVE, COMPLETED }

    /** Plain, player-localized text. Opening a quest remains the owner's responsibility. */
    record Quest(String id, String title, String summary, String currentGoal,
                 int completedSteps, int totalSteps, Status status, boolean tracked) {
        public Quest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(currentGoal, "currentGoal");
            Objects.requireNonNull(status, "status");
            if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid story quest id");
            if (title.isBlank() || title.length() > 160 || title.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid story quest title");
            if (summary.length() > 2048 || currentGoal.length() > 512)
                throw new IllegalArgumentException("Story quest text exceeds journal bounds");
            if (totalSteps < 1 || totalSteps > 128 || completedSteps < 0 || completedSteps > totalSteps)
                throw new IllegalArgumentException("Invalid story quest progress");
            if (tracked && status != Status.ACTIVE)
                throw new IllegalArgumentException("Only an active story quest can be tracked");
        }
    }

    /** True only after this player's persisted quests and tracking selection have loaded. */
    boolean ready(UUID playerId);

    /** Immutable cached snapshot, at most 64 entries, with at most one tracked quest. No I/O. */
    List<Quest> quests(UUID playerId);

    /** Opens the owner's current detail screen while preserving the existing dialog history. */
    boolean open(UUID playerId, String questId);

    /**
     * Persists the selected ACTIVE quest; null clears story tracking. Never accepts a quest,
     * resets progress, or issues rewards. Completion acknowledges the durable selection;
     * a failed save preserves the previous visible selection. ARC owns story HUD and cues.
     */
    CompletionStage<Void> track(UUID playerId, String questId);
}
