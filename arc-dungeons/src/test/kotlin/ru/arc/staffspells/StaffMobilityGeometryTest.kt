package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI

class StaffMobilityGeometryTest : FreeSpec({
    "mobility assemblies and elongated cube models follow the same travel heading" {
        val quarterTurn = Quaternionf().rotationY((PI / 2.0).toFloat())
        StaffSpell.available.forEach { spell ->
            val duration = requireNotNull(staffMobilityProfile(spell)).durationTicks
            val forward = staffMobilityDisplayParts(spell, 4, duration, Vector(0.0, 0.0, 1.0))
            val turned = staffMobilityDisplayParts(spell, 4, duration, Vector(1.0, 0.0, 0.0))
            forward.zip(turned).all { (a, b) ->
                quarterTurn.transform(Vector3f(a.center)).distance(b.center) < .001f
            } shouldBe true
        }
        val nose = staffMobilityDisplayParts(StaffSpell.FROST, 4, 12, Vector(1.0, 0.0, 0.0))
            .single { it.identity == "mobility:frost:frost-nose" }
        (nose.rotation.transform(Vector3f(0f, 0f, 1f)).distance(Vector3f(1f, 0f, 0f)) < .001f) shouldBe true
    }

    "LANCE vault and OSSUARY ram keep every transformed part below the level caster sightline" {
        listOf(StaffSpell.LANCE, StaffSpell.OSSUARY).forEach { spell ->
            val duration = requireNotNull(staffMobilityProfile(spell)).durationTicks
            listOf(0, duration / 2, duration).forEach { age ->
                val parts = staffMobilityDisplayParts(spell, age, duration, Vector(0.0, 0.0, 1.0))
                val corners = parts.flatMap { part ->
                    listOf(-.5f, .5f).flatMap { x -> listOf(-.5f, .5f).flatMap { y ->
                        listOf(-.5f, .5f).map { z ->
                            part.rotation.transform(Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z))
                                .add(part.center)
                        }
                    } }
                }
                (corners.maxOf { it.y } < 1.25f) shouldBe true
            }
        }
    }
})
