package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.onetime.OneTimeUseFingerprint
import java.util.UUID

class RewardChoiceControllerTest : StringSpec({
    "three unique offers are deterministic for the frozen voucher after reopen or reload" {
        val options = (0 until 12).map { index ->
            PhysicalRewardChoiceOption(
                id = "option_$index",
                name = "Награда $index",
                description = emptyList(),
                childKey = "frozen:${"%064x".format(index + 1)}",
                childFingerprint = "%064x".format(index + 1),
            )
        }
        val voucherId = UUID.fromString("067f1104-f871-4c50-8905-1c3dfc4a91b8")
        val definition = OneTimeUseFingerprint.sha256Fields("weekly.choice", "ordered-pool-v1").sha256

        val first = RewardChoiceSelector.offeredIndices(voucherId, definition, options)
        val reopened = RewardChoiceSelector.offeredIndices(voucherId, definition, options)
        val reloaded = RewardChoiceSelector.offeredIndices(voucherId, definition, options.toList())

        first.size shouldBe 3
        first.distinct().size shouldBe 3
        reopened shouldBe first
        reloaded shouldBe first
    }

    "selector refuses a pool that cannot offer exactly three unique choices" {
        val options = (0 until 2).map { index ->
            PhysicalRewardChoiceOption(
                id = "option_$index",
                name = "Награда $index",
                description = emptyList(),
                childKey = "frozen:${"%064x".format(index + 1)}",
                childFingerprint = "%064x".format(index + 1),
            )
        }
        runCatching {
            RewardChoiceSelector.offeredIndices(UUID.randomUUID(), "a".repeat(64), options)
        }.isFailure shouldBe true
    }
})
