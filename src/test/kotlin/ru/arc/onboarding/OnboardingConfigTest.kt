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

    "mining is excluded from settlement progress without changing the claim guide worlds" {
        config("worlds: [Survival, Mining, Vanilla]\n").apply {
            allowsWorld("MINING") shouldBe true
            allowsQuestWorld(" Mining ") shouldBe false
            allowsQuestWorld(" Survival ") shouldBe true
            allowsQuestWorld("VANILLA") shouldBe true
            allowsQuestWorld("spawn") shouldBe false
        }
    }

    "world hints follow the configured quest gate and never expose an unknown world ID" {
        val plain = PlainTextComponentSerializer.plainText()
        config("worlds: [Vanilla, Mining, CustomHome]\n").apply {
            questWorldHint(" VANILLA ") shouldBe null
            questWorldHint("customhome") shouldBe null
            plain.serialize(requireNotNull(questWorldHint(" MINING "))) shouldBe "Перейдите из Мира добычи в Мир выживания"
            for (world in listOf("survival", "spawn", "rc_origin_spawn", "lobby", "world_nether", "world_the_end", "private_instance_42")) {
                allowsQuestWorld(world) shouldBe false
                plain.serialize(requireNotNull(questWorldHint(world))) shouldBe "Перейдите в Мир выживания"
            }
            validate()
        }
        config("quest:\n  hud:\n    enter-survival: '<white>Вернитесь в Мир выживания'\n").apply {
            plain.serialize(requireNotNull(questWorldHint("spawn"))) shouldBe "Вернитесь в Мир выживания"
            validate()
        }
    }

    "empty worlds and invalid NPC fail validation" {
        shouldThrow<IllegalArgumentException> { config("worlds: []\n").validate() }
        shouldThrow<IllegalArgumentException> { config("quest:\n  npc-id: -1\n").validate() }
    }

    "step rewards keep separate positive coin and premium token units" {
        config("enabled: false\n").apply {
            coinsPerStep shouldBe 200L
            tokensPerStep shouldBe 1L
        }
        config("quest:\n  rewards:\n    coins-per-step: 300\n    tokens-per-step: 2\n").apply {
            coinsPerStep shouldBe 300L
            tokensPerStep shouldBe 2L
        }
        shouldThrow<IllegalArgumentException> { config("quest:\n  rewards:\n    coins-per-step: 0\n").validate() }
        shouldThrow<IllegalArgumentException> { config("quest:\n  rewards:\n    tokens-per-step: -1\n").validate() }
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
