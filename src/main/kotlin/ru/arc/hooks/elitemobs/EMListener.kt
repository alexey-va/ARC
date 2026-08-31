package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import com.magmaguy.elitemobs.api.EliteExplosionEvent
import com.magmaguy.elitemobs.api.WorldInstanceEvent
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.items.customloottable.CustomLootEntry
import com.magmaguy.elitemobs.items.customloottable.EliteCustomLootEntry
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.contracts.ContractsManager
import ru.arc.contracts.SeasonDungeonInstanceDecision
import ru.arc.util.Logging.error
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

class EMListener internal constructor(
    private val config: Config = ConfigManager.of(ARC.instance.dataPath, "modules/elitemobs.yml"),
    private val treasureChestAt: (Location) -> TreasureChest? = TreasureChest::getTreasureChest,
    private val customItemPermission: (String) -> String? = { filename ->
        CustomItem.getCustomItem(filename)?.permission
    },
) : Listener {
    private val dungeonRunIds = WeakHashMap<DungeonInstance, String>()
    private val nextDungeonRunId = AtomicLong()

    @EventHandler
    fun emExplosion(event: EliteExplosionEvent) {
        val noExpWorlds = config.stringList("no-explosion-worlds")
        val name = event.explosionSourceLocation.world.name
        if (noExpWorlds.contains(name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun guardQuestTreasureChest(event: PlayerInteractEvent) {
        val location = event.clickedBlock?.location ?: return
        val isLocked =
            try {
                val chest = treasureChestAt(location) ?: return
                isQuestTreasureChestLocked(
                    entries = chest.customTreasureChestConfigFields.customLootTable.entries,
                    hasPermission = event.player::hasPermission,
                    customItemPermission = customItemPermission,
                )
            } catch (failure: RuntimeException) {
                error("Unable to inspect EliteMobs quest treasure chest", failure)
                return
            }
        if (!isLocked) return

        event.isCancelled = true
        if (event.hand != EquipmentSlot.OFF_HAND) {
            event.player.sendMessage(
                config.component(
                    "chests.quest-locked-message",
                    "<white></white> <dark_gray>» <#c42323>Сундук доступен только по заданию.",
                ),
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun authorizeWorldInstance(event: WorldInstanceEvent) {
        when (
            ContractsManager.authorizeSeasonDungeonInstance(
                event.blueprintWorldName,
                event.instancedWorldName,
            )
        ) {
            SeasonDungeonInstanceDecision.NOT_PROTECTED,
            SeasonDungeonInstanceDecision.AUTHORIZED,
            -> Unit
            SeasonDungeonInstanceDecision.DENIED -> event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun recoverCancelledWorldInstance(event: WorldInstanceEvent) {
        if (event.isCancelled) {
            ContractsManager.cancelAuthorizedSeasonDungeonInstance(event.instancedWorldName)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun guardSeasonInstanceTeleport(event: PlayerTeleportEvent) {
        val destinationWorld = event.to.world.name
        if (ContractsManager.seasonDungeonPlayerAuthorized(destinationWorld, event.player.uniqueId) == false) {
            event.isCancelled = true
        }
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
            instanceWorld = instance.instancedWorldName,
        )
    }

    @EventHandler
    fun resumePendingSeasonDungeonRewards(event: PlayerJoinEvent) {
        ContractsManager.resumeSeasonDungeonRewards(event.player.uniqueId)
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
                instanceWorld = instance.instancedWorldName,
            )
        } finally {
            dungeonRunIds.remove(instance)
        }
    }

    private fun runId(instance: DungeonInstance): String =
        ContractsManager.seasonDungeonRunAuthorization(instance.instancedWorldName)?.runId
            ?: dungeonRunIds.getOrPut(instance) { "elite-runtime-${nextDungeonRunId.incrementAndGet()}" }

    private fun participantIds(instance: DungeonInstance): Set<String> =
        instance.participants.mapTo(linkedSetOf()) { it.uniqueId.toString() }
}

internal fun isQuestTreasureChestLocked(
    entries: List<CustomLootEntry>,
    hasPermission: (String) -> Boolean,
    customItemPermission: (String) -> String?,
): Boolean {
    if (entries.isEmpty()) return false
    return entries.all { entry ->
        if (entry !is EliteCustomLootEntry) return false
        val permission = customItemPermission(entry.filename)?.trim().orEmpty()
        if (!permission.startsWith(ELITE_QUEST_PERMISSION_PREFIX, ignoreCase = true)) return false
        !hasPermission(permission)
    }
}

private const val ELITE_QUEST_PERMISSION_PREFIX = "elitequest."
