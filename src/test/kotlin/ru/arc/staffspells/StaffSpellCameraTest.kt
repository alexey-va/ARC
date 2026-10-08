package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f

class StaffSpellCameraTest : FreeSpec({
    "swept bounds account for the path even when both endpoints clear the eye" {
        val eye = Vector(0.0, 0.0, 0.0)
        val from = Vector(-4.0, 0.0, 0.0)
        val to = Vector(4.0, 0.0, 0.0)

        eye.distance(from) shouldBe 4.0
        eye.distance(to) shouldBe 4.0
        staffSweptDistance(eye, from, to) shouldBe 0.0
    }

    "standing and sneaking eye clearance includes the whole display cuboid" {
        val world = mockk<World>()
        val origin = Location(world, 0.0, 0.0, 0.0)
        val rotation = Quaternionf()
        val scale = Vector3f(0.3f, 0.3f, 0.3f)

        fun partAtEyeHeight(eyeHeight: Double, x: Float) = StaffDisplayPart(
            Material.CYAN_STAINED_GLASS,
            Vector3f(x, eyeHeight.toFloat(), 0f),
            Vector3f(scale),
            Quaternionf(),
        )

        val standingEye = Location(world, 0.0, 1.62, 0.0)
        val sneakingEye = Location(world, 0.0, 1.27, 0.0)

        staffPartClearOfEye(partAtEyeHeight(standingEye.y, 3.4f), origin, rotation, standingEye) shouldBe false
        staffPartClearOfEye(partAtEyeHeight(sneakingEye.y, 3.4f), origin, rotation, sneakingEye) shouldBe false
        staffPartClearOfEye(partAtEyeHeight(standingEye.y, 3.6f), origin, rotation, standingEye) shouldBe true
        staffPartClearOfEye(partAtEyeHeight(sneakingEye.y, 3.6f), origin, rotation, sneakingEye) shouldBe true
    }
})
