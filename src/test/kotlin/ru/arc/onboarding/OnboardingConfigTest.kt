package ru.arc.onboarding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files

class OnboardingConfigTest : FreeSpec({
    fun config(yaml: String): OnboardingConfig {
        val root = Files.createTempDirectory("arc-onboarding-config-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve("modules/onboarding.yml"), yaml)
        return OnboardingConfig.load(Config(root, "modules/onboarding.yml"))
    }

    "old enabled flag never publishes the new quest" {
        val value = config("enabled: true\nworlds: [Survival, Mining]\n")
        value.publicEnabled shouldBe false
        value.worlds shouldBe setOf("survival", "mining")
        value.npcId shouldBe 447
        value.validate()
    }

    "public release requires the new explicit switch" {
        config("quest:\n  public-enabled: true\n  npc-id: 12\n").apply {
            publicEnabled shouldBe true
            npcId shouldBe 12
            validate()
        }
    }

    "empty worlds and invalid NPC fail validation" {
        shouldThrow<IllegalArgumentException> { config("worlds: []\n").validate() }
        shouldThrow<IllegalArgumentException> { config("quest:\n  npc-id: -1\n").validate() }
    }

    "preserves the independent claim guide settings" {
        val value = config("claim-guide:\n  land-walls-enabled: false\n  grid-mode: nearby-grid\n")
        value.claimGuideLandWallsEnabled shouldBe false
        value.claimGuideGridMode shouldBe ClaimGuideGridMode.NEARBY_GRID
        value.validate()
    }

    "rejects an unknown grid mode instead of silently showing a different grid" {
        shouldThrow<IllegalArgumentException> { config("claim-guide:\n  grid-mode: unknown\n").validate() }
    }

    "quest and claim text parse with portable defaults" {
        val value = config("enabled: false\n")
        val plain = PlainTextComponentSerializer.plainText()
        OnboardingQuestDialog.DEFAULT_TEXT.keys.forEach { key ->
            plain.serialize(value.questText(key)).contains("<") shouldBe false
        }
        OnboardingConfig.CLAIM_TEXT.keys.forEach { key ->
            plain.serialize(value.claimText(key)).contains("<") shouldBe false
        }
        value.claimGuideEnabled shouldBe true
        value.claimGuideLandWallsEnabled shouldBe true
        value.claimGuideGridMode shouldBe ClaimGuideGridMode.CURRENT_CHUNK
        plain.serialize(value.claimText("world-unavailable")) shouldBe "В этом мире приват недоступен"
        value.validate()
    }
})
