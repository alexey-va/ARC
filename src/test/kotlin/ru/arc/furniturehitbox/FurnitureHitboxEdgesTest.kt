package ru.arc.furniturehitbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.bukkit.util.BoundingBox
import kotlin.math.abs

class FurnitureHitboxEdgesTest : StringSpec({
    "frame uses the real thin and offset click box without converting it to a block or model box" {
        val box = BoundingBox(13.125, 64.001, -1.75, 13.375, 64.101, -0.25)
        val edges = furnitureHitboxEdges(box)
        edges shouldHaveSize 12
        abs(edges.minOf { it.x } - -0.009) shouldBeLessThanOrEqual 1e-9
        abs(edges.maxOf { it.x + it.width } - (box.widthX + 0.009)) shouldBeLessThanOrEqual 1e-9
        abs(edges.maxOf { it.y + it.height } - (box.height + 0.009)) shouldBeLessThanOrEqual 1e-9
        abs(edges.maxOf { it.z + it.depth } - (box.widthZ + 0.009)) shouldBeLessThanOrEqual 1e-9
        // Joined rails touch faces only: no coincident outward faces at their corners.
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val a = edges[i]; val b = edges[j]
            val overlap = listOf(
                minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x),
                minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y),
                minOf(a.z + a.depth, b.z + b.depth) - maxOf(a.z, b.z),
            )
            overlap.all { it > 1e-9 } shouldBe false
        }
    }

    "tiny boxes retain positive bounded rods and invalid click boxes are refused" {
        val edges = furnitureHitboxEdges(BoundingBox(0.0, 0.0, 0.0, 0.01, 0.001, 0.03))
        edges shouldHaveSize 12
        edges.all { it.width > 0 && it.height > 0 && it.depth > 0 } shouldBe true
        shouldThrow<IllegalArgumentException> { furnitureHitboxEdges(BoundingBox(0.0, 0.0, 0.0, 1.0, 0.0, 1.0)) }
        shouldThrow<IllegalArgumentException> { furnitureHitboxEdges(BoundingBox(0.0, 0.0, 0.0, 17.0, 1.0, 1.0)) }
    }
})
