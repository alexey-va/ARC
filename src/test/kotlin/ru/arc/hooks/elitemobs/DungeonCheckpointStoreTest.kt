package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import org.bukkit.Location

class DungeonCheckpointStoreTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "round trips through a fresh store object and rejects another world or run" {
        val player = paper.addPlayer("checkpoint")
        val dungeon = paper.addSimpleWorld("dungeon")
        val other = paper.addSimpleWorld("other")
        val saved = Location(dungeon, 12.5, 70.0, -4.5, 90f, 5f)

        DungeonCheckpointStore().remember(player.persistentDataContainer, saved, "run-a", 100L, 1_000L)
        val store = DungeonCheckpointStore()

        store.destination(player.persistentDataContainer, dungeon, "run-a", 200L, 1_000L) shouldBe saved
        store.destination(player.persistentDataContainer, other, "run-a", 200L, 1_000L) shouldBe null
        store.destination(player.persistentDataContainer, dungeon, "run-b", 200L, 1_000L) shouldBe null
    }

    "rejects future, expired, corrupt, and oversized coordinates" {
        val player = paper.addPlayer("invalid")
        val futureWorld = paper.addSimpleWorld("future-world")
        val expiredWorld = paper.addSimpleWorld("expired-world")
        val nanWorld = paper.addSimpleWorld("nan-world")
        val largeWorld = paper.addSimpleWorld("large-world")
        val store = DungeonCheckpointStore()
        val data = player.persistentDataContainer

        store.remember(data, Location(futureWorld, 1.0, 2.0, 3.0), "future", 101L, 1_000L)
        store.remember(data, Location(expiredWorld, 4.0, 5.0, 6.0), "expired", 0L, 10L)
        store.remember(data, Location(nanWorld, Double.NaN, 5.0, 6.0), "nan", 100L, 1_000L)
        store.remember(data, Location(largeWorld, 30_000_001.0, 5.0, 6.0), "large", 100L, 1_000L)

        store.destination(data, futureWorld, "future", 100L, 1_000L) shouldBe null
        store.destination(data, expiredWorld, "expired", 100L, 10L) shouldBe null
        store.destination(data, nanWorld, "nan", 100L, 1_000L) shouldBe null
        store.destination(data, largeWorld, "large", 100L, 1_000L) shouldBe null
    }

    "keeps only the newest sixteen world checkpoints" {
        val player = paper.addPlayer("bounded")
        val store = DungeonCheckpointStore()
        val worlds = (0..16).map { paper.addSimpleWorld("dungeon-$it") }

        worlds.forEachIndexed { index, world ->
            store.remember(player.persistentDataContainer, Location(world, index.toDouble(), 70.0, 0.0), "run", index.toLong(), 1_000L)
        }

        store.destination(player.persistentDataContainer, worlds.first(), "run", 16L, 1_000L) shouldBe null
        store.destination(player.persistentDataContainer, worlds.last(), "run", 16L, 1_000L)?.x shouldBe 16.0
    }
})
