package ru.arc.ops

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class OpsQaWorldHandlersTest :
    FreeSpec({
        "Trails fixture is fixed, bounded and has unique coordinates" {
            OpsQaWorldHandlers.WORLD_NAME shouldBe "arc_qa_flat"
            OpsQaWorldHandlers.fixtureBlocks.map { it.id } shouldContainExactlyInAnyOrder
                listOf("trail_target", "grass_sample", "dirt_sample", "coarse_dirt_sample", "dirt_path_sample")
            OpsQaWorldHandlers.fixtureBlocks.map { Triple(it.x, it.y, it.z) }.distinct().size shouldBe
                OpsQaWorldHandlers.fixtureBlocks.size
            OpsQaWorldHandlers.fixtureBlocks.single { it.id == "trail_target" }.material shouldBe Material.GRASS_BLOCK
        }

        "player allowlist is exact and case-insensitive" {
            val config = TestOpsHttpConfig(qaWorldAllowedPlayers = setOf("codexqa_728"))

            OpsQaWorldHandlers.requireAllowedPlayer("CodexQA_728", config)
            shouldThrow<IllegalArgumentException> {
                OpsQaWorldHandlers.requireAllowedPlayer("AnotherPlayer", config)
            }
            shouldThrow<IllegalArgumentException> {
                OpsQaWorldHandlers.requireAllowedPlayer("../../world", config)
            }
        }
    })
