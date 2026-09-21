package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

class EMWormholeBoundaryTest : FreeSpec({
    "boundary matches the native sphere at the walking surface" {
        val boundary = wormholeBoundaryCircle(centerY = 101.5, surfaceY = 101.0, sizeMultiplier = 1.0)!!

        boundary.surfaceY shouldBe 101.0
        boundary.radius shouldBe (sqrt(2.0) plusOrMinus 1.0e-12)
    }

    "boundary scales with a smaller native wormhole" {
        val boundary = wormholeBoundaryCircle(centerY = 101.5, surfaceY = 101.0, sizeMultiplier = 0.75)!!

        boundary.radius shouldBe (sqrt(1.015625) plusOrMinus 1.0e-12)
    }

    "points form one crisp evenly sampled ring without duplicating the first point" {
        val points = wormholeBoundaryPoints(
            centerY = 10.0,
            centerSurfaceY = 10.0,
            sizeMultiplier = 4.0 / 3.0,
            pointCount = 24,
            surfaceYAt = { _, _ -> 10.0 },
        )

        points.size shouldBe 24
        points.forEach { point ->
            (point.x * point.x + point.z * point.z) shouldBe (4.0 plusOrMinus 1.0e-12)
            point.surfaceY shouldBe 10.0
        }
        points.first().x shouldBe (2.0 plusOrMinus 1.0e-12)
        points.last().x shouldBe (2.0 * kotlin.math.cos(2.0 * kotlin.math.PI * 23.0 / 24.0) plusOrMinus 1.0e-12)
    }

    "each point follows its local collision surface and remains on the native sphere" {
        val points = wormholeBoundaryPoints(
            centerY = 10.0,
            centerSurfaceY = 9.0,
            sizeMultiplier = 1.0,
            pointCount = 4,
            surfaceYAt = { x, _ -> if (x > 0.0) 9.5 else 9.0 },
        )

        points.first().surfaceY shouldBe 9.5
        points.first().x shouldBe (sqrt(2.0) plusOrMinus 1.0e-12)
        points[2].surfaceY shouldBe 9.0
        points[2].x shouldBe (-sqrt(1.25) plusOrMinus 1.0e-12)
    }

    "point is omitted when a sharp step has no self-consistent horizontal surface" {
        wormholeBoundaryPoint(
            centerY = 10.0,
            centerSurfaceY = 9.0,
            sizeMultiplier = 1.0,
            xUnit = 1.0,
            zUnit = 0.0,
            surfaceYAt = { x, _ -> if (x < 1.2) 9.5 else 9.0 },
        ) shouldBe null
    }

    "point is omitted above a column without collision support" {
        wormholeBoundaryPoint(
            centerY = 10.0,
            centerSurfaceY = 9.0,
            sizeMultiplier = 1.0,
            xUnit = 1.0,
            zUnit = 0.0,
            surfaceYAt = { _, _ -> null },
        ) shouldBe null
    }

    "round robin does not permanently starve visible rings" {
        roundRobinSlice(listOf(0, 1, 2, 3, 4, 5, 6), start = 0, count = 6) shouldBe listOf(0, 1, 2, 3, 4, 5)
        roundRobinSlice(listOf(0, 1, 2, 3, 4, 5, 6), start = 6, count = 6) shouldBe listOf(6, 0, 1, 2, 3, 4)
    }

    "surface outside the native trigger has no boundary" {
        wormholeBoundaryCircle(centerY = 10.0, surfaceY = 8.0, sizeMultiplier = 1.0) shouldBe null
    }

    "walking surface is the highest collision top inside the native sphere" {
        wormholeSurfaceY(
            centerY = 101.5,
            triggerRadius = 1.5,
            candidateTops = sequenceOf(100.0, 101.0, 102.0, Double.NaN),
        ) shouldBe 101.0
    }
})
