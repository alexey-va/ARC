package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.instanced.dungeons.DynamicDungeonInstance
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime

class NativeDungeonVisitsTest : FreeSpec({
    "native difficulty ids resolve by configured id and dynamic level excludes the gear sync offset" {
        val fields = mockk<ContentPackagesConfigFields>()
        every { fields.difficulties } returns listOf(mapOf("id" to 7, "name" to "hard"), mapOf("id" to 0, "name" to "normal"))
        val dynamic = mockk<DynamicDungeonInstance>()
        every { dynamic.contentPackagesConfigFields } returns fields
        every { dynamic.difficultyID } returns "0"
        every { dynamic.selectedLevel } returns 5
        every { dynamic.levelSync } returns 10
        every { dynamic.players } returns hashSetOf()
        nativeDungeonStats(dynamic) shouldBe DungeonVisitStats(0, "normal", 5)

        val fixed = mockk<DungeonInstance>()
        every { fixed.contentPackagesConfigFields } returns fields
        every { fixed.difficultyID } returns "7"
        every { fields.contentLevel } returns 50
        every { fixed.levelSync } returns 55
        every { fixed.players } returns hashSetOf()
        nativeDungeonStats(fixed) shouldBe DungeonVisitStats(0, "hard", 50)
        every { fixed.difficultyID } returns "99"
        every { fields.contentLevel } returns 0
        nativeDungeonStats(fixed) shouldBe DungeonVisitStats(0, null, null)
    }

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
