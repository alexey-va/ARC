package ru.arc.hooks.betterstructures

import com.magmaguy.betterstructures.api.BuildPlaceEvent
import com.magmaguy.betterstructures.api.ChestFillEvent
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.bschests.PersonalLootModule
import java.util.concurrent.ConcurrentSkipListSet

class BSListener : Listener {

    private val genCoords = ConcurrentSkipListSet<ChunkCoords>()
    private val r = 7

    private data class ChunkCoords(val x: Int, val z: Int) : Comparable<ChunkCoords> {
        override fun compareTo(other: ChunkCoords): Int =
            if (x == other.x) z.compareTo(other.z) else x.compareTo(other.x)
    }

    @EventHandler
    fun onChestFill(event: ChestFillEvent) {
        PersonalLootModule.processChestGen(event.container.block)
    }

    @EventHandler
    fun onStructureGen(event: BuildPlaceEvent) {
        val structureTypes = event.fitAnything.schematicContainer.generatorConfigFields.structureTypes
        if (!structureTypes.contains(GeneratorConfigFields.StructureType.SURFACE)) return
        if (event.fitAnything.location.y() < 64) return

        val chunk = event.fitAnything.location.clone().chunk
        val coords = ChunkCoords(chunk.x, chunk.z)

        for (x in -r..r) {
            for (z in -r..r) {
                if (genCoords.contains(ChunkCoords(coords.x + x, coords.z + z))) {
                    event.isCancelled = true
                }
            }
        }
        genCoords.add(coords)
    }
}
