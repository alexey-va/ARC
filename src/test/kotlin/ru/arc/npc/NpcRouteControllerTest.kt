package ru.arc.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class NpcRouteControllerTest : FreeSpec({
    "hard no-go areas are never crossed" {
        val forbidden = NpcRouteBounds(1, 2, 1, 1)
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 4, 0, 2),
            forbidden = listOf(forbidden),
        )

        val path = findNpcGridPath(NpcRouteCell(0, 1), listOf(NpcRouteCell(3, 1)), profile) { true }

        path?.any { it in forbidden } shouldBe false
        path?.zipWithNext()?.all { (first, second) ->
            kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.z - second.z) == 1
        } shouldBe true
    }

    "preferred corridors win over a shorter ordinary route" {
        val preferred = NpcRouteBounds(0, 4, 0, 0)
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 4, 0, 2),
            preferred = listOf(preferred),
        )

        val path = findNpcGridPath(NpcRouteCell(0, 1), listOf(NpcRouteCell(4, 1)), profile) { true }

        path?.contains(NpcRouteCell(2, 0)) shouldBe true
    }

    "bounded search cannot leave the configured venue" {
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 2, 0, 0),
            forbidden = listOf(NpcRouteBounds(1, 1, 0, 0)),
        )

        findNpcGridPath(NpcRouteCell(0, 0), listOf(NpcRouteCell(2, 0)), profile) { true } shouldBe null
    }
})
