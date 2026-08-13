package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import com.magmaguy.elitemobs.api.EliteExplosionEvent
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.contracts.ContractsManager
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

class EMListener : Listener {

    private val config = ConfigManager.of(ARC.instance.dataPath, "elitemobs.yml")
    private val dungeonRunIds = WeakHashMap<DungeonInstance, String>()
    private val nextDungeonRunId = AtomicLong()

    @EventHandler
    fun emExplosion(event: EliteExplosionEvent) {
        val noExpWorlds = config.stringList("no-explosion-worlds")
        val name = event.explosionSourceLocation.world.name
        if (noExpWorlds.contains(name)) event.isCancelled = true
    }

    @EventHandler
    fun dungeonStarted(event: DungeonStartEvent) {
        val instance = event.dungeonInstance
        val world = instance.contentPackagesConfigFields.worldName ?: return
        val runId = runId(instance)
        ContractsManager.observeDungeonStarted(
            runId = runId,
            world = world,
            participantIds = participantIds(instance),
        )
    }

    @EventHandler
    fun dungeonCompleted(event: DungeonCompleteEvent) {
        val instance = event.dungeonInstance
        val runId = runId(instance)
        try {
            ContractsManager.observeDungeonCompleted(
                runId = runId,
                world = instance.contentPackagesConfigFields.worldName ?: return,
                participantIds = participantIds(instance),
            )
        } finally {
            dungeonRunIds.remove(instance)
        }
    }

    private fun runId(instance: DungeonInstance): String =
        dungeonRunIds.getOrPut(instance) { "elite-runtime-${nextDungeonRunId.incrementAndGet()}" }

    private fun participantIds(instance: DungeonInstance): Set<String> =
        instance.participants.mapTo(linkedSetOf()) { it.uniqueId.toString() }
}
