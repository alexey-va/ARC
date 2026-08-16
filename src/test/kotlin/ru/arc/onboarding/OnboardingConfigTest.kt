package ru.arc.onboarding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class OnboardingConfigTest : FreeSpec({
    "loads configurable worlds and bounded delivery timing" {
        val root = Files.createTempDirectory("arc-onboarding-config-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/onboarding.yml"),
            """
            enabled: true
            worlds: [Survival, Mining]
            delivery:
              first-delay-ticks: 0
              resume-delay-ticks: 5000
              between-messages-ticks: 1
            steps:
              first-rtp:
                enabled: false
                message: '<green>ok'
            """.trimIndent(),
        )

        val config = OnboardingConfig.load(Config(root, "modules/onboarding.yml"))

        config.enabled shouldBe true
        config.worlds shouldBe setOf("survival", "mining")
        config.firstDelayTicks shouldBe 1L
        config.resumeDelayTicks shouldBe 1_200L
        config.betweenMessagesTicks shouldBe 20L
        config.hintEnabled(OnboardingHint.FIRST_RTP) shouldBe false
    }

    "fails closed when an enabled config has no target worlds" {
        val root = Files.createTempDirectory("arc-onboarding-config-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve("modules/onboarding.yml"), "enabled: true\nworlds: []\n")

        shouldThrow<IllegalArgumentException> {
            OnboardingConfig.load(Config(root, "modules/onboarding.yml"))
        }
    }

    "fails validation on a blank enabled message" {
        val root = Files.createTempDirectory("arc-onboarding-config-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/onboarding.yml"),
            "enabled: true\nworlds: [survival]\nsteps:\n  first-rtp:\n    enabled: true\n    message: '   '\n",
        )
        val config = OnboardingConfig.load(Config(root, "modules/onboarding.yml"))

        shouldThrow<IllegalArgumentException> { config.validate() }
    }
})
