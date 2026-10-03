package ru.arc.treasurechests

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.util.Vector
import org.joml.Vector3f
import kotlin.math.abs

class TreasureHuntGrappleMathTest :
    DescribeSpec({
        describe("grapple cable geometry") {
            it("covers the path with centered, contiguous segments of equal total length") {
                val from = Vector(3.0, -2.0, 5.0)
                val to = Vector(9.0, 6.0, 2.0)
                val segments = TreasureHuntGrappleMath.cableSegments(from, to, 4)
                val expectedLength = from.distance(to)

                segments.size shouldBe 4
                segments.forEachIndexed { index, segment ->
                    val start = segment.center.clone().subtract(segment.direction.clone().multiply(0.5))
                    val end = segment.center.clone().add(segment.direction.clone().multiply(0.5))
                    segment.length shouldBeCloseTo (expectedLength / 4.0)
                    segment.direction.length() shouldBeCloseTo segment.length
                    assertVectorClose(segment.center, start.clone().add(end).multiply(0.5))

                    if (index == 0) {
                        assertVectorClose(start, from)
                    } else {
                        val previous = segments[index - 1]
                        val previousEnd = previous.center.clone().add(previous.direction.clone().multiply(0.5))
                        assertVectorClose(start, previousEnd)
                    }

                    if (index == segments.lastIndex) assertVectorClose(end, to)
                }
                segments.sumOf { it.length } shouldBeCloseTo expectedLength
            }

            it("caps a requested cable at twelve segments without shortening it") {
                val from = Vector(-7.0, 4.0, 2.0)
                val to = Vector(11.0, 4.0, -13.0)
                val segments = TreasureHuntGrappleMath.cableSegments(from, to, 99)

                segments.size shouldBe TreasureHuntGrappleMath.MAX_CABLE_SEGMENTS
                segments.sumOf { it.length } shouldBeCloseTo from.distance(to)
            }

            it("returns no segments when both endpoints are the same") {
                val point = Vector(1.25, 70.0, -8.5)

                TreasureHuntGrappleMath.cableSegments(point, point.clone(), 8) shouldBe emptyList()
            }

            it("places the rendered block model endpoints at horizontal and inclined cable endpoints") {
                assertRenderedCableEndpoints(Vector(2.0, 3.0, 4.0), Vector(6.0, 3.0, 4.0))
                assertRenderedCableEndpoints(Vector(-4.0, 2.0, 9.0), Vector(2.0, 8.0, 5.0))
            }
        }

        describe("grapple pull velocity") {
            it("caps speed and stops at or inside the configured stopping distance") {
                val from = Vector(0.0, 0.0, 0.0)
                val to = Vector(3.0, 0.0, 4.0)
                val velocity =
                    TreasureHuntGrappleMath.pullVelocity(from, to, stopDistance = 1.0, requestedSpeed = 9.0)
                        .shouldNotBeNull()
                velocity.length() shouldBeCloseTo TreasureHuntGrappleMath.MAX_PULL_SPEED
                TreasureHuntGrappleMath.pullVelocity(from, to, stopDistance = 5.0, requestedSpeed = 1.0) shouldBe null
                TreasureHuntGrappleMath.pullVelocity(from, to, stopDistance = 6.0, requestedSpeed = 1.0) shouldBe null
            }

            it("keeps vertical and near-vertical pulls free of extra sideways velocity") {
                val origin = Vector(0.0, 0.0, 0.0)
                val vertical =
                    TreasureHuntGrappleMath.pullVelocity(
                        origin,
                        Vector(0.0, 12.0, 0.0),
                        stopDistance = 1.0,
                        requestedSpeed = 1.0,
                    ).shouldNotBeNull()
                vertical.x shouldBe 0.0
                vertical.z shouldBe 0.0
                vertical.y shouldBe 0.75

                val nearVerticalTarget = Vector(0.01, 10.0, -0.02)
                val nearVertical =
                    TreasureHuntGrappleMath.pullVelocity(
                        origin,
                        nearVerticalTarget,
                        stopDistance = 1.0,
                        requestedSpeed = 1.25,
                    ).shouldNotBeNull()
                val length = origin.distance(nearVerticalTarget)
                (nearVertical.x - nearVerticalTarget.x / length * 1.25) shouldBeCloseTo 0.0
                (nearVertical.z - nearVerticalTarget.z / length * 1.25) shouldBeCloseTo 0.0
                nearVertical.y shouldBe 0.75
            }
        }
    })

private fun assertRenderedCableEndpoints(from: Vector, to: Vector) {
    val width = 0.075f
    val segment = TreasureHuntGrappleMath.cableSegments(from, to, 1).single()
    val transform = TreasureHuntGrappleMath.cableTransform(segment, width)

    fun renderedCenterlinePoint(progress: Float): Vector {
        val localScaledPoint = Vector3f(width / 2f, segment.length.toFloat() * progress, width / 2f)
        transform.rotation.transform(localScaledPoint).add(transform.translation)
        return Vector(
            segment.center.x + localScaledPoint.x,
            segment.center.y + localScaledPoint.y,
            segment.center.z + localScaledPoint.z,
        )
    }

    assertVectorClose(renderedCenterlinePoint(0f), from)
    assertVectorClose(renderedCenterlinePoint(1f), to)
}

private fun assertVectorClose(actual: Vector, expected: Vector) {
    (actual.x - expected.x).shouldBeCloseTo(0.0)
    (actual.y - expected.y).shouldBeCloseTo(0.0)
    (actual.z - expected.z).shouldBeCloseTo(0.0)
}

private infix fun Double.shouldBeCloseTo(expected: Double) {
    abs(this - expected) shouldBeLessThanOrEqual 1.0e-5
}
