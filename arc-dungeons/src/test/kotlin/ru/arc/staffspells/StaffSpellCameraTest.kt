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

    "standing and sneaking aim clearance includes the whole display cuboid" {
        val world = mockk<World>()
        val origin = Location(world, 0.0, 0.0, 0.0)
        val rotation = Quaternionf()
        val scale = Vector3f(0.3f, 0.3f, 0.3f)

        fun partAtEyeHeight(eyeHeight: Double, z: Float) = StaffDisplayPart(
            Material.CYAN_STAINED_GLASS,
            Vector3f(0f, eyeHeight.toFloat(), z),
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

    "near effects retain peripheral fragments while the eye and swept aim corridor stay clear" {
        val eye = Vector()
        val forward = Vector(0.0, 0.0, 1.0)
        fun visible(from: Vector, to: Vector = from, facing: Vector = forward) =
            staffSweptPartClearOfEye(eye, facing, from, to, .15, 3.35)

        visible(Vector(1.0, 0.0, .5)) shouldBe true
        visible(Vector(0.0, -1.0, .5)) shouldBe true
        visible(Vector(.2, 0.0, 0.0)) shouldBe false
        visible(Vector(0.0, 0.0, 1.0)) shouldBe false
        visible(Vector(-1.0, 0.0, 0.0), Vector(1.0, 0.0, 4.0)) shouldBe false
        visible(Vector(0.0, 0.0, 1.0), facing = Vector(1.0, 0.0, 0.0)) shouldBe true
        visible(Vector(1.0, 0.0, .3), facing = Vector(1.0, 0.0, 0.0)) shouldBe false
    }

    "rupture and fault keep part of their native silhouette when the viewer enters them" {
        val world = mockk<World>()
        val origin = Location(world, 0.0, 0.0, 0.0)
        listOf(StaffSpell.MARK, StaffSpell.FAULT).forEach { spell ->
            val parts = staffDisplayParts(spell, 8, 22, 0.0,
                if (spell == StaffSpell.MARK) 3.5 else 2.0, impact = true)
            val clearance = staffEyeClearance(spell, true) + .15
            // A body-centered mark is .9 blocks above the ground; also cover a ground-anchored effect.
            listOf(.72, 1.62).forEach { relativeEyeHeight ->
                val eye = Location(world, 0.0, relativeEyeHeight, 0.0)
                val oldVisible = parts.count { it.center.distance(Vector3f(0f, relativeEyeHeight.toFloat(), 0f)) >= clearance + it.scale.length() * .5 }
                val nearVisible = parts.count { staffPartClearOfEye(it, origin, Quaternionf(), eye, clearance) }
                (nearVisible > oldVisible) shouldBe true
                (nearVisible in 1 until parts.size) shouldBe true
                val distant = eye.clone().add(0.0, 0.0, -12.0)
                parts.all { staffPartClearOfEye(it, origin, Quaternionf(), distant, clearance) } shouldBe true
            }
        }
    }
})
