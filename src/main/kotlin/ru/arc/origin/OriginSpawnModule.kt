package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.ARC
import ru.arc.core.PluginModule

object OriginSpawnModule : PluginModule, Listener {
    override val name = "OriginSpawn"
    override val priority = 22

    private var chunks: OriginSpawnChunkManager? = null
    private var showcase: AuctionShowcaseManager? = null

    override fun init() {
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
    }

    private fun apply(config: OriginSpawnConfig) {
        chunks?.apply(config)
        showcase?.apply(config)
    }
}
