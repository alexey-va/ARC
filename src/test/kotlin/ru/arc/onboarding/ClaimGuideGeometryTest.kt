package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ClaimGuideGeometryTest : FreeSpec({
    "button gesture never consumes placement or ordinary left clicks" {
        for (action in org.bukkit.event.block.Action.entries) {
            claimGuideButtonGesture(action, org.bukkit.inventory.EquipmentSlot.HAND, false) shouldBe false
            claimGuideButtonGesture(action, org.bukkit.inventory.EquipmentSlot.OFF_HAND, true) shouldBe false
        }
        claimGuideButtonGesture(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe false
        claimGuideButtonGesture(org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe false
        claimGuideButtonGesture(org.bukkit.event.block.Action.LEFT_CLICK_AIR, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe true
        claimGuideButtonGesture(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, org.bukkit.inventory.EquipmentSlot.HAND, true) shouldBe true
    }
    "frozen side button can be targeted but rejects placement crosshair, back and distant clicks" {
        val eye = Location(null, 10.0, 70.0, 20.0, 0f, 0f)
        val button = claimGuideButtonLocation(eye)
        claimGuideButtonHit(eye, button) shouldBe false
        val aimed = eye.clone().setDirection(button.toVector().add(org.bukkit.util.Vector(0.0, 0.22, 0.0)).subtract(eye.toVector()))
        claimGuideButtonHit(aimed, button) shouldBe true
        claimGuideButtonHit(aimed.clone().setDirection(aimed.direction.multiply(-1)), button) shouldBe false
        claimGuideButtonHit(aimed.clone().subtract(aimed.direction.multiply(10)), button) shouldBe false
        claimGuideButtonHit(eye.clone().setDirection(button.toVector().add(org.bukkit.util.Vector(0.0, 1.5, 0.0)).subtract(eye.toVector())), button) shouldBe false
        eye shouldBe Location(null, 10.0, 70.0, 20.0, 0f, 0f)
    }

    "hologram stays in front of the eyes without a target block and leaves the player location unchanged" {
        val eye = Location(null, 10.0, 70.0, 20.0, 0f, 0f)
        claimGuideLabelLocation(eye) shouldBe Location(null, 10.0, 70.35, 24.0, 0f, 0f)
        eye shouldBe Location(null, 10.0, 70.0, 20.0, 0f, 0f)
        val lookingUp = Location(null, 10.0, 70.0, 20.0, 0f, -90f)
        claimGuideLabelLocation(lookingUp).y shouldBe 74.35
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
})
