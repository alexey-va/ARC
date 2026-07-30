package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.rtp.NetworkRtpRequest

class PluginMessengerWorldResolutionTest :
    FreeSpec({
        "resolves a bare proxy request to the carrier current world" {
            resolveNetworkRtpWorld(NetworkRtpRequest.CURRENT_WORLD, "Vanilla") shouldBe "vanilla"
        }

        "preserves an explicitly requested world" {
            resolveNetworkRtpWorld("mining", "vanilla") shouldBe "mining"
        }
    })
