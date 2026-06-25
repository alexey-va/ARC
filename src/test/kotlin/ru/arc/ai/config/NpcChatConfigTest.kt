package ru.arc.ai.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class NpcChatConfigTest : FreeSpec({
    "NpcChatConfig" - {
        "should read archetype settings from test config" {
            val config =
                TestNpcChatConfig(
                    archetypes =
                        mapOf(
                            "joker" to
                                TestNpcChatConfig.ArchetypeSettings(
                                    system = listOf("Be funny"),
                                    temperature = 0.9,
                                ),
                        ),
                )

            config.systemMessages("joker") shouldBe listOf("Be funny")
            config.temperature("joker", 0.2) shouldBe 0.9
            config.temperature("unknown", 0.2) shouldBe 0.2
        }
    }
})
