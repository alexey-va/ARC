package ru.arc.citizens

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class NpcChunkTicketPlannerTest : FreeSpec({
    "deduplicates candidates and filters configured worlds case-insensitively" {
        val plan =
            NpcChunkTicketPlanner.plan(
                candidates =
                    listOf(
                        NpcChunkKey("sp11", 12, 28),
                        NpcChunkKey("sp11", 12, 28),
                        NpcChunkKey("SP11", 26, 21),
                        NpcChunkKey("spawn", 24, 18),
                    ),
                allowedWorlds = setOf("Sp11"),
                maxPinnedChunks = 8,
            )

        plan.selected.shouldContainExactly(
            NpcChunkKey("sp11", 12, 28),
            NpcChunkKey("SP11", 26, 21),
        )
        plan.rejectedCount shouldBe 0
    }

    "applies a deterministic global safety cap" {
        val plan =
            NpcChunkTicketPlanner.plan(
                candidates =
                    listOf(
                        NpcChunkKey("sp11", 4, 9),
                        NpcChunkKey("sp11", -2, 3),
                        NpcChunkKey("sp11", 1, 1),
                    ),
                allowedWorlds = setOf("sp11"),
                maxPinnedChunks = 2,
            )

        plan.selected.shouldContainExactly(
            NpcChunkKey("sp11", -2, 3),
            NpcChunkKey("sp11", 1, 1),
        )
        plan.rejectedCount shouldBe 1
    }

    "rejects a non-positive cap" {
        shouldThrow<IllegalArgumentException> {
            NpcChunkTicketPlanner.plan(emptyList(), setOf("sp11"), 0)
        }
    }
})
