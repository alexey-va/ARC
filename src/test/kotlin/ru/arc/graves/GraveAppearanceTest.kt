package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.abs

class GraveAppearanceTest : FunSpec({
    test("grave cosmetics are stable and changing epitaphs does not reroll the model or front") {
        val settings = GraveAppearanceSettings()
        val ids = (0L..99L).map { UUID(7L, it) }
        val appearances = ids.map { GraveAppearances.select(it, settings) }
        appearances shouldBe ids.map { GraveAppearances.select(it, settings) }
        appearances.map { it.model.id }.toSet() shouldBe GraveModels.all.map { it.id }.toSet()
        ids.zip(appearances).forEach { (id, original) ->
            val changed = GraveAppearances.select(id, settings.copy(epitaphs = listOf("Я успею.")))
            changed.model shouldBe original.model
            changed.yaw shouldBe original.yaw
            changed.epitaph shouldBe "Я успею."
        }
    }

    test("zero weights disable variants and invalid or overflowing totals still produce a scene") {
        val id = UUID(12L, 34L)
        GraveModels.all.forEach { model ->
            GraveAppearances.select(id, GraveAppearanceSettings(mapOf(model.id to 1.0))).model shouldBe model
        }
        val invalid = GraveAppearanceSettings(mapOf("slate" to 0.0, "timber" to -1.0, "obelisk" to Double.NaN))
        GraveAppearances.select(id, invalid).model shouldBe GraveModels.slate
        val large = GraveAppearanceSettings(mapOf("slate" to Double.MAX_VALUE, "timber" to Double.MAX_VALUE))
        (0L..50L).map { GraveAppearances.select(UUID(9L, it), large).model.id }.toSet() shouldBe setOf("slate", "timber")
        (GraveAppearances.select(id, invalid.copy(epitaphs = listOf(" "))).epitaph in GraveAppearanceSettings.DEFAULT_EPITAPHS) shouldBe true
    }

    test("rotated silhouettes stay within one block and default inscriptions keep a readable scale") {
        GraveModels.all.forEach { model ->
            (model.parts.size <= 11) shouldBe true
            model.parts.forEach { part ->
                val rotation = Quaternionf().rotationXYZ(
                    Math.toRadians(part.rotationX.toDouble()).toFloat(),
                    Math.toRadians(part.rotationY.toDouble()).toFloat(),
                    Math.toRadians(part.rotationZ.toDouble()).toFloat(),
                )
                for (sx in listOf(-.5f, .5f)) for (sy in listOf(-.5f, .5f)) for (sz in listOf(-.5f, .5f)) {
                    val point = Vector3f(sx * part.width, sy * part.height, sz * part.depth).rotate(rotation).add(
                        (part.x + part.width / 2).toFloat(),
                        (part.y + part.height / 2).toFloat(),
                        (part.z + part.depth / 2).toFloat(),
                    )
                    (abs(point.x) <= .5f && abs(point.z) <= .5f && point.y >= -.000001f && point.y <= 1.3f) shouldBe true
                }
            }
            GraveAppearanceSettings.DEFAULT_EPITAPHS.forEach { epitaph ->
                val placement = model.inscription.placement(epitaph)
                (placement.scale >= .25f) shouldBe true
                (placement.bottomY >= model.inscription.centerY - model.inscription.maxHeight / 2 - .000001) shouldBe true
            }
        }
    }
})
