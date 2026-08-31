package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class MountTrailGeometryTest : StringSpec({
    val trail =
        MountTrailDefinition(
            particle = "SOUL",
            displayName = "След душ",
            backOffset = 0.25,
            heightRatio = 0.5,
        )
    val bounds = MountTrailBounds(-0.5, 10.0, -1.0, 0.5, 12.0, 1.0)

    "rear trail follows the moving body instead of the entity origin" {
        val origin = mountTrailOrigin(bounds, 0f, MotionVector(0.0, 0.0, 1.0), trail)

        origin.x shouldBe (0.0 plusOrMinus 1.0e-9)
        origin.y shouldBe (11.0 plusOrMinus 1.0e-9)
        origin.z shouldBe (-1.25 plusOrMinus 1.0e-9)
    }

    "stationary trail uses yaw and stays finite" {
        val origin = mountTrailOrigin(bounds, 90f, MotionVector.ZERO, trail)

        origin.x shouldBe (0.75 plusOrMinus 1.0e-9)
        origin.y.isFinite() shouldBe true
        origin.z.isFinite() shouldBe true
    }

    "larger live bounds move the trail farther behind the body" {
        val small = mountTrailOrigin(bounds, 0f, MotionVector.ZERO, trail)
        val large = mountTrailOrigin(MountTrailBounds(-1.0, 10.0, -2.0, 1.0, 14.0, 2.0), 0f, MotionVector.ZERO, trail)

        (large.z < small.z) shouldBe true
        large.y shouldBe (12.0 plusOrMinus 1.0e-9)
    }
})
