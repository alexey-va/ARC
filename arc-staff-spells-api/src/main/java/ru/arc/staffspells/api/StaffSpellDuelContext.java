package ru.arc.staffspells.api;

import java.util.UUID;
import java.util.Objects;

/** Immutable match and round identity captured when a duel spell is cast. */
public record StaffSpellDuelContext(UUID matchId, int round, double damageMultiplier) {
    public static final double MAX_DAMAGE_MULTIPLIER = 10.0;

    public StaffSpellDuelContext {
        Objects.requireNonNull(matchId, "matchId");
        if (round < 0) throw new IllegalArgumentException("round must be non-negative");
        if (!Double.isFinite(damageMultiplier) || damageMultiplier <= 0.0 ||
            damageMultiplier > MAX_DAMAGE_MULTIPLIER) {
            throw new IllegalArgumentException("damageMultiplier must be finite and in (0, 10]");
        }
    }
}
