package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime

class NativeDungeonVisitsTest : FreeSpec({
    "keeps metadata optional for existing visit consumers" {
        DungeonVisit("run").name shouldBe null
        DungeonVisit("run").stats shouldBe null
        DungeonVisitStats(playerCount = 2, difficulty = "hard", level = 15) shouldBe
            DungeonVisitStats(2, "hard", 15)
    }

    "matches EliteMobs native wormhole trigger and relocates open entry" {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.addSimpleWorld("dungeon")
            val authored = Location(world, -7.5, 145.0, -11.5)
            val volume = NativeWormholeVolume(Location(world, -7.5, 145.0, -11.5), wormholeTriggerRadiusSquared(1.0))
            val destination = normalizeDungeonEntry(authored, world, listOf(volume)) {
                it.x == -5.5 && it.y == 145.0 && it.z == -11.5
            }
            isNativeWormholeTrigger(authored, listOf(volume)) shouldBe true
            destination shouldBe Location(world, -5.5, 145.0, -11.5)
        } finally { paper.close() }
    }

    "normalizes fractional native start to an existing floor candidate" {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.addSimpleWorld("dungeon")
            val authored = Location(world, -9.5, -38.8, 35.5)
            val destination = normalizeDungeonEntry(authored, world, emptyList()) { it.x == -9.5 && it.y == -40.0 && it.z == 35.5 }
            destination shouldBe Location(world, -9.5, -40.0, 35.5)
        } finally { paper.close() }
    }

    "returns no entry when no bounded safe candidate exists" {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.addSimpleWorld("dungeon")
            normalizeDungeonEntry(Location(world, 0.0, 70.0, 0.0), world, emptyList()) { false }.shouldBeNull()
        } finally { paper.close() }
    }

    "uses EliteMobs native wormhole trigger radius" {
        wormholeTriggerRadiusSquared(1.0) shouldBeExactly 2.25
        wormholeTriggerRadiusSquared(2.0) shouldBeExactly 9.0
    }
})
