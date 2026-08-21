package ru.arc.rtp

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class RtpRespawnCompletionCopyTest : FreeSpec({
    "respawn confirmation leaves the home command to contextual onboarding" {
        RtpRespawnCompletion.DEFAULT_SET_SPAWN_MESSAGE.contains("/sethome") shouldBe false
    }
})
