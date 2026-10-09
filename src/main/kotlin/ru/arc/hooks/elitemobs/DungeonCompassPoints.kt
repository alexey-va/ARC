package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.mobconstructor.BossType
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.npcs.NPCInteractions
import com.magmaguy.elitemobs.npcs.NPCEntity
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.util.Logging
import java.lang.reflect.Field
import java.time.Instant
import java.util.HashSet
import java.util.UUID
import kotlin.math.floor

/**
 * Read-only nearby POI adapter for EliteMobs. The native registries remain the
 * source of truth; this class does not create quests, touch cooldown lists, or
 * load chunks.
 */
internal class DungeonCompassPoints(
    private val questOffers: (Player) -> List<DungeonQuestOffer>? = ::readAvailableDungeonQuests,
) {
    private val failedFamilies = mutableSetOf<String>()
    private var nextEliteSnapshotTick = Long.MIN_VALUE
    private var elitePointsByChunk = emptyMap<CompassChunk, List<DungeonCompassPoint>>()

    fun nearby(player: Player, tick: Long = 0L): List<DungeonCompassPoint> {
        val origin = player.location
        val world = origin.world ?: return emptyList()
        val now = Instant.now().epochSecond
        return collect("chests") { addChestPoints(it, player, origin, world.uid, now) } +
            collect("quests") { addNpcPoints(it, player, origin, world.uid) } +
            collect("mobs") { addEliteMobPoints(it, origin, world.uid, tick) }
    }

    private fun collect(family: String, action: (MutableList<DungeonCompassPoint>) -> Unit): List<DungeonCompassPoint> =
        runCatching {
            val result = mutableListOf<DungeonCompassPoint>()
            action(result)
            result
        }.getOrElse { failure ->
            if (failedFamilies.add(family)) {
                Logging.warn("EliteMobs compass $family unavailable; markers omitted", failure)
            }
            emptyList()
        }

    private fun addChestPoints(
        points: MutableList<DungeonCompassPoint>,
        player: Player,
        origin: Location,
        worldId: UUID,
        nowEpochSeconds: Long,
    ) {
        val chests = TreasureChest.getTreasureChestHashMap()
        if (chests.isEmpty()) return
        val stateReader = cachedChestStateReader.getOrThrow()

        for (chest in chests.values) {
            val location = chest.getLocation() ?: continue
            if (!isNearbySameWorld(origin, location, worldId)) continue
            val locationWorld = location.world ?: continue
            if (!locationWorld.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) continue

            val fields = chest.getCustomTreasureChestConfigFields() ?: continue
            val material = fields.getChestMaterial() ?: continue
            val block = location.block
            if (block.type != material) continue

            val state = stateReader.read(chest)
            if (!isChestAvailableFor(
                    player.uniqueId,
                    fields.isInstanced(),
                    fields.getDropStyle(),
                    state.restockTimeEpochSeconds,
                    EMChestCooldowns.timers(fields),
                    state.blacklistedPlayers,
                    nowEpochSeconds,
                )
            ) continue

            val entries = fields.getCustomLootTable()?.getEntries() ?: continue
            val locked = isQuestTreasureChestLocked(
                entries = entries,
                hasPermission = player::hasPermission,
                customItemPermission = { filename -> CustomItem.getCustomItem(filename)?.permission },
            )
            if (locked) continue
            points += location.toPoint(DungeonCompassPointKind.CHEST)
        }
    }

    private fun addNpcPoints(
        points: MutableList<DungeonCompassPoint>,
        player: Player,
        origin: Location,
        worldId: UUID,
    ) {
        questOffers(player).orEmpty().forEach { offer ->
            if (offer.location.world?.uid == worldId) {
                points += offer.location.toPoint(DungeonCompassPointKind.AVAILABLE_QUEST)
            }
        }

        for (npc in EntityTracker.getNpcEntities().values) {
            val location = liveNpcLocation(npc) ?: continue
            if (!isNearbySameWorld(origin, location, worldId)) continue
            val fields = npc.getNPCsConfigFields() ?: continue
            val interaction = fields.getInteractionType()
            val kind = compassNpcPointKind(fields.getFilename(), interaction)
            if (kind != null) points += location.toPoint(kind)
        }
    }

    private fun addEliteMobPoints(
        points: MutableList<DungeonCompassPoint>,
        origin: Location,
        worldId: UUID,
        tick: Long,
    ) {
        val centerX = origin.blockX shr 4
        val centerZ = origin.blockZ shr 4
        val nearbyChunks = eliteSnapshot(tick)
        for (chunkX in centerX - 4..centerX + 4) {
            for (chunkZ in centerZ - 4..centerZ + 4) {
                for (point in nearbyChunks[CompassChunk(worldId, chunkX, chunkZ)].orEmpty()) {
                    if (isNearbySameWorld(origin, point, worldId)) points += point
                }
            }
        }
    }

    /** One tracker snapshot per compass refresh interval, indexed by already-loaded chunk. */
    private fun eliteSnapshot(tick: Long): Map<CompassChunk, List<DungeonCompassPoint>> {
        if (tick < nextEliteSnapshotTick) return elitePointsByChunk
        val snapshot = collect("mobs") { points ->
            for (elite in EntityTracker.getEliteMobEntities().values) {
                val living = elite.getLivingEntity() ?: continue
                if (!living.isValid || living.isDead) continue
                val location = living.location
                val world = location.world ?: continue
                val chunkX = location.blockX shr 4
                val chunkZ = location.blockZ shr 4
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue
                val kind = if (elite is CustomBossEntity && elite.customBossesConfigFields.bossType in BOSS_TYPES)
                    DungeonCompassPointKind.ELITE_BOSS else DungeonCompassPointKind.ELITE_MOB
                points += location.toPoint(kind)
            }
        }
        elitePointsByChunk = snapshot.groupBy { point ->
            CompassChunk(point.worldId, floor(point.x).toInt() shr 4, floor(point.z).toInt() shr 4)
        }
        nextEliteSnapshotTick = tick + ELITE_SNAPSHOT_INTERVAL_TICKS
        return elitePointsByChunk
    }

    private fun liveNpcLocation(npc: NPCEntity): Location? {
        val villager = npc.getVillager() ?: return null
        if (!npc.isValid() || !villager.isValid) return null
        return villager.location
    }

    private companion object {
        val cachedChestStateReader: Result<ChestStateReader> = ChestStateReader.create()
    }
}

