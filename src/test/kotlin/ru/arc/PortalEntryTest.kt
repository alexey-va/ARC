package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.BoundingBox

class PortalEntryTest : FreeSpec({
    val portalBase = Location(null, 10.0, 64.0, 10.0)

    "portal entrance" - {
        "accepts a player whose hitbox touches the portal from an adjacent block" {
            val playerLocation = Location(null, 9.7, 65.0, 10.5)
            val playerBounds = BoundingBox(9.4, 65.0, 10.2, 10.0, 66.8, 10.8)

            touchesPortalEntrance(playerLocation, playerBounds, portalBase) shouldBe true
        }

        "accepts feet exactly one and a half blocks above the portal base" {
            val playerLocation = Location(null, 10.5, 65.5, 10.5)
            val playerBounds = BoundingBox(10.2, 65.5, 10.2, 10.8, 67.3, 10.8)

            touchesPortalEntrance(playerLocation, playerBounds, portalBase) shouldBe true
        }

        "rejects a nearby hitbox that does not touch the portal" {
            val playerLocation = Location(null, 9.6, 65.0, 10.5)
            val playerBounds = BoundingBox(9.3, 65.0, 10.2, 9.9, 66.8, 10.8)

            touchesPortalEntrance(playerLocation, playerBounds, portalBase) shouldBe false
        }

        "requires contact on both horizontal axes" {
            val playerLocation = Location(null, 9.95, 65.0, 9.6)
            val playerBounds = BoundingBox(9.65, 65.0, 9.3, 10.25, 66.8, 9.9)

            touchesPortalEntrance(playerLocation, playerBounds, portalBase) shouldBe false
        }

        "rejects contact above the activation height" {
            val playerLocation = Location(null, 10.5, 65.51, 10.5)
            val playerBounds = BoundingBox(10.2, 65.51, 10.2, 10.8, 67.31, 10.8)

            touchesPortalEntrance(playerLocation, playerBounds, portalBase) shouldBe false
        }

        "preserves the lower activation boundary" {
            val playerLocation = Location(null, 10.5, 63.0, 10.5)
            val playerBounds = BoundingBox(10.2, 63.0, 10.2, 10.8, 64.8, 10.8)

            touchesPortalEntrance(playerLocation, playerBounds, portalBase) shouldBe false
        }
    }
})
