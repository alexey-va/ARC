package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.TileState
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import java.util.UUID

internal data class OriginBreakProtectionSettings(
    val protected: Boolean,
    val illusionEnabled: Boolean,
    val worldName: String,
    val restoreDelayTicks: Long,
)

internal data class OriginBreakIllusionTarget(
    val playerId: UUID,
    val worldId: UUID,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalBlockData: String,
) {
    val key: OriginBreakIllusionKey
        get() = OriginBreakIllusionKey(playerId, worldId, x, y, z)
}

internal data class OriginBreakIllusionKey(
    val playerId: UUID,
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)

internal interface OriginBreakIllusionRuntime {
    fun schedule(delayTicks: Long, action: () -> Unit): ScheduledTask

    fun currentBlockData(target: OriginBreakIllusionTarget): String?

    fun showBroken(target: OriginBreakIllusionTarget): Boolean

    fun restore(target: OriginBreakIllusionTarget)
}

internal class BukkitOriginBreakIllusionRuntime : OriginBreakIllusionRuntime {
    override fun schedule(delayTicks: Long, action: () -> Unit): ScheduledTask =
        Tasks.scheduler.runLater(delayTicks, Runnable(action))

    override fun currentBlockData(target: OriginBreakIllusionTarget): String? {
        val world = Bukkit.getWorld(target.worldId) ?: return null
        if (!world.isChunkLoaded(target.x shr 4, target.z shr 4)) return null
        return world.getBlockAt(target.x, target.y, target.z).blockData.asString
    }

    override fun showBroken(target: OriginBreakIllusionTarget): Boolean {
        val player = Bukkit.getPlayer(target.playerId)?.takeIf { it.isOnline } ?: return false
        val world = Bukkit.getWorld(target.worldId) ?: return false
        if (player.world.uid != world.uid || !world.isChunkLoaded(target.x shr 4, target.z shr 4)) return false
        player.sendBlockChange(
            world.getBlockAt(target.x, target.y, target.z).location,
            Material.AIR.createBlockData(),
        )
        return true
    }

    override fun restore(target: OriginBreakIllusionTarget) {
        val player = Bukkit.getPlayer(target.playerId)?.takeIf { it.isOnline } ?: return
        val world = Bukkit.getWorld(target.worldId) ?: return
        if (player.world.uid != world.uid || !world.isChunkLoaded(target.x shr 4, target.z shr 4)) return
        val block = world.getBlockAt(target.x, target.y, target.z)
        player.sendBlockChange(block.location, block.blockData)
        (block.state as? TileState)?.let { player.sendBlockUpdate(block.location, it) }
    }
}

internal class OriginBreakProtection(
    private val runtime: OriginBreakIllusionRuntime,
) {
    private data class PendingIllusion(
        val target: OriginBreakIllusionTarget,
        val generation: Long,
        var showTask: ScheduledTask? = null,
        var restoreTask: ScheduledTask? = null,
        var visible: Boolean = false,
    )

    private var settings = OriginBreakProtectionSettings(false, false, "", 100L)
    private var generation = 0L
    private val pending = mutableMapOf<OriginBreakIllusionKey, PendingIllusion>()

    fun apply(newSettings: OriginBreakProtectionSettings) {
        if (settings == newSettings) return
        restoreAll()
        settings = newSettings
    }

    fun handle(target: OriginBreakIllusionTarget): Boolean {
        val current = settings
        if (!current.protected || target.worldName != current.worldName) return false
        if (!current.illusionEnabled) return true

        retire(target.key, restore = true)
        val pendingIllusion = PendingIllusion(target, ++generation)
        pending[target.key] = pendingIllusion
        pendingIllusion.showTask = runtime.schedule(SHOW_DELAY_TICKS) {
            show(target.key, pendingIllusion.generation)
        }
        return true
    }

    fun forget(playerId: UUID) {
        pending.keys.filter { it.playerId == playerId }.forEach { retire(it, restore = false) }
    }

    fun shutdown() {
        restoreAll()
        settings = settings.copy(protected = false, illusionEnabled = false)
    }

    private fun show(key: OriginBreakIllusionKey, expectedGeneration: Long) {
        val entry = pending[key]?.takeIf { it.generation == expectedGeneration } ?: return
        if (runtime.currentBlockData(entry.target) != entry.target.originalBlockData || !runtime.showBroken(entry.target)) {
            retire(key, restore = false)
            return
        }
        entry.visible = true
        entry.restoreTask = runtime.schedule(settings.restoreDelayTicks) {
            retire(key, restore = true, expectedGeneration = expectedGeneration)
        }
    }

    private fun restoreAll() {
        pending.keys.toList().forEach { retire(it, restore = true) }
    }

    private fun retire(
        key: OriginBreakIllusionKey,
        restore: Boolean,
        expectedGeneration: Long? = null,
    ) {
        val entry = pending[key] ?: return
        if (expectedGeneration != null && entry.generation != expectedGeneration) return
        pending.remove(key)
        entry.showTask?.cancel()
        entry.restoreTask?.cancel()
        if (restore && entry.visible) runtime.restore(entry.target)
    }

    private companion object {
        const val SHOW_DELAY_TICKS = 1L
    }
}