private val BOSS_TYPES = setOf(BossType.BOSS, BossType.MINIBOSS, BossType.EVENT)

// ponytail: one native-map snapshot per second keeps per-player work to 81 chunk lookups; add spawn/move event tracking only if one-second staleness is visible.
private const val ELITE_SNAPSHOT_INTERVAL_TICKS = 20L

private data class CompassChunk(val worldId: UUID, val x: Int, val z: Int)

internal fun compassNpcPointKind(
    filename: String,
    interaction: NPCInteractions.NPCInteractionType,
): DungeonCompassPointKind? {
    if (filename.equals("guild_patrol", ignoreCase = true) ||
        interaction == NPCInteractions.NPCInteractionType.GUILD_GREETER
    ) return DungeonCompassPointKind.GUILD_NPC

    return when (interaction) {
        NPCInteractions.NPCInteractionType.CLASS_TRAINER -> DungeonCompassPointKind.CLASS_TRAINER
        NPCInteractions.NPCInteractionType.ARENA,
        NPCInteractions.NPCInteractionType.ARENA_MASTER -> DungeonCompassPointKind.ARENA
        NPCInteractions.NPCInteractionType.TRANSPORT,
        NPCInteractions.NPCInteractionType.TELEPORT_BACK -> DungeonCompassPointKind.TRANSPORT
        NPCInteractions.NPCInteractionType.CUSTOM_SHOP,
        NPCInteractions.NPCInteractionType.PROCEDURALLY_GENERATED_SHOP,
        NPCInteractions.NPCInteractionType.SELL,
        NPCInteractions.NPCInteractionType.ARROW_SHOP,
        NPCInteractions.NPCInteractionType.GAMBLING_BLACKJACK,
        NPCInteractions.NPCInteractionType.GAMBLING_COINFLIP,
        NPCInteractions.NPCInteractionType.GAMBLING_SLOTS,
        NPCInteractions.NPCInteractionType.GAMBLING_HIGHERLOWER -> DungeonCompassPointKind.SHOP
        NPCInteractions.NPCInteractionType.REPAIRMAN -> DungeonCompassPointKind.REPAIR
        NPCInteractions.NPCInteractionType.SCRAPPER -> DungeonCompassPointKind.SCRAP
        NPCInteractions.NPCInteractionType.ENHANCER,
        NPCInteractions.NPCInteractionType.ENCHANTER -> DungeonCompassPointKind.ENCHANT
        NPCInteractions.NPCInteractionType.UNBINDER -> DungeonCompassPointKind.UNBIND
        NPCInteractions.NPCInteractionType.SCROLL_APPLIER -> DungeonCompassPointKind.SCROLL
        NPCInteractions.NPCInteractionType.QUEST_GIVER,
        NPCInteractions.NPCInteractionType.CUSTOM_QUEST_GIVER -> null
        NPCInteractions.NPCInteractionType.NONE -> null
        else -> DungeonCompassPointKind.NPC_SERVICE
    }
}

