package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.luckperms.api.model.data.DataMutateResult
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ParticlePresetRewardsTest : StringSpec({
    "only fixed configured presets are ready" {
        val runtime = FakeRuntime().apply { configured = setOf("arc_raincloud") }
        val rewards = ParticlePresetRewards(runtime)

        rewards.ready("arc_raincloud") shouldBe true
        rewards.ready("arc_angel") shouldBe false
        rewards.ready("arc_other") shouldBe false

        runtime.available = false
        rewards.ready("arc_raincloud") shouldBe false
    }

    "readiness observes a refreshed native preset contract" {
        val runtime = FakeRuntime()
        val rewards = ParticlePresetRewards(runtime)

        rewards.ready("arc_rainbow") shouldBe true
        runtime.configured = setOf("arc_raincloud", "arc_angel")
        rewards.ready("arc_rainbow") shouldBe false
        rewards.ready("arc_angel") shouldBe true
    }

    "effective duplicate ownership rejects before mutation and preserves transferability" {
        val runtime = FakeRuntime().apply { alreadyHasPermission = true }
        val rewards = ParticlePresetRewards(runtime)
        val player = player()

        rewards.canRedeem(player, "arc_angel") shouldBe
            "<gold>Эта косметика уже открыта. Сертификат можно передать другому игроку."
        rewards.redeem(player, "arc_angel").get() shouldBe
            PhysicalRewardOutcome.Rejected("<gold>Эта косметика уже открыта. Сертификат можно передать другому игроку.")
        runtime.grants shouldBe emptyList()
    }

    "grant completes Applied only after LuckPerms completes its durable save" {
        val pendingSave = CompletableFuture<DataMutateResult>()
        val runtime = FakeRuntime().apply { nextSave = pendingSave }
        val rewards = ParticlePresetRewards(runtime)
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val player = player(playerId)

        val outcome = rewards.redeem(player, "arc_raincloud")
        outcome.isDone shouldBe false
        runtime.grants shouldBe listOf(playerId to "arc.cosmetics.particles.arc_raincloud")

        pendingSave.complete(DataMutateResult.SUCCESS)
        outcome.get() shouldBe PhysicalRewardOutcome.Applied
    }

    "atomic already-present results reject and post-mutation failures remain uncertain" {
        val duplicate = ParticlePresetRewards(FakeRuntime().apply {
            nextSave = CompletableFuture.completedFuture(DataMutateResult.FAIL_ALREADY_HAS)
        })
        duplicate.redeem(player(), "arc_rainbow").get() shouldBe PhysicalRewardOutcome.Rejected(
            "<gold>Эта косметика уже открыта. Сертификат можно передать другому игроку.",
        )

        val failedSave = CompletableFuture<DataMutateResult>().also {
            it.completeExceptionally(IllegalStateException("save failed"))
        }
        val failed = ParticlePresetRewards(FakeRuntime().apply { nextSave = failedSave })
        failed.redeem(player(), "arc_angel").get() shouldBe PhysicalRewardOutcome.Uncertain(
            "<#ffcb70>Выдача сохранена для проверки администрацией.",
        )
    }

    "an unavailable or unsupported preset never starts a permission mutation" {
        val runtime = FakeRuntime().apply { available = false }
        val rewards = ParticlePresetRewards(runtime)

        rewards.redeem(player(), "arc_angel").get() shouldBe
            PhysicalRewardOutcome.Rejected(RewardCatalogMessages.DEFAULT.unavailable)
        rewards.redeem(player(), "arc_arbitrary").get() shouldBe
            PhysicalRewardOutcome.Rejected(RewardCatalogMessages.DEFAULT.unavailable)
        runtime.grants shouldBe emptyList()
    }
}) {
    companion object {
        private fun player(id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")): Player =
            mockk<Player>().also { every { it.uniqueId } returns id }

        private class FakeRuntime(
        ) : ParticlePresetRewards.Runtime {
            var available = true
            var configured: Set<String> = ParticlePresetEntitlements.IDS
            var alreadyHasPermission = false
            var nextSave: CompletableFuture<DataMutateResult> =
                CompletableFuture.completedFuture(DataMutateResult.SUCCESS)
            val grants = mutableListOf<Pair<UUID, String>>()

            override fun providersReady(): Boolean = available

            override fun hasVerifiedPreset(id: String): Boolean = id in configured

            override fun hasPermission(player: Player, permission: String): Boolean = alreadyHasPermission

            override fun addPermission(playerId: UUID, permission: String): CompletableFuture<DataMutateResult> {
                grants += playerId to permission
                return nextSave
            }
        }
    }
}
