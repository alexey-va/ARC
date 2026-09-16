package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.ARC
import ru.arc.core.PluginModule

object OriginSpawnModule : PluginModule, Listener {
    override val name = "OriginSpawn"
    override val priority = 22

    private var chunks: OriginSpawnChunkManager? = null
    private var showcase: AuctionShowcaseManager? = null
    private var breakProtection: OriginBreakProtection? = null
    private var breakBypassPermission = OriginSpawnConfig.DEFAULT_BREAK_BYPASS_PERMISSION

    override fun init() {
        breakProtection = OriginBreakProtection(BukkitOriginBreakRuntime())
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        chunks = OriginSpawnChunkManager(ARC.instance.chunkTicketRegistry)
        showcase = AuctionShowcaseManager()
        apply(OriginSpawnConfig.load(ARC.instance.dataPath))
    }

    override fun reload() {
        apply(OriginSpawnConfig.load(ARC.instance.dataPath))
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        showcase?.shutdown()
        showcase = null
        breakProtection?.shutdown()
        breakProtection = null
        chunks?.shutdown()
        chunks = null
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        showcase?.handle(event)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        showcase?.forget(event.player)
        breakProtection?.forget(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        val protected =
            breakProtection?.handle(
                OriginBreakIllusionTarget(
                    playerId = event.player.uniqueId,
                    worldId = event.block.world.uid,
                    worldName = event.block.world.name,
                    x = event.block.x,
                    y = event.block.y,
                    z = event.block.z,
                    originalBlockData = event.block.blockData.asString,
                ),
                bypassProtection = event.player.hasPermission(breakBypassPermission),
            ) == true
        if (!protected) return
        event.isDropItems = false
        event.isCancelled = true
    }

    private fun apply(config: OriginSpawnConfig) {
        chunks?.apply(config)
        showcase?.apply(config)
        breakBypassPermission = config.regenerativeBreakingBypassPermission
        breakProtection?.apply(
            OriginBreakProtectionSettings(
                protected = config.enabled,
                illusionEnabled = config.regenerativeBreakingEnabled,
                worldName = config.worldName,
                restoreDelayTicks = config.regenerativeBreakingRestoreDelayTicks,
                feedback = config.regenerativeBreakingFeedback,
            ),
        )
    }
}
