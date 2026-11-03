package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import java.util.UUID
import kotlin.math.abs

class GraveAppearanceTest : FunSpec({
    test("grave cosmetics are stable and changing epitaphs does not reroll the model or front") {
        val settings = GraveAppearanceSettings()
        settings.epitaphs shouldBe emptyList()
        val ids = (0L..255L).map { UUID(7L, it) }
        val appearances = ids.map { GraveAppearances.select(it, settings) }
        appearances shouldBe ids.map { GraveAppearances.select(it, settings) }
        appearances.map { it.model }.toSet() shouldBe GraveModels.all.toSet()
        ids.zip(appearances).forEach { (id, original) ->
            original.epitaph shouldBe ""
            val changed = GraveAppearances.select(id, settings.copy(epitaphs = listOf("Похуй...")))
            changed.model shouldBe original.model
            changed.yaw shouldBe original.yaw
            changed.epitaph shouldBe "Похуй..."
        }
    }

    test("zero weights disable variants and invalid or overflowing totals still produce a scene") {
        val id = UUID(12L, 34L)
        GraveModels.all.forEach { model ->
            val settings = GraveAppearanceSettings(mapOf(model.id to 1.0), engravedChance = if (model.engraved) 1.0 else 0.0)
            GraveAppearances.select(id, settings).model shouldBe model
        }
        val invalid = GraveAppearanceSettings(mapOf("slate" to 0.0, "timber" to -1.0, "obelisk" to Double.NaN), engravedChance = 0.0)
        GraveAppearances.select(id, invalid).model shouldBe GraveModels.slate
        val large = GraveAppearanceSettings(mapOf("slate" to Double.MAX_VALUE, "timber" to Double.MAX_VALUE))
        (0L..50L).map { GraveAppearances.select(UUID(9L, it), large).model.id }.toSet() shouldBe setOf("slate", "timber")
        GraveAppearances.select(id, invalid.copy(epitaphs = listOf(" "))).epitaph shouldBe ""
    }

    test("engraving chance only switches geometry and unavailable images preserve the original style") {
        val settings = GraveAppearanceSettings(epitaphs = listOf("Похуй...", "Да я свой"))
        (0L..255L).forEach { seed ->
            val id = UUID(2, seed)
            val original = GraveAppearances.select(id, settings.copy(engravedChance = 0.0))
            val engraved = GraveAppearances.select(id, settings.copy(engravedChance = 1.0))
            original.model.engraved shouldBe false
            engraved.model.engraved shouldBe true
            engraved.model.id shouldBe original.model.id
            engraved.yaw shouldBe original.yaw
            engraved.epitaph shouldBe original.epitaph
            GraveAppearances.select(id, settings.copy(engravedChance = 1.0), engravingsAvailable = false) shouldBe original
            GraveAppearances.select(id, settings.copy(engravedChance = -1.0)) shouldBe original
            GraveAppearances.select(id, settings.copy(engravedChance = 2.0)) shouldBe engraved
            GraveAppearances.select(id, settings.copy(engravedChance = Double.NaN)) shouldBe GraveAppearances.select(id, settings)
        }
    }

    test("every distinct epitaph has an equal independent slot for original and engraved tombs") {
        val phrases = (1..20).map { "Эпитафия $it" }
        val settings = GraveAppearanceSettings(epitaphs = phrases, engravedChance = 0.0)
        val ids = (0L..4095L).map { UUID(19, it) }
        val chosen = ids.map { GraveAppearances.select(it, settings).epitaph }
        val counts = chosen.groupingBy { it }.eachCount()
        counts.keys shouldBe phrases.toSet()
        (counts.values.max() < counts.values.min() * 2) shouldBe true
        ids.zip(chosen).forEach { (id, epitaph) ->
            GraveAppearances.select(id, settings.copy(engravedChance = 1.0)).epitaph shouldBe epitaph
            GraveAppearances.select(id, settings.copy(weights = mapOf("timber" to 1.0))).epitaph shouldBe epitaph
            GraveAppearances.select(id, settings.copy(epitaphs = phrases + " " + phrases.first())).epitaph shouldBe epitaph
        }
    }

    test("quoted last words and readable Java or Bedrock signatures fit without changing any name characters") {
        GraveModels.all.forEach { model ->
            listOf("Alexey", "WwWwWwWwWwWwWwWw", ".WwWwWwWwWwWwWwWw", null).forEach { name ->
                val inscription = model.inscription
                val placements = inscription.placements("Да не ссы, я сто раз так делал.", name)
                val author = "— ${name ?: "Игрок"}"
                val text = placements.joinToString("\n") { it.text }
                text.substringBefore("»\n") shouldBe "«Да не ссы, я сто раз так делал."
                val renderedAuthor = text.substringAfter("»\n")
                renderedAuthor.replace("\n", "") shouldBe author
                if (name == "Alexey") renderedAuthor shouldBe author
                if (placements.size > 1) (placements.last().scale >= .22f) shouldBe true
                // Long nicknames may use a separate smaller row; the quote remains readable.
                (placements.first().scale >= .20f) shouldBe true
                placements.forEach { placement ->
                    (placement.lineWidth * placement.scale <= inscription.lineWidth * inscription.scale + .000001f) shouldBe true
                    val layout = DialogTextLayout.layout(Component.text(placement.text), TextAlignment.LEFT, placement.lineWidth + 8)
                        as TextLayoutResult.Aligned
                    (placement.bottomY >= inscription.centerY - inscription.maxHeight / 2 - .000001) shouldBe true
                    (placement.bottomY + .25 * layout.lineCount * placement.scale <= inscription.centerY + inscription.maxHeight / 2 + .000001) shouldBe true
                }
            }
        }
    }

    test("original silhouettes keep their compact footprint and readable independent inscriptions") {
        GraveModels.originals.forEach { model ->
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
            listOf("Похуй...", "Собаке собачья смерть", "Да не ссы, ничего не будет").forEach { epitaph ->
                val placement = model.inscription.placements(epitaph).single()
                (placement.scale >= .20f) shouldBe true
                (placement.bottomY >= model.inscription.centerY - model.inscription.maxHeight / 2 - .000001) shouldBe true
            }
        }
    }

    test("stone pedestals support one by two maps with bounded overhang and readable separate last words") {
        GraveModels.engraved.forEach { model ->
            (model.parts.size <= 12) shouldBe true
            model.parts.any { part ->
                part.z == -.5 && part.width >= .95f && part.height >= 1.95f
            } shouldBe true
            model.parts.any { part ->
                part.y == .0 && part.width >= 1.40f && part.height >= .18f && part.depth >= 1.10f
            } shouldBe true
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
                    (abs(point.x) <= .740001f && point.z >= -.700001f && point.z <= .500001f &&
                        point.y >= -.000001f && point.y <= 2.000001f) shouldBe true
                    for (yaw in listOf(0f, 90f, 180f, 270f)) {
                        val turned = Vector3f(point).rotateY(Math.toRadians(yaw.toDouble()).toFloat())
                        (abs(turned.x) <= .740001f && abs(turned.z) <= .740001f) shouldBe true
                    }
                }
                if (part.z < GraveModels.IMAGE_FRONT_Z) {
                    // Trim may cover the map's empty outer border, never the engraving or last words.
                    val crossesContent = part.x < .42 && part.x + part.width > -.42 &&
                        part.y < 1.88 && part.y + part.height > .24
                    crossesContent shouldBe false
                }
            }
            (model.inscription.z < GraveModels.IMAGE_FRONT_Z) shouldBe true
            (model.inscription.centerY + model.inscription.maxHeight / 2 < .5) shouldBe true
            listOf("Похуй...", "Собаке собачья смерть", "Да не ссы, ничего не будет").forEach { epitaph ->
                val placement = model.inscription.placements(epitaph).single()
                (placement.scale >= .20f) shouldBe true
                (placement.bottomY >= model.inscription.centerY - model.inscription.maxHeight / 2 - .000001) shouldBe true
            }
        }
    }
})
