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
            config.verticalOffset shouldBe 5.0
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
            survival.verticalOffset shouldBe 5.0
            survival.labelFrontDistance shouldBe 3.0
            survival.labelHeightOffset shouldBe -0.5
            survival.labelScale shouldBe 6.0f
            survival.labelBackgroundGray shouldBe 48
            survival.labelBackgroundAlpha shouldBe 180
            config.gateSettings(survival)!!.height shouldBe 16.8f
            config.gateSettings(survival)!!.itemIds shouldBe
                mapOf(
                    ru.arc.PortalVisualStyle.ORIGIN to "origin_gate_portals:origin_portal",
                    ru.arc.PortalVisualStyle.ASTRAL to "origin_gate_portals:astral_portal",
                    ru.arc.PortalVisualStyle.VOID to "origin_gate_portals:void_portal",
                )

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
            gallery.labelFrontDistance shouldBe 0.0
            gallery.labelScale shouldBe 0.9f
            gallery.labelBackgroundAlpha shouldBe 0
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
            verticalOffset = 5.0,
            labelFrontDistance = 3.0,
            labelSideOffset = 1.0,
            labelHeightOffset = 0.75,
            labelScale = 4.6f,
            labelBackgroundGray = 48,
            labelBackgroundAlpha = 180,
            style = ru.arc.PortalVisualStyle.ORIGIN,
        )
        val center = Location(world, 0.0, 0.0, 0.0)
        everyWorldName(world, "origin")

        anchor.contains(center).shouldBeTrue()
        anchor.contains(Location(world, 2.1, 0.0, 0.0)).shouldBeFalse()
        anchor.contains(Location(world, 0.0, 0.0, 6.1)).shouldBeFalse()
        anchor.contains(Location(world, 0.0, 8.41, 0.0)).shouldBeFalse()
        val hologram = anchor.labelLocation(world)
        hologram.x shouldBe 3.0
        hologram.y shouldBe 5.75
        (kotlin.math.abs(hologram.z - 1.0) < 1e-9).shouldBeTrue()
        hologram.yaw shouldBe anchor.yaw
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

    "hologram text, position, size and per-portal height can be tuned in config" {
        val directory = Files.createTempDirectory("arc-origin-portals-hologram")
        try {
            OriginPortalsConfig.load(directory)
            val source = ConfigManager.ofModule(directory, "origin-spawn.yml")
            source.setString("origin-portals.anchors.survival.hologram.text", "Новый мир")
            source.setDouble("origin-portals.anchors.survival.hologram.front-distance", 4.0)
            source.setDouble("origin-portals.anchors.survival.hologram.side-offset", -1.5)
            source.setDouble("origin-portals.anchors.survival.hologram.height-offset", 1.25)
            source.setDouble("origin-portals.anchors.survival.hologram.scale", 3.2)
            source.setInt("origin-portals.anchors.survival.hologram.background-gray", 72)
            source.setInt("origin-portals.anchors.survival.hologram.background-alpha", 210)
            source.setDouble("origin-portals.anchors.survival.vertical-offset", 4.5)
            source.saveStrict()
            ConfigManager.clear()
            val survival = OriginPortalsConfig.load(directory).anchors.first { it.id == OriginPortalId.SURVIVAL }
            survival.label shouldBe "Новый мир"
            survival.labelFrontDistance shouldBe 4.0
            survival.labelSideOffset shouldBe -1.5
            survival.labelHeightOffset shouldBe 1.25
            survival.labelScale shouldBe 3.2f
            survival.labelBackgroundGray shouldBe 72
            survival.labelBackgroundAlpha shouldBe 210
            survival.verticalOffset shouldBe 4.5
            OriginPortalsConfig.load(directory).gateSettings(survival)!!.verticalOffset shouldBe 4.5
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "administrator bypass covers central portals and their configured commands" {
        shouldBypassOriginPortal(OriginPortalId.SURVIVAL, hasBypassPermission = true).shouldBeTrue()
        shouldBypassOriginPortal(OriginPortalId.MINING, hasBypassPermission = true).shouldBeTrue()
        shouldBypassOriginPortal(OriginPortalId.VANILLA, hasBypassPermission = true).shouldBeTrue()
        shouldBypassOriginPortal(OriginPortalId.GALLERY_EXIT, hasBypassPermission = true).shouldBeFalse()
        shouldBypassOriginPortal(OriginPortalId.SURVIVAL, hasBypassPermission = false).shouldBeFalse()

        matchesPortalCommand(
            "/ARC RTP survival --only-if-first",
            "arc rtp survival --only-if-first",
        ).shouldBeTrue()
        matchesPortalCommand(
            "/arc rtp mining --only-if-first",
            "arc rtp survival --only-if-first",
        ).shouldBeFalse()
    }
})

private fun everyWorldName(world: World, name: String) {
    every { world.name } returns name
}