private data class ChestRuntimeState(
    val restockTimeEpochSeconds: Long,
    val blacklistedPlayers: Set<UUID>,
)

private class ChestStateReader private constructor(
    private val restockTimeField: Field,
    private val blacklistedPlayersField: Field,
) {
    fun read(chest: TreasureChest): ChestRuntimeState {
        val rawBlacklistedPlayers = blacklistedPlayersField.get(chest)
        require(rawBlacklistedPlayers is Set<*>) { "Unexpected EliteMobs chest blacklist type" }
        return ChestRuntimeState(
            restockTimeEpochSeconds = restockTimeField.getLong(chest),
            blacklistedPlayers = rawBlacklistedPlayers.filterIsInstance<UUID>().toSet(),
        )
    }

    companion object {
        fun create(): Result<ChestStateReader> = runCatching {
            val restockTimeField = TreasureChest::class.java.getDeclaredField("restockTime").apply {
                check(type == Long::class.javaPrimitiveType)
                isAccessible = true
            }
            val blacklistedPlayersField = TreasureChest::class.java
                .getDeclaredField("blacklistedPlayersInstance")
                .apply {
                    check(type == HashSet::class.java)
                    isAccessible = true
                }
            ChestStateReader(restockTimeField, blacklistedPlayersField)
        }
    }
}

internal fun isChestAvailableFor(
    playerId: UUID,
    instanced: Boolean,
    dropStyle: TreasureChest.DropStyle,
    restockTime: Long,
    restockTimers: Collection<String>?,
    blacklistedPlayers: Set<UUID>,
    nowEpochSeconds: Long,
): Boolean {
    if (dropStyle != TreasureChest.DropStyle.GROUP) return true
    if (restockTime > nowEpochSeconds) return false
    if (instanced) return playerId !in blacklistedPlayers
    return restockTimers.orEmpty().none { timer ->
        val separator = timer.indexOf(':')
        if (separator <= 0 || separator == timer.lastIndex) return@none false
        if (timer.substring(0, separator) != playerId.toString()) return@none false
        timer.substring(separator + 1).toLongOrNull()?.let { it > nowEpochSeconds } == true
    }
}

private fun isNearbySameWorld(origin: Location, target: Location, worldId: UUID): Boolean {
    if (target.world?.uid != worldId) return false
    val deltaX = target.x - origin.x
    val deltaY = target.y - origin.y
    val deltaZ = target.z - origin.z
    return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= DUNGEON_COMPASS_RADIUS * DUNGEON_COMPASS_RADIUS
}

private fun isNearbySameWorld(origin: Location, target: DungeonCompassPoint, worldId: UUID): Boolean {
    if (target.worldId != worldId) return false
    val deltaX = target.x - origin.x
    val deltaY = target.y - origin.y
    val deltaZ = target.z - origin.z
    return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= DUNGEON_COMPASS_RADIUS * DUNGEON_COMPASS_RADIUS
}

private fun Location.toPoint(kind: DungeonCompassPointKind): DungeonCompassPoint =
    DungeonCompassPoint(world!!.uid, x, y, z, kind)
