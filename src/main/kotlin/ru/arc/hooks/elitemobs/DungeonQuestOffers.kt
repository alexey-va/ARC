package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.customquests.CustomQuestsConfig
import com.magmaguy.elitemobs.config.customquests.CustomQuestsConfigFields
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfigFields
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.npcs.NPCEntity
import com.magmaguy.elitemobs.npcs.NPCInteractions
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.CustomQuest
import com.magmaguy.elitemobs.quests.DynamicQuest
import com.magmaguy.elitemobs.quests.Quest
import com.magmaguy.elitemobs.utils.ConfigurationLocation
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID

internal data class DungeonQuestOffer(
    val id: String,
    val name: String,
    val npcName: String,
    val location: Location,
    val worldName: String = location.world?.name.orEmpty(),
)

/** Lists only offers that the native EliteMobs quest giver would allow now. */
internal fun readAvailableDungeonQuests(player: Player): List<DungeonQuestOffer>? {
    val playerId = player.uniqueId
    if (!PlayerData.isDataLoaded(playerId)) return null

    val activeCustomQuests = activeCustomQuestFilenames(PlayerData.getQuests(playerId).orEmpty())
    val questConfigs = CustomQuestsConfig.getCustomQuests()
    val runtimeNpcsByFilename = EntityTracker.getNpcEntities().values
        .mapNotNull { npc -> npc.getNPCsConfigFields()?.let { it.filename to npc } }
        .groupBy({ it.first }, { it.second })
    val dungeonWorldNames = ContentPackagesConfig.getDungeonPackages().values
        .mapNotNull { fields -> fields.name.takeIf(String::isNotBlank)?.let { fields.worldName to it } }
        .toMap()
    val instanceWorldNames = DungeonInstance.getDungeonInstances()
        .mapNotNull { instance ->
            instance.contentPackagesConfigFields.name.takeIf(String::isNotBlank)
                ?.let { instance.world.uid to it }
        }
        .toMap()
    val offers = mutableListOf<DungeonQuestOffer>()
    var dynamicQuestsAvailable: Boolean? = null

    for (npcFields in NPCsConfig.getNpcEntities().values) {
        if (!npcFields.isEnabled) continue
        val interaction = npcFields.interactionType
        if (interaction != NPCInteractions.NPCInteractionType.CUSTOM_QUEST_GIVER &&
            interaction != NPCInteractions.NPCInteractionType.QUEST_GIVER
        ) continue
        val npcName = npcFields.name.takeIf(String::isNotBlank) ?: npcFields.filename
        val availableQuests = when (interaction) {
            NPCInteractions.NPCInteractionType.CUSTOM_QUEST_GIVER -> {
                npcFields.questFilenames.orEmpty().distinct().mapNotNull { filename ->
                    val quest = questConfigs[filename] ?: return@mapNotNull null
                    val available = CustomQuest.hasPermissionForQuest(player, quest)
                    if (!isFreshQuestOffer(filename, activeCustomQuests, available)) return@mapNotNull null
                    val questName = quest.questName.takeIf(String::isNotBlank) ?: filename
                    filename to questName
                }
            }

            NPCInteractions.NPCInteractionType.QUEST_GIVER -> {
                val available = dynamicQuestsAvailable ?: (
                    player.hasPermission("elitemobs.quest.npc") && DynamicQuest.hasAvailableQuests(player)
                    ).also { dynamicQuestsAvailable = it }
                if (available) listOf("dynamic:${npcFields.filename}" to "Задания гильдии") else emptyList()
            }

        }
        if (availableQuests.isEmpty()) continue

        val locations = questNpcLocations(player, npcFields, runtimeNpcsByFilename[npcFields.filename].orEmpty())
        for ((id, name) in availableQuests) {
            for (location in locations) {
                offers += questOffer(id, name, npcName, location, dungeonWorldNames, instanceWorldNames)
            }
        }
    }

    val origin = player.location
    val originWorldId = origin.world?.uid
    return offers.sortedWith(
        compareBy<DungeonQuestOffer>(
            { it.location.world?.uid != originWorldId },
            { if (it.location.world?.uid == originWorldId) it.location.distanceSquared(origin) else 0.0 },
            { it.name.lowercase(Locale.ROOT) },
            { it.npcName.lowercase(Locale.ROOT) },
            { it.worldName.lowercase(Locale.ROOT) },
            { it.location.x },
            { it.location.y },
            { it.location.z },
            { it.id },
        ),
    )
}

private fun questNpcLocations(
    player: Player,
    fields: NPCsConfigFields,
    runtimeNpcs: List<NPCEntity>,
): List<Location> {
    val authoredLocations = (fields.locations.orEmpty() + listOfNotNull(fields.spawnLocation))
        .filter(String::isNotBlank)
        .distinct()
    val locations = mutableListOf<Location>()

    fun runtimeLocations(raw: String? = null): List<Location> = runtimeNpcs.asSequence()
        .filter { raw == null || it.locationString == raw }
        .mapNotNull(::npcLocation)
        .toList()

    if (!fields.isInstanced) {
        for (raw in authoredLocations) {
            val live = runtimeLocations(raw)
            if (live.isNotEmpty()) locations += live
            else ConfigurationLocation.serialize(raw)
                ?.takeIf(::isUsableLocation)
                ?.let(locations::add)
        }
        locations += runtimeLocations()
        return locations.distinctBy(::locationKey)
    }

    val playerWorld = player.world
    val sameWorldRuntime = runtimeLocations().filter { it.world?.uid == playerWorld.uid }
    locations += sameWorldRuntime

    val instance = PlayerData.getMatchInstance(player) as? DungeonInstance
        ?: return locations.distinctBy(::locationKey)
    if (instance.world.uid != playerWorld.uid) return locations.distinctBy(::locationKey)

    val instanceBlueprintWorld = instance.contentPackagesConfigFields.worldName
    for (raw in authoredLocations) {
        if (ConfigurationLocation.worldName(raw) != instanceBlueprintWorld) continue
        val live = runtimeLocations(raw).filter { it.world?.uid == playerWorld.uid }
        if (live.isEmpty()) {
            ConfigurationLocation.serializeWithInstance(instance.world, raw)
                ?.takeIf(::isUsableLocation)
                ?.let(locations::add)
        }
    }
    return locations.distinctBy(::locationKey)
}

private fun npcLocation(npc: NPCEntity): Location? {
    val villager = npc.villager
    return (villager?.takeIf { it.isValid }?.location ?: npc.persistentLocation)
        ?.takeIf(::isUsableLocation)
        ?.clone()
}

private fun isUsableLocation(location: Location): Boolean = location.world != null &&
    location.x.isFinite() && location.y.isFinite() && location.z.isFinite()

private fun questOffer(
    id: String,
    name: String,
    npcName: String,
    location: Location,
    dungeonWorldNames: Map<String, String>,
    instanceWorldNames: Map<UUID, String>,
): DungeonQuestOffer {
    val world = location.world ?: error("Quest NPC location has no world")
    val displayWorldName = when (world.name) {
        "em_adventurers_guild" -> "Гильдия приключений"
        else -> instanceWorldNames[world.uid] ?: dungeonWorldNames[world.name] ?: world.name
    }
    return DungeonQuestOffer(id, name, npcName, location, displayWorldName)
}

private data class LocationKey(val worldId: UUID, val x: Double, val y: Double, val z: Double)

private fun locationKey(location: Location): LocationKey = LocationKey(
    location.world!!.uid,
    location.x,
    location.y,
    location.z,
)

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
