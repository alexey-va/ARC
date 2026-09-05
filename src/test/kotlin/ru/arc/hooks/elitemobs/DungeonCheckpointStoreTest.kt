package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

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

    "round trips named manual and auto points through a fresh store" {
        val player = paper.addPlayer("saves")
        val world = paper.addSimpleWorld("saves-world")
        val store = DungeonCheckpointStore()
        val manual = store.save(player.persistentDataContainer, Location(world, 1.0, 2.0, 3.0, 10f, 20f), "run-a", "  Boss room  ", DungeonSaveKind.MANUAL, 100L, 1_000L)
        val auto = store.save(player.persistentDataContainer, Location(world, 4.0, 5.0, 6.0), "run-a", "auto", DungeonSaveKind.AUTO, 101L, 1_000L)
        val fresh = DungeonCheckpointStore()
        manual shouldNotBe null
        auto shouldNotBe null
        fresh.list(player.persistentDataContainer, world.uid, "run-a", 200L, 1_000L).map { it.name } shouldBe listOf("Boss room", "auto")
        fresh.list(player.persistentDataContainer, world.uid, "run-b", 200L, 1_000L) shouldBe emptyList()
    }

    "manual points have a five point limit and same name overwrites its id" {
        val player = paper.addPlayer("manual-limit")
        val world = paper.addSimpleWorld("manual-world")
        val store = DungeonCheckpointStore()
        val first = store.save(player.persistentDataContainer, Location(world, 1.0, 2.0, 3.0), "run", "Point", DungeonSaveKind.MANUAL, 1L, 10_000L)!!
        (2..5).forEach { index -> store.save(player.persistentDataContainer, Location(world, index.toDouble(), 2.0, 3.0), "run", "p$index", DungeonSaveKind.MANUAL, index.toLong(), 10_000L) shouldNotBe null }
        store.save(player.persistentDataContainer, Location(world, 9.0, 2.0, 3.0), "run", "six", DungeonSaveKind.MANUAL, 9L, 10_000L) shouldBe null
        val replaced = store.save(player.persistentDataContainer, Location(world, 20.0, 2.0, 3.0), "run", " point ", DungeonSaveKind.MANUAL, 20L, 10_000L)!!
        replaced.id shouldBe first.id
        store.list(player.persistentDataContainer, world.uid, "run", 21L, 10_000L).size shouldBe 5
        store.remove(player.persistentDataContainer, world.uid, "run", replaced.id) shouldBe true
        store.remove(player.persistentDataContainer, world.uid, "run", replaced.id) shouldBe false
    }

    "auto points form a ring without evicting a dedicated manual point" {
        val player = paper.addPlayer("auto-limit")
        val world = paper.addSimpleWorld("auto-world")
        val store = DungeonCheckpointStore()
        store.save(player.persistentDataContainer, Location(world, 0.0, 1.0, 0.0), "run", "manual", DungeonSaveKind.MANUAL, 1L, 10_000L)
        (1..4).forEach { index -> store.save(player.persistentDataContainer, Location(world, index.toDouble(), 1.0, 0.0), "run", "auto-$index", DungeonSaveKind.AUTO, (index + 1).toLong(), 10_000L) }
        val points = store.list(player.persistentDataContainer, world.uid, "run", 100L, 10_000L)
        points.any { it.name == "manual" } shouldBe true
        points.count { it.kind == DungeonSaveKind.AUTO } shouldBe 3
    }

    "auto labels never overwrite manual points and repeated labels keep distinct ids" {
        val player = paper.addPlayer("auto-collision")
        val world = paper.addSimpleWorld("auto-collision-world")
        val store = DungeonCheckpointStore()
        val manual = store.save(player.persistentDataContainer, Location(world, 0.0, 1.0, 0.0), "run", "checkpoint", DungeonSaveKind.MANUAL, 1L, 10_000L)!!
        val autos = (1..4).map { index -> store.save(player.persistentDataContainer, Location(world, index.toDouble(), 1.0, 0.0), "run", "checkpoint", DungeonSaveKind.AUTO, (index + 1).toLong(), 10_000L)!! }
        val points = store.list(player.persistentDataContainer, world.uid, "run", 100L, 10_000L)
        points.first { it.kind == DungeonSaveKind.MANUAL }.id shouldBe manual.id
        points.filter { it.kind == DungeonSaveKind.AUTO }.size shouldBe 3
        autos.map { it.id }.distinct().size shouldBe 4
    }

    "corrupt entries and future or expired points do not hide valid points" {
        val player = paper.addPlayer("corrupt")
        val world = paper.addSimpleWorld("corrupt-world")
        val store = DungeonCheckpointStore()
        store.save(player.persistentDataContainer, Location(world, 1.0, 2.0, 3.0), "run", "valid", DungeonSaveKind.MANUAL, 100L, 1_000L)
        store.save(player.persistentDataContainer, Location(world, 4.0, 5.0, 6.0), "run", "future", DungeonSaveKind.MANUAL, 200L, 1_000L)
        val root = NamespacedKey("arc", "dungeon_save_points")
        @Suppress("DEPRECATION") val entries = player.persistentDataContainer.get(root, PersistentDataType.TAG_CONTAINER_ARRAY)!!.toMutableList()
        val old = player.persistentDataContainer.adapterContext.newPersistentDataContainer()
        entries.first().copyTo(old, true)
        old.set(NamespacedKey("arc", "saved_at"), PersistentDataType.LONG, 0L)
        old.set(NamespacedKey("arc", "name"), PersistentDataType.STRING, "old")
        entries += old
        entries += player.persistentDataContainer.adapterContext.newPersistentDataContainer()
        @Suppress("DEPRECATION") player.persistentDataContainer.set(root, PersistentDataType.TAG_CONTAINER_ARRAY, entries.toTypedArray())
        store.list(player.persistentDataContainer, world.uid, "run", 150L, 100L).map { it.name } shouldBe listOf("valid")
        store.save(player.persistentDataContainer, Location(world, 10.0, 2.0, 3.0), "run", "negative", DungeonSaveKind.MANUAL, -1L, 1_000L) shouldBe null
        store.list(player.persistentDataContainer, world.uid, "run", 100L, 1_000L).any { it.name == "negative" } shouldBe false
    }

    "forget clears legacy exit and all named points in one world" {
        val player = paper.addPlayer("forget-all")
        val world = paper.addSimpleWorld("forget-world")
        val store = DungeonCheckpointStore()
        store.remember(player.persistentDataContainer, Location(world, 1.0, 2.0, 3.0), "run", 1L, 1_000L)
        store.save(player.persistentDataContainer, Location(world, 4.0, 5.0, 6.0), "run", "manual", DungeonSaveKind.MANUAL, 1L, 1_000L)
        store.forget(player.persistentDataContainer, world.uid)
        store.destination(player.persistentDataContainer, world, "run", 2L, 1_000L) shouldBe null
        store.list(player.persistentDataContainer, world.uid, "run", 2L, 1_000L) shouldBe emptyList()
    }
    "one legacy entry with wrong PDC type does not hide a valid exit" {
        val player = paper.addPlayer("legacy-types")
        val world = paper.addSimpleWorld("legacy-types-world")
        val store = DungeonCheckpointStore()
        val location = Location(world, 1.0, 70.0, 3.0)
        store.remember(player.persistentDataContainer, location, "run", 100L, 1_000L)
        val root = NamespacedKey("arc", "dungeon_checkpoints")
        @Suppress("DEPRECATION") val entries = player.persistentDataContainer.get(root, PersistentDataType.TAG_CONTAINER_ARRAY)!!
        val malformed = player.persistentDataContainer.adapterContext.newPersistentDataContainer()
        malformed.set(NamespacedKey("arc", "world"), PersistentDataType.INTEGER, 42)
        @Suppress("DEPRECATION") player.persistentDataContainer.set(root, PersistentDataType.TAG_CONTAINER_ARRAY, arrayOf(malformed, *entries))
        store.destination(player.persistentDataContainer, world, "run", 150L, 1_000L) shouldBe location
    }

})
