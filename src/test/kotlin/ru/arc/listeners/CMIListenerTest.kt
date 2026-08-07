package ru.arc.listeners

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class CMIListenerTest : FreeSpec({
    "command-only portal join predicate" - {
        "accepts an enabled command-only portal without another destination" {
            isCommandOnlyPortal(
                enabled = true,
                performCommandsWithoutTp = true,
                hasTeleportLocation = false,
                hasBungeeDestination = false,
            ) shouldBe true
        }

        "rejects disabled, teleporting, and bungee portals" {
            isCommandOnlyPortal(
                enabled = false,
                performCommandsWithoutTp = true,
                hasTeleportLocation = false,
                hasBungeeDestination = false,
            ) shouldBe false
            isCommandOnlyPortal(
                enabled = true,
                performCommandsWithoutTp = true,
                hasTeleportLocation = true,
                hasBungeeDestination = false,
            ) shouldBe false
            isCommandOnlyPortal(
                enabled = true,
                performCommandsWithoutTp = true,
                hasTeleportLocation = false,
                hasBungeeDestination = true,
            ) shouldBe false
        }
    }
})
