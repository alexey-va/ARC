package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class StaffLunarGeometryTest : FreeSpec({
    "secondary crescents crumble after contact while the bounded shard field remains" {
        listOf(0.0, .5, 1.0).forEach { charge ->
            fun frame(age: Int) = staffArsenalParts(StaffSpell.LUNAR, age, 40, 12.0, 2.2,
                impact = false, secondary = true, input = StaffCastInput.SECONDARY, charge = charge)
            val contact = frame(16)
            val breaking = frame(19)
            val collapsed = frame(22)
            val damageEnd = frame(24)
            val identities = contact.map { it.identity }
            identities.size shouldBe 48
            identities.distinct().size shouldBe identities.size
            listOf(0, 19, 22, 24, 30, 38).forEach { frame(it).map { part -> part.identity } shouldBe identities }

            fun blades(parts: List<StaffDisplayPart>) = parts.filter {
                it.identity?.startsWith("moon:shard:") == false
            }
            val intactHeight = blades(contact).maxOf { it.center.y }
            (blades(breaking).maxOf { it.center.y } < intactHeight * .65f) shouldBe true
            blades(collapsed).all { it.scale.x <= .002f && it.scale.y <= .002f && it.scale.z <= .002f } shouldBe true
            (blades(collapsed).maxOf { it.center.y } < .5f) shouldBe true

            val contactShards = contact.filter { it.identity?.startsWith("moon:shard:") == true }
            val remainingShards = damageEnd.filter { it.identity?.startsWith("moon:shard:") == true }
            remainingShards.size shouldBe 14
            remainingShards.zip(contactShards).all { (after, before) ->
                after.center.distance(before.center) < .001f && after.scale.distance(before.scale) < .001f
            } shouldBe true
            val terminalParticles = staffArsenalParticles(StaffSpell.LUNAR, 24, 40, 2.2, true, charge)
            terminalParticles.size shouldBe 14
            terminalParticles.all { it.offset.y < 1.0 } shouldBe true
        }
    }
})
