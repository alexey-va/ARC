package ru.arc.onboarding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class OptionalQuestConfigTest : FreeSpec({
    fun config(yaml: String): OptionalQuestConfig {
        val root = Files.createTempDirectory("arc-optional-quest-")
        Files.writeString(root.resolve("onboarding.yml"), yaml)
        return OptionalQuestConfig(Config(root, "onboarding.yml"))
    }

    "legacy configuration leaves optional quests off and does not create NPC bindings" {
        config("quest:\n  public-enabled: true\n").apply {
            enabled shouldBe false
            npcIds.values.toSet() shouldBe setOf(-1)
            validate()
        }
    }

    "arrival areas use all three coordinates and the exact configured world" {
        val area = OptionalQuestArea("rc_atelier_casino", 0.0, 63.0, 0.0, 50.0, 68.0, 52.0)
        area.contains("RC_ATELIER_CASINO", 4.5, 65.0, 22.2) shouldBe true
        area.contains("rc_atelier_casino", 4.5, 49.0, 22.2) shouldBe false
        area.contains("rc_atelier_casino", 51.0, 65.0, 22.2) shouldBe false
        area.contains("another_casino", 4.5, 65.0, 22.2) shouldBe false
        shouldThrow<IllegalArgumentException> { OptionalQuestArea("world", 0.0, 3.0, 0.0, 1.0, 2.0, 1.0) }
    }

    "enabling quests requires the complete set of real arrival areas and distinct guide NPCs" {
        shouldThrow<IllegalArgumentException> { config("optional-quests:\n  enabled: true\n").validate() }
        shouldThrow<IllegalArgumentException> {
            config("optional-quests:\n  adventure: {npc-id: 426}\n  farm: {npc-id: 426}\n").validate()
        }
    }
})
