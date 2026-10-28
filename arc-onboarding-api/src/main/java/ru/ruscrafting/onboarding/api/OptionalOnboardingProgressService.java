package ru.ruscrafting.onboarding.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * ARC-owned acknowledgements of real gameplay for the optional introductory quests.
 * Call on the Paper thread after the native successful transition. The returned stage
 * acknowledges quest persistence; failure never rolls back the provider's completed action.
 * No method enrolls a player, issues a reward, or resets an existing completion.
 */
public interface OptionalOnboardingProgressService {
    CompletionStage<Void> recordCasinoTrial(UUID playerId, CasinoIntroService.Game game, String receiptId);
    CompletionStage<Void> recordFarmShift(UUID playerId, String eventId);
    CompletionStage<Void> recordPrimisIntro(UUID playerId, String questId);
}
