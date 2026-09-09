package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ClaimGuideGeometryTest : FreeSpec({
    "button gesture accepts either mouse button only while sneaking with the main hand" {
        for (action in org.bukkit.event.block.Action.entries) {
            claimGuideButtonGesture(action, org.bukkit.inventory.EquipmentSlot.HAND, false) shouldBe false
            claimGuideButtonGesture(action, org.bukkit.inventory.EquipmentSlot.OFF_HAND, true) shouldBe false
        }
        claimGuideButtonGesture(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe true
        claimGuideButtonGesture(org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe true
        claimGuideButtonGesture(org.bukkit.event.block.Action.LEFT_CLICK_AIR, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe true
        claimGuideButtonGesture(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe true
    }
    "frozen side button can be targeted but rejects placement crosshair, back and distant clicks" {
        val eye = Location(null, 10.0, 70.0, 20.0, 0f, 0f)
        val button = claimGuideButtonLocation(eye)
        claimGuideButtonHit(eye, button) shouldBe false
        claimGuideButtonHit(eye.clone().subtract(0.0, 0.35, 0.0), button) shouldBe false
        val aimed = eye.clone().setDirection(button.toVector().add(org.bukkit.util.Vector(0.0, 0.44, 0.0)).subtract(eye.toVector()))
        claimGuideButtonHit(aimed, button) shouldBe true
        claimGuideButtonHit(aimed.clone().setDirection(aimed.direction.multiply(-1)), button) shouldBe false
        claimGuideButtonHit(aimed.clone().subtract(aimed.direction.multiply(10)), button) shouldBe false
        claimGuideButtonHit(eye.clone().setDirection(button.toVector().add(org.bukkit.util.Vector(0.0, 3.0, 0.0)).subtract(eye.toVector())), button) shouldBe false
        eye shouldBe Location(null, 10.0, 70.0, 20.0, 0f, 0f)
    }

    "frozen central hologram opens only after aiming at the visible panel" {
        val eye = Location(null, 10.0, 70.0, 20.0, 0f, 0f)
        val label = claimGuideLabelLocation(eye)
        claimGuideButtonHit(eye, label, 3.8, 1.0) shouldBe false
        val aimed = eye.clone().setDirection(label.toVector().add(org.bukkit.util.Vector(0.0, 0.5, 0.0)).subtract(eye.toVector()))
        claimGuideButtonHit(aimed, label, 3.8, 1.0) shouldBe true
    }

    "billboard click follows the camera rather than the frozen spawn yaw" {
        for (yaw in listOf(-170f, -90f, 0f, 90f, 170f)) {
            for (pitch in listOf(-70f, 0f, 70f)) {
                val eye = Location(null, 10.0, 70.0, 20.0, yaw, pitch)
                val right = org.bukkit.util.Vector(kotlin.math.cos(Math.toRadians(yaw.toDouble())), 0.0,
                    kotlin.math.sin(Math.toRadians(yaw.toDouble())))
                val up = eye.direction.crossProduct(right).normalize()
                val anchor = eye.clone().add(eye.direction.multiply(4.0)).subtract(up.clone().multiply(0.44))
                anchor.yaw = yaw + 130f
                claimGuideButtonHit(eye, anchor) shouldBe true
                claimGuideButtonHit(eye, anchor.clone().add(right.clone().multiply(2.6))) shouldBe false
                claimGuideButtonHit(eye, anchor.clone().subtract(up.clone().multiply(1.0))) shouldBe false
                claimGuideButtonHit(eye, anchor.clone().add(up.clone().multiply(1.0))) shouldBe false
            }
        }
    }
    "free grid never gets a thick perimeter while real land has no internal dividers" {
        val visible = claimGuideChunks(GuideChunk(0, 0), 1)
        val free = claimGuideBorders(visible, emptyMap())
        free.size shouldBe 24
        free.all { it.landId == null } shouldBe true
        val occupied = mapOf(GuideChunk(0, 0) to "home", GuideChunk(1, 0) to "home")
        val borders = claimGuideBorders(visible, occupied)
        borders.filter { it.landId == "home" }.size shouldBe 6
        borders.single { it.edge == GuideEdge(16, 0, false) }.landId shouldBe null
        borders.map { it.edge }.distinct().size shouldBe borders.size
        claimGuideBorderY(70.62) shouldBe 69.62
    }
    "all visible chunks get thin grid and intersections are unique" {
        val chunks = claimGuideChunks(GuideChunk(-1, -1), 1)
        claimGuideGridEdges(chunks).size shouldBe 24
        claimGuideGridEdges(chunks).distinct().size shouldBe 24
        claimGuideIntersections(chunks).size shouldBe 16
        claimGuideIntersections(chunks).contains(GuideChunk(-1, -1)) shouldBe true
        claimGuideIntersections(chunks).contains(GuideChunk(1, 1)) shouldBe true
    }
    "moving the visible window does not invent a border through a larger land" {
        val claims = claimGuideChunks(GuideChunk(0, 0), 2).associateWith { "home" }
        val borders = claimGuideBorders(claimGuideChunks(GuideChunk(0, 0), 1), claims)
        borders.size shouldBe 24
        borders.all { it.landId == null } shouldBe true
    }

    "hologram stays in front of the eyes without a target block and leaves the player location unchanged" {
        val eye = Location(null, 10.0, 70.0, 20.0, 0f, 0f)
        claimGuideLabelLocation(eye) shouldBe Location(null, 10.0, 70.65, 24.0, 0f, 0f)
        eye shouldBe Location(null, 10.0, 70.0, 20.0, 0f, 0f)
        val lookingUp = Location(null, 10.0, 70.0, 20.0, 0f, -90f)
        claimGuideLabelLocation(lookingUp).y shouldBe 74.65
    }
    "expansion names remain literal even when the land name contains markup" {
        val result = claimGuideLandText(Component.text("Расширить «{land}»"), "<red>Дом")
        PlainTextComponentSerializer.plainText().serialize(result) shouldBe "Расширить «<red>Дом»"
    }
    "held preview uses the player chunk without a ground hit and the placement chunk across a border" {
        claimGuideTarget(null, null, -1, 32) shouldBe GuideChunk(-1, 2)
        claimGuideTarget(16, 32, 15, 32) shouldBe GuideChunk(1, 2)
    }
    "native radius one covers nine chunks with a 48 by 48 outer border" {
        val chunks = claimGuideChunks(GuideChunk(-1, 0), 1)
        chunks.size shouldBe 9
        chunks shouldBe (-2..0).flatMap { x -> (-1..1).map { z -> GuideChunk(x, z) } }.toSet()
        claimGuideEdges(chunks).size shouldBe 12
        claimGuideChunks(GuideChunk(0, 0), 0) shouldBe setOf(GuideChunk(0, 0))
    }
    "adjacent claims have only the outer region border, including negative coordinates" {
        val edges = claimGuideEdges(setOf(GuideChunk(-1, 0), GuideChunk(0, 0)))
        edges.size shouldBe 6
        edges.count { !it.alongX && it.x == 0 } shouldBe 0
        edges.toSet() shouldBe setOf(
            GuideEdge(-16, 0, true), GuideEdge(-16, 16, true), GuideEdge(-16, 0, false),
            GuideEdge(0, 0, true), GuideEdge(0, 16, true), GuideEdge(16, 0, false),
        )
    }
    "holes stay visible and duplicate segments are not produced" {
        val ring = buildSet {
            for (x in -1..1) for (z in -1..1) if (x != 0 || z != 0) add(GuideChunk(x, z))
        }
        val edges = claimGuideEdges(ring)
        edges.size shouldBe 16
        edges.toSet().size shouldBe 16
        edges.containsAll(claimGuideEdges(setOf(GuideChunk(0, 0)))) shouldBe true
        claimGuideEdges(emptySet()) shouldBe emptyList()
    }
    "outside-neighbor lookup follows every real side across negative chunk boundaries" {
        val interior = setOf(GuideChunk(-1, -1), GuideChunk(0, -1))
        val outside = claimGuideEdges(interior).map { claimGuideEdgeOutside(it, interior) }.toSet()
        outside shouldBe setOf(GuideChunk(-1, -2), GuideChunk(-1, 0), GuideChunk(-2, -1),
            GuideChunk(0, -2), GuideChunk(0, 0), GuideChunk(1, -1))
    }
    "wilderness grid keeps internal separators and deduplicates negative coordinates" {
        val chunks = claimGuideChunks(GuideChunk(-1, -1), 1)
        val edges = claimGuideWildernessEdges(chunks, emptySet())
        edges.size shouldBe 24
        edges.toSet().size shouldBe 24
    }
    "wilderness grid omits edges against occupied chunks" {
        val chunks = claimGuideChunks(GuideChunk(0, 0), 1)
        val edges = claimGuideWildernessEdges(chunks, setOf(GuideChunk(0, 0)))
        edges.size shouldBe 20
        edges.none { it == GuideEdge(0, 0, true) } shouldBe true
        edges.none { it == GuideEdge(0, 0, false) } shouldBe true
    }
})
