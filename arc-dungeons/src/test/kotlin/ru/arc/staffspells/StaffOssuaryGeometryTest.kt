package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class StaffOssuaryGeometryTest : FreeSpec({
    "primary ossuary groups keep a finite five-spike cluster with outward angled sides" {
        val expectedIds = buildSet {
            add("bone:spike:shaft")
            add("bone:spike:tip")
            repeat(4) { spike ->
                add("bone:spike:side:$spike:shaft")
                add("bone:spike:side:$spike:tip")
            }
        }

        for (charge in listOf(0.0, 1.0)) {
            for (age in listOf(0, 2, OSSUARY_PRIMARY_RISE_TICKS, 8)) {
                val parts = staffArsenalParts(StaffSpell.OSSUARY, age, 22, 24.0, 1.0,
                    false, false, StaffCastInput.PRIMARY, charge)
                val ids = parts.mapNotNull { it.identity }

                parts.size shouldBe 10
                ids.toSet() shouldBe expectedIds
                ids.distinct().size shouldBe ids.size
                parts.count { it.material == Material.BONE_BLOCK } shouldBe 5
                parts.count { it.material == Material.CALCITE } shouldBe 5
                parts.all { part ->
                    listOf(part.center.x, part.center.y, part.center.z,
                        part.scale.x, part.scale.y, part.scale.z,
                        part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w)
                        .all { it.isFinite() } &&
                        part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
                } shouldBe true
            }
        }

        val earlySideTip = staffArsenalParts(StaffSpell.OSSUARY, 2, 22, 24.0, 1.0,
            false, false, StaffCastInput.PRIMARY, 1.0)
            .single { it.identity == "bone:spike:side:0:tip" }
        val full = staffArsenalParts(StaffSpell.OSSUARY, OSSUARY_PRIMARY_RISE_TICKS, 22, 24.0, 1.0,
            false, false, StaffCastInput.PRIMARY, 1.0)
        val centralTip = full.single { it.identity == "bone:spike:tip" }
        val centralTop = Vector3f(0f, centralTip.scale.y / 2f, 0f)
        centralTip.rotation.transform(centralTop).add(centralTip.center)
        val risenSideTip = full.single { it.identity == "bone:spike:side:0:tip" }
        (earlySideTip.center.y < risenSideTip.center.y) shouldBe true

        repeat(4) { spike ->
            val shaft = full.single { it.identity == "bone:spike:side:$spike:shaft" }
            val tip = full.single { it.identity == "bone:spike:side:$spike:tip" }
            val axis = shaft.rotation.transform(Vector3f(0f, 1f, 0f))
            val radialX = cos(spike * PI / 2).toFloat()
            val radialZ = sin(spike * PI / 2).toFloat()
            (axis.x * radialX + axis.z * radialZ > .25f) shouldBe true
            (axis.y in 0.94f..0.97f) shouldBe true

            val foot = shaft.rotation.transform(Vector3f(0f, -shaft.scale.y / 2f, 0f)).add(shaft.center)
            (foot.y in -.2f..0f) shouldBe true
            val tipTop = tip.rotation.transform(Vector3f(0f, tip.scale.y / 2f, 0f)).add(tip.center)
            val footRadius = foot.x * radialX + foot.z * radialZ
            val tipRadius = tipTop.x * radialX + tipTop.z * radialZ
            (tipRadius > footRadius + .4f) shouldBe true
            (tipTop.y > foot.y + 1.5f) shouldBe true
            (tipTop.y < centralTop.y - .5f) shouldBe true
        }
    }
})
