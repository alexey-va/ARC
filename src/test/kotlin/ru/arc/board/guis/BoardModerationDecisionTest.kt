package ru.arc.board.guis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.ModerResponse
import ru.arc.ai.ModerationResponse

class BoardModerationDecisionTest : FreeSpec({
    "board moderation decision" - {
        "should allow only explicit OK" {
            boardModerationDecision(ModerResponse(ModerationResponse.OK, "")) shouldBe
                BoardModerationDecision.ALLOW
        }

        "should reject explicit BAD" {
            boardModerationDecision(ModerResponse(ModerationResponse.BAD, "reason")) shouldBe
                BoardModerationDecision.REJECT
        }

        "should fail closed when moderation is unavailable or ambiguous" {
            boardModerationDecision(null) shouldBe BoardModerationDecision.UNAVAILABLE
        }
    }
})
