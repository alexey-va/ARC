package ru.ruscrafting.onboarding.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Optional ARC-owned initial quest integration, resolved through Bukkit ServicesManager.
 * Call on the Paper thread after the native preview or durable construction transition.
 * The returned stage acknowledges persistence; failures never roll back a committed build.
 * Missing players are never enrolled. Completion must match the previously recorded operation.
 */
public interface OnboardingBuildProgressService {
    enum Stage { BOOK_OPENED, BUILD_STARTED, BUILD_COMPLETED }

    CompletionStage<Void> record(UUID playerId, Stage stage, String world, int blockX, int blockZ, String operationId);
}
