package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay

class GraveImpactTest : FunSpec({
    test("two hits move the inscription with its backing and settle without accumulated displacement") {
        val scheduler = TestTaskScheduler()
        val impact = GraveImpact(scheduler)
        val rig = ImpactRig(GraveModels.slate)
        val appearance = GraveAppearance(GraveModels.slate, "Текст", 90f)
        impact.shake(rig.parts, appearance, 1)
        rig.assertOffset(0.0, -.027)
        scheduler.tick(2)
        rig.assertOffset(0.0, .01125)

        // The next accepted hit interrupts the first tail, but keeps the original rest pose.
        impact.shake(rig.parts, appearance, 2)
        rig.assertOffset(0.0, .045)
        scheduler.tick(6)
        rig.assertOffset(0.0, -.0045)
        scheduler.tick(4)
        rig.assertRestored()
        impact.close()
    }

    test("engraved plaques shake within the fixed image frame and cancellation never replays an old tail") {
        GraveModels.engraved.forEach { model ->
            val scheduler = TestTaskScheduler()
            val impact = GraveImpact(scheduler)
            val rig = ImpactRig(model)
            impact.shake(rig.parts, GraveAppearance(model, "Текст", 0f), 2)
            // Both text rows and their backing move together; the top frame and map slab stay put.
            val plaque = model.parts.lastIndex
            (plaque..plaque + 2).forEach { index ->
                rig.states[index].at.x shouldBe (rig.states[index].rest.x - .02 plusOrMinus 1e-9)
            }
            model.parts.indices.filter { model.parts[it].height > .55 || model.parts[it].y > .55 }.forEach { index ->
                rig.states[index].at shouldBe rig.states[index].rest
            }
            // The widest plaque stays inside even the cairn's narrow 0.87 m frame opening.
            val backing = model.inscription.backing
            (backing.x + backing.width + .02 < .435) shouldBe true
            (backing.x - .02 > -.435) shouldBe true
            impact.cancel(rig.parts)
            rig.assertRestored()
            scheduler.tick(20)
            rig.assertRestored()
            impact.close()
        }
    }

    test("shutdown restores surviving parts and sound material follows the visible structure") {
        val scheduler = TestTaskScheduler()
        val impact = GraveImpact(scheduler)
        val rig = ImpactRig(GraveModels.timber)
        impact.shake(rig.parts, GraveAppearance(GraveModels.timber, "Текст", 0f), 2)
        rig.states.first().valid = false
        impact.close()
        impact.close()
        rig.assertRestored()
        scheduler.tick(20)
        rig.assertRestored()
        impact.shake(rig.parts, GraveAppearance(GraveModels.timber, "Текст", 0f), 1)
        rig.assertRestored()
        GraveImpact.material(GraveModels.timber) shouldBe Material.STRIPPED_SPRUCE_LOG
        GraveImpact.material(GraveModels.engravedTimber) shouldBe Material.SMOOTH_SANDSTONE
        GraveImpact.material(GraveModels.slate) shouldBe Material.POLISHED_DEEPSLATE
    }
})

private class ImpactRig(model: GraveModel) {
    class State(val rest: Location) {
        var at = rest.clone()
        var duration = 0
        var valid = true
    }
    val states = (model.parts.map { part -> Location(null, part.x, part.y, part.z) } +
        listOf(Location(null, 0.0, .35, -.574, 180f, 0f), Location(null, 0.0, .28, -.574, 180f, 0f)))
        .map(::State)
    val parts: List<PacketDisplay> = states.mapIndexed { index, state ->
        mockk<PacketBlockDisplay>(name = "impact-part-$index", relaxed = true).also { part ->
            every { part.isValid } answers { state.valid }
            every { part.location } answers { state.at.clone() }
            every { part.teleport(any()) } answers { state.at = firstArg<Location>().clone() }
            every { part.teleportDuration } answers { state.duration }
            every { part.teleportDuration = any() } answers { state.duration = firstArg() }
        }
    }

    fun assertOffset(x: Double, z: Double) {
        states.filter { it.valid }.forEach { state ->
            state.at.x shouldBe (state.rest.x + x plusOrMinus 1e-9)
            state.at.y shouldBe state.rest.y
            state.at.z shouldBe (state.rest.z + z plusOrMinus 1e-9)
            state.at.yaw shouldBe state.rest.yaw
            state.duration shouldBe 2
        }
    }

    fun assertRestored() {
        states.filter { it.valid }.forEach { state ->
            state.at shouldBe state.rest
            state.duration shouldBe 0
        }
    }
}
