package me.dartanman.duels.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChallengeServiceTest {
    @Test
    void acceptsOnlyTheExpectedUnexpiredChallenge() {
        AtomicLong now = new AtomicLong(1_000L);
        ChallengeService service = new ChallengeService(Duration.ofSeconds(30), now::get);
        UUID challenger = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        service.request(challenger, target);

        assertTrue(service.accept(target, UUID.randomUUID()).isEmpty());
        assertEquals(challenger, service.accept(target, challenger).orElseThrow().challenger());
        assertEquals(0, service.size());
    }

    @Test
    void expiresAndRemovesRequestsFromEitherParticipant() {
        AtomicLong now = new AtomicLong(1_000L);
        ChallengeService service = new ChallengeService(Duration.ofSeconds(5), now::get);
        UUID challenger = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        service.request(challenger, target);
        now.addAndGet(5_001L);
        assertTrue(service.accept(target, null).isEmpty());

        service.request(challenger, target);
        service.removePlayer(challenger);
        assertEquals(0, service.size());
    }
}
