package ru.arc.treasurechests

import com.jeff_media.customblockdata.CustomBlockData
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.world.ChunkLoadEvent
import ru.arc.ARC
import ru.arc.common.chests.ItemsAdderFurnitureRemover
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging.info

/**
 * Подчищает IA-мебель охоты: JSON-реестр (основной) + legacy-маркеры в CustomBlockData.
 */
object HuntFurnitureJanitor : Listener {
    private const val STARTUP_DELAY_TICKS = 100L
    private const val ANCHORS_PER_TICK = 16
    private const val CHUNKS_PER_TICK = 4

    private lateinit var scheduler: TaskScheduler
    private var scanTask: ScheduledTask? = null

    @JvmStatic
    fun init(taskScheduler: TaskScheduler) {
        scheduler = taskScheduler
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        scheduler.runLater(STARTUP_DELAY_TICKS, Runnable { startStartupCleanup() })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!HuntFurnitureRegistry.hasEntry(block)) return
        val anchor = HuntFurnitureRegistry.take(block) ?: return
        ItemsAdderFurnitureRemover.removeAnchor(anchor)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!::scheduler.isInitialized) return
        if (TreasureHuntManager.hasActiveHunts()) {
            cleanupChunkIfOrphan(event.chunk)
        } else {
            cleanupChunk(event.chunk)
        }
    }

    private fun startStartupCleanup() {
        cleanupRegistryAnchors()
        startLegacyChunkScan()
    }

    private fun cleanupRegistryAnchors() {
        val anchors = HuntFurnitureRegistry.drainAll()
        if (anchors.isEmpty()) return

        var index = 0
        var removedEntities = 0
        scanTask?.cancel()
        scanTask =
            scheduler.runTimer(
                1,
                1,
                Runnable {
                    repeat(ANCHORS_PER_TICK) {
                        if (index >= anchors.size) {
                            scanTask?.cancel()
                            info(
                                "[hunt-furniture] registry cleanup finished: {} anchors, {} entities",
                                anchors.size,
                                removedEntities,
                            )
                            return@Runnable
                        }
                        removedEntities += cleanupAnchor(anchors[index++])
                    }
                },
            )
        info("[hunt-furniture] registry cleanup scheduled for {} anchors", anchors.size)
    }

    private fun cleanupAnchor(anchor: HuntFurnitureAnchor): Int = ItemsAdderFurnitureRemover.removeAnchor(anchor)

    private fun startLegacyChunkScan() {
        val chunks = Bukkit.getWorlds().flatMap { world -> world.loadedChunks.toList() }
        if (chunks.isEmpty()) return

        var index = 0
        var cleaned = 0
        scheduler.runTimer(
            1,
            1,
            Runnable {
                repeat(CHUNKS_PER_TICK) {
                    if (index >= chunks.size) return@Runnable
                    cleaned += cleanupChunk(chunks[index++])
                }
            },
        )
        info("[hunt-furniture] legacy chunk scan for {} loaded chunks", chunks.size)
    }

    fun cleanupChunk(chunk: Chunk): Int {
        var cleaned = 0
        for (block in CustomBlockData.getBlocksWithCustomData(ARC.instance, chunk)) {
            cleaned += ItemsAdderFurnitureRemover.cleanupOrphan(block)
        }
        return cleaned
    }

    private fun cleanupChunkIfOrphan(chunk: Chunk) {
        for (block in CustomBlockData.getBlocksWithCustomData(ARC.instance, chunk)) {
            if (TreasureHuntManager.getByBlock(block) != null) continue
            ItemsAdderFurnitureRemover.cleanupOrphan(block)
        }
    }
}
