package ru.arc.ai.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class NpcChatConfigTest : FreeSpec({
    "NpcChatConfig" - {
        "should read system prompt from test config" {
            val config =
                TestNpcChatConfig(
                    prompts = mapOf("joker" to "Be funny"),
                )

            config.systemPrompt("joker") shouldBe "Be funny"
            config.systemPrompt("unknown") shouldBe ""
        }
    }
})
