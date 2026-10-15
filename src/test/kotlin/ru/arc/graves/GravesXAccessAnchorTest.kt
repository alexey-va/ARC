package ru.arc.graves

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

class GravesXAccessAnchorTest : StringSpec({
    "selects the nearest placed grave block instead of the original death position" {
        val world = testWorld("00000000-0000-0000-0000-000000000001")
        val death = Location(world, 100.0, 64.0, 100.0)
        val nearerBlock = Location(world, 101.0, 64.0, 100.0)
        val fartherBlock = Location(world, 20.0, 64.0, 20.0)

        selectGraveAnchor(death, listOf(fartherBlock, nearerBlock)) shouldBe nearerBlock
    }

    "uses the death position only when no placed grave blocks remain" {
        val death = Location(testWorld("00000000-0000-0000-0000-000000000002"), 4.0, 65.0, -7.0)

        selectGraveAnchor(death, emptyList()) shouldBe death
    }

    "chooses a stable block when all placed blocks are in other worlds" {
        val death = Location(testWorld("00000000-0000-0000-0000-000000000003"), 0.0, 64.0, 0.0)
        val stableFirst = Location(testWorld("00000000-0000-0000-0000-000000000004"), 1_000.0, 64.0, 1_000.0)
        val geometricallyCloser = Location(testWorld("00000000-0000-0000-0000-000000000005"), 1.0, 64.0, 1.0)

        selectGraveAnchor(death, listOf(geometricallyCloser, stableFirst)) shouldBe stableFirst
    }
})

private fun testWorld(id: String): World = mockk<World> {
    every { uid } returns UUID.fromString(id)
}
