package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class PersonalTreasureMapSceneTest : StringSpec({
    "cache decorations stay within a compact 3 by 3 footprint" {
        val cells = listOf(-1 to 0, 1 to 0, -1 to -1, 0 to -1, 1 to -1, 0 to 1)
            .associateWith { PersonalTreasureMapCacheSurface(64, true, true, true) }

        val plan = personalTreasureMapCacheDecorations(baseFootY = 65, surfaces = cells)

        plan.size shouldBe 6
        plan.map { it.offsetX to it.offsetZ }.distinct().size shouldBe plan.size
        plan.all { it.offsetX in -1..1 && it.offsetZ in -1..1 } shouldBe true
        plan.all { it.y == 65 } shouldBe true
        plan.map { it.material }.toSet() shouldBe setOf(
            Material.MOSSY_COBBLESTONE,
            Material.MOSSY_STONE_BRICKS,
            Material.OAK_PLANKS,
            Material.LANTERN,
        )
    }

    "missing, obstructed, unsafe, off-border and distant terrain is omitted" {
        val surfaces = mapOf(
            (-1 to 0) to PersonalTreasureMapCacheSurface(64, true, true, false),
            (1 to 0) to PersonalTreasureMapCacheSurface(64, true, false, true),
            (-1 to -1) to PersonalTreasureMapCacheSurface(64, false, true, true),
            (0 to -1) to PersonalTreasureMapCacheSurface(61, true, true, true),
            (1 to -1) to PersonalTreasureMapCacheSurface(63, true, true, true),
        )

        val plan = personalTreasureMapCacheDecorations(baseFootY = 65, surfaces = surfaces)

        plan.map { it.offsetX to it.offsetZ } shouldContainExactly listOf(1 to -1)
        plan.single().y shouldBe 64
    }
})
