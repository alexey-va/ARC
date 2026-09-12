package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import ru.arc.config.ConfigManager
import java.nio.file.Files

class OriginPortalsModuleTest : FreeSpec({
    afterTest { ConfigManager.clear() }

    "bundled Origin portals keep the requested anchors and route ownership" {
        val directory = Files.createTempDirectory("arc-origin-portals")
        try {
            val config = OriginPortalsConfig.load(directory)
            config.enabled shouldBe true
            config.verticalOffset shouldBe 5.5
            config.anchors.map { it.id } shouldContainExactly OriginPortalId.entries

            val survival = config.anchors.first { it.id == OriginPortalId.SURVIVAL }
            survival.x shouldBe 9.5
            survival.y shouldBe 70.0
            survival.z shouldBe 0.5
            survival.yaw shouldBe 270f
            survival.width shouldBe 12.0
            survival.height shouldBe 16.8
            survival.style shouldBe ru.arc.PortalVisualStyle.ORIGIN
            survival.command shouldBe "arc rtp survival --only-if-first"
            survival.label shouldBe "Выживание"
            config.gateSettings(survival)!!.height shouldBe 16.8f

            val mining = config.anchors.first { it.id == OriginPortalId.MINING }
            mining.style shouldBe ru.arc.PortalVisualStyle.ASTRAL
            mining.command shouldBe "arc rtp mining --only-if-first"
            val vanilla = config.anchors.first { it.id == OriginPortalId.VANILLA }
            vanilla.style shouldBe ru.arc.PortalVisualStyle.VOID
            vanilla.command shouldBe "arc rtp vanilla --only-if-first"
            val gallery = config.anchors.first { it.id == OriginPortalId.GALLERY_EXIT }
            gallery.worldName shouldBe "rc_atelier_furniture_gallery"
            gallery.width shouldBe 4.5
            gallery.height shouldBe 6.3
            gallery.command shouldBe "rcfurniturereturn"
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "portal entry uses the configured yaw-aware plane and vertical bounds" {
        val world = mockk<World>()
        val anchor = OriginPortalAnchor(
            id = OriginPortalId.SURVIVAL,
            worldName = "origin",
            x = 0.0,
            y = 0.0,
            z = 0.0,
            yaw = 90f,
            width = 12.0,
            height = 16.8,
            entryDepth = 2.0,
            command = "test",
            label = "Тест",
            style = ru.arc.PortalVisualStyle.ORIGIN,
        )
        val center = Location(world, 0.0, 0.0, 0.0)
        everyWorldName(world, "origin")

        anchor.contains(center).shouldBeTrue()
        anchor.contains(Location(world, 2.1, 0.0, 0.0)).shouldBeFalse()
        anchor.contains(Location(world, 0.0, 0.0, 6.1)).shouldBeFalse()
        anchor.contains(Location(world, 0.0, 8.41, 0.0)).shouldBeFalse()
    }

    "move persists feet coordinates and reloads the same anchor" {
        val directory = Files.createTempDirectory("arc-origin-portals-move")
        try {
            val config = OriginPortalsConfig.load(directory)
            val world = mockk<World>()
            everyWorldName(world, "rc_origin_spawn")
            config.persistFeet(OriginPortalId.SURVIVAL, Location(world, 13.25, 71.5, -2.75, 42.5f, 30f))
            ConfigManager.clear()
            val reloaded = OriginPortalsConfig.load(directory)
            val moved = reloaded.anchors.first { it.id == OriginPortalId.SURVIVAL }
            moved.x shouldBe 13.25
            moved.y shouldBe 71.5
            moved.z shouldBe -2.75
            moved.yaw shouldBe 42.5f
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})

private fun everyWorldName(world: World, name: String) {
    every { world.name } returns name
}
