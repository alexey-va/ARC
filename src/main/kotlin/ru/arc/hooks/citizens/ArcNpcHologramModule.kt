package ru.arc.hooks.citizens

import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

/** Global lifecycle and narrow integration API for ARC-owned NPC presentation. */
object ArcNpcHologramModule : PluginModule {
    override val name = "NpcHolograms"
    override val priority = 22

    private var service: ArcNpcHologramService? = null

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            warn("ARC_NPC_HOLOGRAM phase=DISABLED reason=citizens-unavailable")
            return
        }
        val config = ArcNpcHologramConfig.load(ARC.instance.dataPath)
        if (!config.enabled) {
            info("ARC_NPC_HOLOGRAM phase=DISABLED reason=config")
            return
        }
        val active = ArcNpcHologramService(config)
        service = active
        Bukkit.getPluginManager().registerEvents(active, ARC.instance)
        active.start()
        info("ARC_NPC_HOLOGRAM phase=READY follow_ticks={} reconcile_ticks={}", config.followIntervalTicks, config.reconcileIntervalTicks)
    }

    override fun reload() {
        val config = ArcNpcHologramConfig.load(ARC.instance.dataPath)
        val active = service
        if (active == null && config.enabled) init() else active?.reload(config)
    }

    override fun shutdown() {
        service?.let(HandlerList::unregisterAll)
        service?.close()
        service = null
    }

    fun showTemporaryBubble(npcId: Int, lines: List<String>, ttlTicks: Int): Boolean =
        service?.showTemporaryBubble(npcId, lines, ttlTicks) == true

    fun showTemporaryBubble(npcId: Int, lines: List<String>, ttlTicks: Int, owner: String?): Boolean =
        service?.showTemporaryBubble(npcId, lines, ttlTicks, owner) == true

    fun clearTemporaryBubble(npcId: Int, owner: String): Boolean =
        service?.clearTemporaryBubble(npcId, owner) == true

    fun hasTemporaryBubble(npcId: Int): Boolean = service?.hasTemporaryBubble(npcId) == true

    fun patchHologram(npc: NPC, lines: List<String>? = null, lineHeight: Double? = null, viewRange: Int? = null): Boolean =
        service?.patchHologram(npc, lines, lineHeight, viewRange) == true

    fun clearHologram(npc: NPC): Boolean = service?.clearHologram(npc) == true

    fun patchNameplate(npc: NPC, mode: String): Boolean = service?.patchNameplate(npc, mode) == true

    fun desiredNameplate(npc: NPC): String? = service?.desiredNameplate(npc)

    fun desiredSpeechBubbles(npc: NPC): Boolean? = service?.desiredSpeechBubbles(npc)

    fun onTextPatched(npc: NPC, desiredSpeechBubbles: Boolean?) {
        service?.onTextPatched(npc, desiredSpeechBubbles)
    }

    fun summary(npc: NPC): Map<String, Any?>? = service?.summary(npc)
}
