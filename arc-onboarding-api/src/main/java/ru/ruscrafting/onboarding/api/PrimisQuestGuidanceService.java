package ru.ruscrafting.onboarding.api;

import java.util.UUID;

/** Read-only, main-thread projection of EliteMobs' own loaded first-quest state. */
public interface PrimisQuestGuidanceService {
    enum Stage { UNAVAILABLE, NOT_ACCEPTED, ACTIVE_OBJECTIVE, READY_TO_TURN_IN, COMPLETED }
    Stage stage(UUID playerId);
}
