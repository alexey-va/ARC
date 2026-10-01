package ru.arc.slimefunmenu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.common.ServerLocation

class NetworkSpawnDestinationTest : FreeSpec({
    "keeps the explicit remote destination and orientation" {
        SlimefunMenuModule.networkSpawnDestination("spawn", "rc_origin_spawn", 0.5, 70.0, 0.5, 180.0, 0.0) shouldBe
            ServerLocation("spawn", "rc_origin_spawn", 0.5, 70.0, 0.5, 180f, 0f)
    }
    "rejects incomplete, wrong-backend or impossible destinations rather than just switching servers" {
        SlimefunMenuModule.networkSpawnDestination("survival", "rc_origin_spawn", 0.5, 70.0, 0.5, 0.0, 0.0) shouldBe null
        SlimefunMenuModule.networkSpawnDestination("spawn", "", 0.5, 70.0, 0.5, 0.0, 0.0) shouldBe null
        SlimefunMenuModule.networkSpawnDestination("spawn", "rc_origin_spawn", Double.NaN, 70.0, 0.5, 0.0, 0.0) shouldBe null
        SlimefunMenuModule.networkSpawnDestination("spawn", "rc_origin_spawn", 0.5, 70.0, 0.5, Double.MAX_VALUE, 0.0) shouldBe null
        SlimefunMenuModule.networkSpawnDestination("spawn", "rc_origin_spawn", 0.5, 70.0, 30_000_001.0, 0.0, 0.0) shouldBe null
        SlimefunMenuModule.networkSpawnDestination("spawn", "rc_origin_spawn", 0.5, 70.0, 0.5, 0.0, 91.0) shouldBe null
    }
})
