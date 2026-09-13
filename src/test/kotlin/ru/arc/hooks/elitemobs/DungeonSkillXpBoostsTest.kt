package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.time.Duration
import java.time.Instant

class DungeonSkillXpBoostsTest : FreeSpec({
    "buying time extends an active boost instead of replacing it" {
        val now = Instant.parse("2026-09-13T10:00:00Z")
        extendedSkillBoostExpiry(now, now.plusSeconds(600), Duration.ofMinutes(30)) shouldBe now.plusSeconds(2_400)
    }

    "an expired boost starts from the purchase time" {
        val now = Instant.parse("2026-09-13T10:00:00Z")
        extendedSkillBoostExpiry(now, now.minusSeconds(1), Duration.ofHours(2)) shouldBe now.plusSeconds(7_200)
    }

    "balanced catalog keeps one multiplier and discounted duration tiers" {
        DEFAULT_SKILL_XP_BOOSTS.map { it.duration.toMinutes() to it.price } shouldBe
            listOf(30L to 150.0, 120L to 500.0, 240L to 900.0)
    }

    "failed permission persistence refunds the exact crystal charge" {
        val id = java.util.UUID.randomUUID()
        val player = mockk<Player> { every { uniqueId } returns id }
        var balance = 500.0
        val wallet = object : SkillXpBoostWallet {
            override fun balance(player: Player) = balance
            override fun withdraw(player: Player, amount: Double) = true.also { balance -= amount }
            override fun deposit(player: Player, amount: Double) = true.also { balance += amount }
        }
        val access = object : SkillXpBoostAccess {
            override fun currentExpiry(playerId: java.util.UUID): Instant? = null
            override fun extend(playerId: java.util.UUID, duration: Duration, complete: (Boolean) -> Unit) = complete(false)
        }
        val shop = DungeonSkillXpBoosts(wallet = wallet, access = access)
        var result: SkillXpBoostResult? = null

        shop.buy(player, DEFAULT_SKILL_XP_BOOSTS.first()) { result = it }

        result shouldBe SkillXpBoostResult.GRANT_FAILED
        balance shouldBe 500.0
    }

    "synchronous permission failure also clears the purchase and refunds it" {
        val id = java.util.UUID.randomUUID()
        val player = mockk<Player> { every { uniqueId } returns id }
        var balance = 500.0
        val wallet = object : SkillXpBoostWallet {
            override fun balance(player: Player) = balance
            override fun withdraw(player: Player, amount: Double) = true.also { balance -= amount }
            override fun deposit(player: Player, amount: Double) = true.also { balance += amount }
        }
        val access = object : SkillXpBoostAccess {
            override fun currentExpiry(playerId: java.util.UUID): Instant? = null
            override fun extend(playerId: java.util.UUID, duration: Duration, complete: (Boolean) -> Unit) = error("LuckPerms unavailable")
        }
        val shop = DungeonSkillXpBoosts(wallet = wallet, access = access)
        var result: SkillXpBoostResult? = null

        shop.buy(player, DEFAULT_SKILL_XP_BOOSTS.first()) { result = it }

        result shouldBe SkillXpBoostResult.GRANT_FAILED
        balance shouldBe 500.0
        shop.isPending(player) shouldBe false
    }

    "successful permission persistence charges once" {
        val id = java.util.UUID.randomUUID()
        val player = mockk<Player> { every { uniqueId } returns id }
        var balance = 500.0
        var extendedBy: Duration? = null
        val wallet = object : SkillXpBoostWallet {
            override fun balance(player: Player) = balance
            override fun withdraw(player: Player, amount: Double) = true.also { balance -= amount }
            override fun deposit(player: Player, amount: Double) = true.also { balance += amount }
        }
        val access = object : SkillXpBoostAccess {
            override fun currentExpiry(playerId: java.util.UUID): Instant? = null
            override fun extend(playerId: java.util.UUID, duration: Duration, complete: (Boolean) -> Unit) {
                extendedBy = duration
                complete(true)
            }
        }
        val offer = DEFAULT_SKILL_XP_BOOSTS.first()
        var result: SkillXpBoostResult? = null

        DungeonSkillXpBoosts(wallet = wallet, access = access).buy(player, offer) { result = it }

        result shouldBe SkillXpBoostResult.BOUGHT
        balance shouldBe 350.0
        extendedBy shouldBe offer.duration
    }
})
