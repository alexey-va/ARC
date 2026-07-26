package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.Block

class PortalPlacementTest : FreeSpec({
    "portal clearance" - {
        "checks the material of the second block" {
            val first = mockk<Block> {
                every { isEmpty } returns true
            }
            val second = mockk<Block> {
                every { isEmpty } returns false
                every { type } returns Material.STONE
            }

            hasPortalClearance(first, second) shouldBe false
        }

        "allows configured passable materials in both spaces" {
            val first = mockk<Block> {
                every { isEmpty } returns false
                every { type } returns Material.SHORT_GRASS
            }
            val second = mockk<Block> {
                every { isEmpty } returns false
                every { type } returns Material.SNOW
            }

            hasPortalClearance(first, second) shouldBe true
        }
    }
})
