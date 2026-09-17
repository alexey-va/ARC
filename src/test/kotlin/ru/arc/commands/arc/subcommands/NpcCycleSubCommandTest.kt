package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.origin.scene.OriginSceneCycleKey

class NpcCycleSubCommandTest : FreeSpec({
    val keys = listOf(
        OriginSceneCycleKey("forge", "apprentice-engraving"),
        OriginSceneCycleKey("forge", "furnace-check"),
        OriginSceneCycleKey("mount-yard", "groom-wind"),
    )

    "resolves legacy forge actor aliases" {
        resolveOriginSceneCycle(keys, arrayOf("forge-luka")) shouldBe
            OriginSceneCycleKey("forge", "apprentice-engraving")
        resolveOriginSceneCycle(keys, arrayOf("forge-yar")) shouldBe
            OriginSceneCycleKey("forge", "furnace-check")
    }

    "resolves unique ids and explicit scene ids" {
        resolveOriginSceneCycle(keys, arrayOf("furnace-check")) shouldBe
            OriginSceneCycleKey("forge", "furnace-check")
        resolveOriginSceneCycle(keys, arrayOf("mount-yard", "groom-wind")) shouldBe
            OriginSceneCycleKey("mount-yard", "groom-wind")
    }

    "rejects unknown and malformed selectors" {
        resolveOriginSceneCycle(keys, arrayOf("missing")) shouldBe null
        resolveOriginSceneCycle(keys, arrayOf("forge", "furnace-check", "extra")) shouldBe null
    }
})
