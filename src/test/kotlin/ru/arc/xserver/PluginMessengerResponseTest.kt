package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.arc.rtp.FirstRtpRouteResult

class PluginMessengerResponseTest :
    FreeSpec({
        "does not notify a returning portal player" {
            firstEntryPlayerMessage(FirstRtpRouteResult.ReturnedToWorld) shouldBe null
        }

        "preserves rejection feedback" {
            firstEntryPlayerMessage(FirstRtpRouteResult.Rejected("причина")).shouldNotBeNull()
        }
    })
