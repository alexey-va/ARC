package ru.arc.decorinteraction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID

class DecorInteractionRulesTest : FunSpec({
    test("retains all eight cooldown permission nodes and durations") {
        DecorInteractionAction.entries.associate { it.command to (it.cooldownNode to it.cooldown) } shouldBe mapOf(
            "drink" to ("arc.cooldown.interaction.drink" to Duration.ofHours(1)),
            "fountain" to ("arc.cooldown.interaction.fountain" to Duration.ofHours(3)),
            "well" to ("arc.cooldown.interaction.well" to Duration.ofMinutes(1)),
            "milk" to ("arc.cooldown.interaction.milk" to Duration.ofMinutes(5)),
            "tea" to ("arc.cooldown.interaction.tea" to Duration.ofMinutes(3)),
            "fish" to ("arc.cooldown.interaction.fish" to Duration.ofMinutes(20)),
            "harvest" to ("arc.cooldown.interaction.harvest" to Duration.ofMinutes(5)),
            "rest" to ("arc.cooldown.interaction.rest" to Duration.ofMinutes(5)),
        )
    }

    test("fish rarity boundaries preserve 5 percent golden and 25 percent silver") {
        listOf(1, 5).map(FishQuality::fromRoll) shouldBe listOf(FishQuality.GOLDEN, FishQuality.GOLDEN)
        listOf(6, 30).map(FishQuality::fromRoll) shouldBe listOf(FishQuality.SILVER, FishQuality.SILVER)
        listOf(31, 100).map(FishQuality::fromRoll) shouldBe listOf(FishQuality.NORMAL, FishQuality.NORMAL)
    }

    test("fish IDs preserve the four source variants and quality suffixes") {
        FishReward.entries.map { FishCatch(it, FishQuality.NORMAL).itemId } shouldBe listOf(
            "customfishing:tuna_fish",
            "customfishing:perch_fish",
            "customfishing:sardine_fish",
            "customfishing:carp_fish",
        )
        FishCatch(FishReward.TUNA, FishQuality.SILVER).itemId shouldBe "customfishing:tuna_fish_silver_star"
        FishCatch(FishReward.TUNA, FishQuality.GOLDEN).itemId shouldBe "customfishing:tuna_fish_golden_star"
    }

    test("rate limit is target-specific and expires at the three-second boundary") {
        val gate = DecorInteractionRateLimiter()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        gate.tryAcquire(first, 10L) shouldBe true
        gate.tryAcquire(first, 3_000_000_009L) shouldBe false
        gate.tryAcquire(second, 3_000_000_009L) shouldBe true
        gate.tryAcquire(first, 3_000_000_010L) shouldBe true
    }

    test("local fallback cooldowns for one player remain independent by interaction") {
        val playerId = UUID.randomUUID()
        val cooldowns = DecorInteractionCooldowns()
        val now = 1_000L
        cooldowns.start(playerId, DecorInteractionAction.DRINK, now)
        cooldowns.start(playerId, DecorInteractionAction.WELL, now)

        cooldowns.isActive(playerId, DecorInteractionAction.DRINK, now + 1) shouldBe true
        cooldowns.isActive(playerId, DecorInteractionAction.WELL, now + 1) shouldBe true
        cooldowns.isActive(playerId, DecorInteractionAction.REST, now + 1) shouldBe false
        val oneMinuteLater = now + Duration.ofMinutes(1).toNanos()
        cooldowns.expire(oneMinuteLater)
        cooldowns.isActive(playerId, DecorInteractionAction.DRINK, oneMinuteLater) shouldBe true
        cooldowns.isActive(playerId, DecorInteractionAction.WELL, oneMinuteLater) shouldBe false
    }
})
