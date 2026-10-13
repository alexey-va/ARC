package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import com.magmaguy.elitemobs.api.EliteExplosionEvent
import com.magmaguy.elitemobs.api.EliteMobDeathEvent
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
import org.bukkit.Bukkit
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.dungeons.api.ArcDungeonContracts
import ru.arc.logging.ArcLogging.error
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

class EMListener internal constructor(
    private val config: Config = ConfigManager.of(ru.arc.dungeons.ArcDungeonsPlugin.instance.legacyDataPath, "modules/elitemobs.yml"),
    private val treasureChestAt: (Location) -> TreasureChest? = TreasureChest::getTreasureChest,
    private val customItemPermission: (String) -> String? = { filename ->
        CustomItem.getCustomItem(filename)?.permission
    },
    private val bossEnchantmentRewards: BossEnchantmentRewards = BossEnchantmentRewards(config),
) : Listener {
    private val dungeonRunIds = WeakHashMap<DungeonInstance, String>()
    private val nextDungeonRunId = AtomicLong()

    @EventHandler
    fun emExplosion(event: EliteExplosionEvent) {
        val noExpWorlds = config.stringList("no-explosion-worlds")
        val name = event.explosionSourceLocation.world.name
        if (noExpWorlds.contains(name)) event.isCancelled = true
    }

    // ARC softdepends on EliteMobs and therefore registers after its HIGHEST death handler,
    // which may clear vanilla drops. DungeonBossLockoutHandler has already populated lockouts at LOW.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun dropBossEnchantmentSupply(event: EliteMobDeathEvent) {
        bossEnchantmentRewards.addToNativeDeathDrops(event)
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
                    "<white></white> <#c42323>Сундук доступен только по заданию.",
                ),
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun authorizeWorldInstance(event: WorldInstanceEvent) {
        when (Bukkit.getServicesManager().load(ArcDungeonContracts::class.java)
            ?.authorizeInstance(event.blueprintWorldName, event.instancedWorldName)) {
            ArcDungeonContracts.Admission.NOT_PROTECTED,
            ArcDungeonContracts.Admission.AUTHORIZED -> Unit
            ArcDungeonContracts.Admission.DENIED,
            null -> event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun recoverCancelledWorldInstance(event: WorldInstanceEvent) {
        if (event.isCancelled) {
            Bukkit.getServicesManager().load(ArcDungeonContracts::class.java)
                ?.cancelInstance(event.instancedWorldName)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun guardSeasonInstanceTeleport(event: PlayerTeleportEvent) {
        val destinationWorld = event.to.world.name
        if (Bukkit.getServicesManager().load(ArcDungeonContracts::class.java)
                ?.playerAuthorized(destinationWorld, event.player.uniqueId) == false) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun dungeonStarted(event: DungeonStartEvent) {
        val instance = event.dungeonInstance
        val world = instance.contentPackagesConfigFields.worldName ?: return
        val runId = runId(instance)
        Bukkit.getServicesManager().load(ArcDungeonContracts::class.java)?.started(
            runId, world, participantIds(instance), instance.instancedWorldName,
        )
    }

    @EventHandler
    fun resumePendingSeasonDungeonRewards(event: PlayerJoinEvent) {
        Bukkit.getServicesManager().load(ArcDungeonContracts::class.java)
            ?.resumeRewards(event.player.uniqueId)
    }

    @EventHandler
    fun dungeonCompleted(event: DungeonCompleteEvent) {
        val instance = event.dungeonInstance
        val runId = runId(instance)
        try {
            Bukkit.getServicesManager().load(ArcDungeonContracts::class.java)?.completed(
                runId,
                instance.contentPackagesConfigFields.worldName ?: return,
                participantIds(instance),
                instance.instancedWorldName,
            )
        } finally {
            dungeonRunIds.remove(instance)
        }
    }

    private fun runId(instance: DungeonInstance): String =
        Bukkit.getServicesManager().load(ArcDungeonContracts::class.java)?.runId(instance.instancedWorldName)
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
