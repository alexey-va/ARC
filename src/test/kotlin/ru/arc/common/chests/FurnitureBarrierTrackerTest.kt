package ru.arc.common.chests

import io.kotest.matchers.collections.shouldContainExactly
import ru.arc.KotestTestBase

class FurnitureBarrierTrackerTest :
    KotestTestBase({

        describe("FurnitureBarrierTracker") {
            it("detectSpawned returns barrier blocks that appeared after spawn") {
                val before = setOf(BlockPos(1, 64, 2))
                val after = setOf(BlockPos(1, 64, 2), BlockPos(2, 65, 2))

                FurnitureBarrierTracker.detectSpawned(before, after) shouldContainExactly setOf(BlockPos(2, 65, 2))
            }
        }
    })
