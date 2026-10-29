package ru.arc.backwings

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.joml.Vector3f

class BackWingPoseTest : FreeSpec({
    val settings = BackWingsSettings()
    val style = settings.styles.getValue(BackWing.DREADKNIGHT)

    "standing NONE pose compensates native full height once and presents the original YZ mesh across the back" {
        val pose = backWingPose(style, 0f, false, 1.8f, settings.crouchOffset, settings.crouchPitchDegrees)
        pose.translation.x shouldBe (0f plusOrMinus 0.0001f)
        pose.translation.y + 1.8f shouldBe (1.02f plusOrMinus 0.0001f)
        pose.translation.z shouldBe (-0.31f plusOrMinus 0.0001f)
        val width = pose.leftRotation.transform(Vector3f(0f, 0f, 1f))
        width.x shouldBe (1f plusOrMinus 0.0001f)
        width.z shouldBe (0f plusOrMinus 0.0001f)
        pose.scale.x shouldBe 0.70f
        // Source cube rotations give Z span 66.7412px and max Y 37.5772px.
        66.7412f / 16f * pose.scale.x shouldBe (2.92f plusOrMinus 0.01f)
        (37.5772f - 8f) / 16f * pose.scale.y + pose.translation.y + 1.8f shouldBe
            (2.314f plusOrMinus 0.01f)
    }

    "body yaw rotates anchor and mesh together without a camera pitch input" {
        val front = backWingPose(style, 0f, false, 1.8f, settings.crouchOffset, settings.crouchPitchDegrees)
        val turned = backWingPose(style, 90f, false, 1.8f, settings.crouchOffset, settings.crouchPitchDegrees)
        turned.translation.x shouldBe (0.31f plusOrMinus 0.0001f)
        turned.translation.y shouldBe front.translation.y
        turned.translation.z shouldBe (0f plusOrMinus 0.0001f)
        val width = turned.leftRotation.transform(Vector3f(0f, 0f, 1f))
        width.z shouldBe (1f plusOrMinus 0.0001f)
        width.x shouldBe (0f plusOrMinus 0.0001f)
    }

    "crouch follows the torso lean and lower attachment without inheriting passenger height change" {
        val crouch = backWingPose(style, 0f, true, 1.5f, settings.crouchOffset, settings.crouchPitchDegrees)
        crouch.translation.y + 1.5f shouldBe (0.82f plusOrMinus 0.0001f)
        crouch.translation.z shouldBe (-0.19f plusOrMinus 0.0001f)
        val up = crouch.leftRotation.transform(Vector3f(0f, 1f, 0f))
        up.y shouldBe (0.877583f plusOrMinus 0.0001f)
        up.z shouldBe (0.479426f plusOrMinus 0.0001f)
        val scaledCarrier = backWingPose(style, 0f, true, 3f, settings.crouchOffset, settings.crouchPitchDegrees)
        scaledCarrier.translation.y + 3f shouldBe (crouch.translation.y + 1.5f plusOrMinus 0.0001f)
    }
})
