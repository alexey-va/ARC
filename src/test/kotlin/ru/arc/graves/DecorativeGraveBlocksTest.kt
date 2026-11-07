package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class DecorativeGraveBlocksTest : FunSpec({
    test("an unloaded grave removed from the catalog restores only journaled barriers when its chunk returns") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DecorativeGraveJournal")
            val world = paper.addSimpleWorld("decorative")
            val chunk = world.getChunkAt(-2, 3)
            chunk.getBlock(15, 79, 1).type = Material.STONE
            val base = chunk.getBlock(15, 80, 1).apply { type = Material.CAVE_AIR }
            val ordinary = chunk.getBlock(14, 80, 1).apply { type = Material.BARRIER }
            val record = decorativeRecord().copy(
                worldId = world.uid, world = world.name, x = base.x, y = base.y, z = base.z,
            )
            DecorativeGraveBlocks(plugin) { 1 }.use { blocks ->
                blocks.start(listOf(record))
                base.type shouldBe Material.BARRIER
                blocks.record(base) shouldBe record
                blocks.record(ordinary) shouldBe null
                val data = chunk.persistentDataContainer
                val key = data.keys.single()
                val journal = requireNotNull(data.get(key, PersistentDataType.INTEGER_ARRAY)).clone()

                // MockBukkit changes its loaded set without firing either chunk event.
                paper.callEvent(ChunkUnloadEvent(chunk, true))
                base.type shouldBe Material.CAVE_AIR
                world.unloadChunk(chunk.x, chunk.z) shouldBe true
                blocks.update(emptyList(), record)
                world.isChunkLoaded(chunk.x, chunk.z) shouldBe false

                val reloaded = world.getChunkAt(chunk.x, chunk.z)
                val stale = reloaded.getBlock(15, 80, 1).apply { type = Material.BARRIER }
                // Simulate terrain and PDC read back from the previous saved chunk.
                reloaded.persistentDataContainer.set(key, PersistentDataType.INTEGER_ARRAY, journal)
                paper.callEvent(ChunkLoadEvent(reloaded, false))
                stale.type shouldBe Material.CAVE_AIR
                reloaded.persistentDataContainer.has(key) shouldBe false
                reloaded.getBlock(14, 80, 1).type shouldBe Material.BARRIER
            }
        }
    }
})
