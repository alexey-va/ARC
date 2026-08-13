package me.dartanman.duels.game;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class ChallengeService {
    private final long expirationMillis;
    private final LongSupplier clock;
    private final Map<UUID, Challenge> incoming = new HashMap<>();

    public ChallengeService(Duration expiration, LongSupplier clock) {
        if (expiration.isNegative() || expiration.isZero()) throw new IllegalArgumentException("expiration must be positive");
        this.expirationMillis = expiration.toMillis();
        this.clock = clock;
    }

    public synchronized void request(UUID challenger, UUID target) {
        if (challenger.equals(target)) throw new IllegalArgumentException("Cannot challenge self");
        cleanupExpired();
        incoming.put(target, new Challenge(challenger, target, clock.getAsLong() + expirationMillis));
    }

    public synchronized Optional<Challenge> accept(UUID target, UUID expectedChallenger) {
        cleanupExpired();
        Challenge challenge = incoming.get(target);
        if (challenge == null) return Optional.empty();
        if (expectedChallenger != null && !challenge.challenger().equals(expectedChallenger)) return Optional.empty();
        incoming.remove(target);
        return Optional.of(challenge);
    }

    public synchronized Optional<Challenge> deny(UUID target, UUID expectedChallenger) {
        return accept(target, expectedChallenger);
    }

    public synchronized void removePlayer(UUID playerId) {
        incoming.remove(playerId);
        incoming.values().removeIf(challenge -> challenge.challenger().equals(playerId));
    }

    public synchronized int size() {
        cleanupExpired();
        return incoming.size();
    }

    private void cleanupExpired() {
        long now = clock.getAsLong();
        Iterator<Challenge> iterator = incoming.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAt() <= now) iterator.remove();
        }
    }

    public record Challenge(UUID challenger, UUID target, long expiresAt) {}
}
