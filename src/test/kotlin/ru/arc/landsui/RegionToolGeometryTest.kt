package ru.arc.landsui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe

class RegionToolGeometryTest : FreeSpec({
    "covers every interior chunk including negative coordinates" {
        val box = RegionBox.between(RegionPoint(16, 16), RegionPoint(-1, -1))
        box.width shouldBe 18L
        box.depth shouldBe 18L
        box.chunkCount shouldBe 9L
        box.chunks().toSet() shouldBe (-1..1).flatMap { x -> (-1..1).map { z -> x to z } }.toSet()
    }

    "accepts readable names while preventing command arguments and formatting" {
        regionToolName("  Дом_2  ") shouldBe "Дом_2"
        listOf("", "дом друга", "a\nb", "<red>дом", "a;assign", "x".repeat(25)).forEach { name ->
            shouldThrow<IllegalArgumentException> { regionToolName(name) }
        }
    }

    "builds a 12-edge visible cage for a nearby box" {
        val frame = regionToolFrame(RegionBox(0, 0, 1, 1), RegionPoint(1, 1))

        frame shouldHaveSize 12
        frame.count { it.axis == RegionFrameAxis.X } shouldBe 4
        frame.count { it.axis == RegionFrameAxis.Z } shouldBe 4
        frame.count { it.axis == RegionFrameAxis.Y } shouldBe 4
        frame.filter { it.axis == RegionFrameAxis.X || it.axis == RegionFrameAxis.Z }
            .map { it.yOffset }
            .toSet() shouldContainExactlyInAnyOrder setOf(-1.2, 1.2)
        frame.filter { it.axis == RegionFrameAxis.Y }.forEach {
            it.yOffset shouldBeExactly -1.2
            it.length shouldBeExactly 2.4
        }
    }

    "does not invent vertical corners at a distance cutoff" {
        val frame = regionToolFrame(RegionBox(-100, -100, 100, 100), RegionPoint(0, 0))

        frame.none { it.axis == RegionFrameAxis.Y } shouldBe true
    }

    "uses inclusive max plus one coordinates for negative bounds" {
        val frame = regionToolFrame(RegionBox(-17, -33, -16, -32), RegionPoint(-16, -32))

        frame.filter { it.axis == RegionFrameAxis.X }.map { it.z }.toSet() shouldBe setOf(-33.0, -31.0)
        frame.filter { it.axis == RegionFrameAxis.Z }.map { it.x }.toSet() shouldBe setOf(-17.0, -15.0)
        frame.filter { it.axis == RegionFrameAxis.Y }.map { it.x to it.z }
            .toSet() shouldContainExactlyInAnyOrder setOf(
                -17.0 to -33.0,
                -17.0 to -31.0,
                -15.0 to -33.0,
                -15.0 to -31.0,
            )
    }
})
