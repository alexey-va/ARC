package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.customquests.CustomQuestsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfigFields
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.npcs.NPCInteractions
import com.magmaguy.elitemobs.npcs.NPCEntity
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.CustomQuest
import com.magmaguy.elitemobs.quests.DynamicQuest
import com.magmaguy.elitemobs.quests.Quest
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.util.Logging
import java.lang.reflect.Field
import java.time.Instant
import java.util.HashSet
import java.util.UUID

/**
 * Read-only nearby POI adapter for EliteMobs. The native registries remain the
 * source of truth; this class does not create quests, touch cooldown lists, or
 * load chunks.
 */
internal class DungeonCompassPoints {
    private val failedFamilies = mutableSetOf<String>()

    fun nearby(player: Player): List<DungeonCompassPoint> {
        val origin = player.location
        val world = origin.world ?: return emptyList()
        val now = Instant.now().epochSecond
        return collect("chests") { addChestPoints(it, player, origin, world.uid, now) } +
            collect("quests") { addQuestPoints(it, player, origin, world.uid) }
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
                    fields.getRestockTimers(),
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

    private fun addQuestPoints(
        points: MutableList<DungeonCompassPoint>,
        player: Player,
        origin: Location,
        worldId: UUID,
    ) {
        if (!PlayerData.isInMemory(player)) return
        val existingQuestFilenames = activeCustomQuestFilenames(PlayerData.getQuests(player.uniqueId).orEmpty())

        for (npc in EntityTracker.getNpcEntities().values) {
            val location = liveNpcLocation(npc) ?: continue
            if (!isNearbySameWorld(origin, location, worldId)) continue
            val fields = npc.getNPCsConfigFields() ?: continue
            val available = when (fields.getInteractionType()) {
                NPCInteractions.NPCInteractionType.CUSTOM_QUEST_GIVER ->
                    customQuestOfferAvailable(player, fields, existingQuestFilenames)
                NPCInteractions.NPCInteractionType.QUEST_GIVER ->
                    player.hasPermission("elitemobs.quest.npc") && DynamicQuest.hasAvailableQuests(player)
                else -> false
            }
            if (available) points += location.toPoint(DungeonCompassPointKind.AVAILABLE_QUEST)
        }
    }

    private fun customQuestOfferAvailable(
        player: Player,
        fields: NPCsConfigFields,
        existingQuestFilenames: Set<String>,
    ): Boolean = fields.getQuestFilenames().orEmpty().any { filename ->
        val questFields = CustomQuestsConfig.getCustomQuests()[filename] ?: return@any false
        isFreshQuestOffer(
            filename = filename,
            existingQuestFilenames = existingQuestFilenames,
            nativePermission = CustomQuest.hasPermissionForQuest(player, questFields),
        )
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

internal fun activeCustomQuestFilenames(quests: Collection<Quest>): Set<String> =
    quests.filterIsInstance<CustomQuest>()
        .filter { it.isAccepted() && !it.getQuestObjectives().isTurnedIn() }
        .map { it.getConfigurationFilename() }
        .toSet()

internal fun isFreshQuestOffer(
    filename: String,
    existingQuestFilenames: Set<String>,
    nativePermission: Boolean,
): Boolean = filename.isNotBlank() && filename !in existingQuestFilenames && nativePermission

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

private fun Location.toPoint(kind: DungeonCompassPointKind): DungeonCompassPoint =
    DungeonCompassPoint(world!!.uid, x, y, z, kind)
