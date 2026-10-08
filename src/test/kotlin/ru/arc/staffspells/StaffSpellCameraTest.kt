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

    "compact centered flight stays visible near the reticle while large impacts keep their clearance" {
        val world = mockk<World>()
        val eye = Location(world, 0.0, 1.62, 0.0)
        val part = StaffDisplayPart(Material.SEA_LANTERN, Vector3f(0f, 0f, 2f),
            Vector3f(0.55f, 0.55f, 0.55f), Quaternionf())
        staffPartClearOfEye(part, eye, Quaternionf(), eye,
            staffEyeClearance(StaffSpell.CHAIN, false) + 0.15) shouldBe true
        staffPartClearOfEye(part.copy(center = Vector3f(0f, 0f, 1f)), eye, Quaternionf(), eye,
            staffEyeClearance(StaffSpell.CHAIN, false) + 0.15) shouldBe false
        staffPartClearOfEye(part, eye, Quaternionf(), eye,
            staffEyeClearance(StaffSpell.EMBER, true) + 0.15) shouldBe false
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
