package ru.ruscrafting.onboarding.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Optional ArcCasino-owned admission for one free physical round of each introductory game.
 * ARC owns these unshaded API classes; consumers resolve the provider through ServicesManager.
 * Invoke on the Paper thread. Arming never starts a round or changes the player's balance.
 * COMPLETE is returned only after the casino's terminal receipt is durably retained.
 * A completed entitlement is never reset by reopening or restarting an onboarding quest.
 */
public interface CasinoIntroService {
    enum Game { SLOTS, BLACKJACK, PLINKO }
    enum State { AVAILABLE, ARMED, PLAYING, COMPLETE, UNAVAILABLE }

    CompletionStage<State> arm(UUID playerId, Game game);
    CompletionStage<State> state(UUID playerId, Game game);
}
