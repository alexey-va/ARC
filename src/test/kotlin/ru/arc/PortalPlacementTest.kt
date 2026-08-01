package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.block.Block

class PortalPlacementTest : FreeSpec({
    "portal clearance" - {
        "rejects a slab in either portal cell" {
            val passable = mockk<Block> {
                every { isPassable } returns true
            }
            val slab = mockk<Block> {
                every { isPassable } returns false
            }

            hasPortalClearance(slab, passable) shouldBe false
            hasPortalClearance(passable, slab) shouldBe false
        }

        "allows two blocks without colliding parts" {
            val grass = mockk<Block> {
                every { isPassable } returns true
            }
            val air = mockk<Block> {
                every { isPassable } returns true
            }

            hasPortalClearance(grass, air) shouldBe true
        }
    }
})
