package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.customquests.CustomQuestsConfig
import com.magmaguy.elitemobs.config.customquests.CustomQuestsConfigFields
import com.magmaguy.elitemobs.config.npcs.NPCsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfigFields
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.npcs.NPCInteractions
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.CustomQuest
import com.magmaguy.elitemobs.quests.DynamicQuest
import com.magmaguy.elitemobs.quests.Quest
import com.magmaguy.elitemobs.quests.objectives.QuestObjectives
import com.magmaguy.elitemobs.utils.ConfigurationLocation
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

class DungeonQuestOffersTest : FreeSpec({
    "cold player quest data returns no partial offers and avoids native registries" {
        mockkStatic(PlayerData::class, NPCsConfig::class, EntityTracker::class)
        try {
            val playerId = UUID.randomUUID()
            val player = mockk<Player> { every { uniqueId } returns playerId }
            every { PlayerData.isDataLoaded(playerId) } returns false

            readAvailableDungeonQuests(player) shouldBe null

            verify(exactly = 0) { PlayerData.getQuests(playerId) }
            verify(exactly = 0) { NPCsConfig.getNpcEntities() }
            verify(exactly = 0) { EntityTracker.getNpcEntities() }
        } finally {
            unmockkStatic(PlayerData::class, NPCsConfig::class, EntityTracker::class)
        }
    }

    "custom offers use native eligibility, omit accepted quests, and keep unloaded distant targets" {
        mockOfferStatics()
        try {
            val world = mockk<World> {
                every { uid } returns UUID.randomUUID()
                every { name } returns "em_adventurers_guild"
                every { isChunkLoaded(any(), any()) } returns false
            }
            val playerId = UUID.randomUUID()
            val player = mockk<Player> {
                every { uniqueId } returns playerId
                every { location } returns Location(world, 0.0, 64.0, 0.0)
            }
            val active = customQuest("active.yml", accepted = true, turnedIn = false)
            val redeemed = customQuest("repeat.yml", accepted = true, turnedIn = true)
            val activeFields = customQuestFields("Уже взято")
            val repeatFields = customQuestFields("Повторное")
            val lockedFields = customQuestFields("Закрыто")
            val filenames = listOf("active.yml", "repeat.yml", "locked.yml")
            val rawLocations = listOf("guild-one", "guild-two", "invalid")
            val npcFields = npcFields(
                filename = "guild_quest_giver",
                interaction = NPCInteractions.NPCInteractionType.CUSTOM_QUEST_GIVER,
                questFilenames = filenames,
                locations = rawLocations,
            )
            val first = Location(world, 6_500.0, 64.0, 0.0)
            val second = Location(world, 7_000.0, 64.0, 0.0)
            every { PlayerData.isDataLoaded(playerId) } returns true
            every { PlayerData.getQuests(playerId) } returns arrayListOf<Quest>(active, redeemed)
            every { NPCsConfig.getNpcEntities() } returns hashMapOf("guild_quest_giver" to npcFields)
            every { EntityTracker.getNpcEntities() } returns hashMapOf()
            every { CustomQuestsConfig.getCustomQuests() } returns hashMapOf(
                "active.yml" to activeFields,
                "repeat.yml" to repeatFields,
                "locked.yml" to lockedFields,
            )
            every { CustomQuest.hasPermissionForQuest(player, activeFields) } returns true
            every { CustomQuest.hasPermissionForQuest(player, repeatFields) } returns true
            every { CustomQuest.hasPermissionForQuest(player, lockedFields) } returns false
            every { ConfigurationLocation.serialize("guild-one") } returns first
            every { ConfigurationLocation.serialize("guild-two") } returns second
            every { ConfigurationLocation.serialize("invalid") } returns Location(null, Double.NaN, 64.0, 0.0)

            val offers = readAvailableDungeonQuests(player).orEmpty()

            offers.map { it.id } shouldBe listOf("repeat.yml", "repeat.yml")
            offers.map { it.location.x } shouldBe listOf(6_500.0, 7_000.0)
            offers.map { it.name }.toSet() shouldBe setOf("Повторное")
            offers.map { it.worldName }.toSet() shouldBe setOf("Гильдия приключений")
            verify(exactly = 0) { world.isChunkLoaded(any(), any()) }
            verify(exactly = 1) { ConfigurationLocation.serialize("invalid") }
        } finally {
            unmockOfferStatics()
        }
    }

    "dynamic quest giver is permission gated and reported once per configured location" {
        mockOfferStatics()
        mockkStatic(DynamicQuest::class)
        try {
            val world = mockk<World> {
                every { uid } returns UUID.randomUUID()
                every { name } returns "em_adventurers_guild"
                every { isChunkLoaded(any(), any()) } returns false
            }
            val playerId = UUID.randomUUID()
            var hasQuestPermission = false
            val player = mockk<Player> {
                every { uniqueId } returns playerId
                every { location } returns Location(world, 0.0, 64.0, 0.0)
                every { hasPermission("elitemobs.quest.npc") } answers { hasQuestPermission }
            }
            val npcConfig = npcFields(
                filename = "guild_requests",
                interaction = NPCInteractions.NPCInteractionType.QUEST_GIVER,
                locations = listOf("guild-one", "guild-two"),
            )
            every { PlayerData.isDataLoaded(playerId) } returns true
            every { PlayerData.getQuests(playerId) } returns arrayListOf()
            every { NPCsConfig.getNpcEntities() } returns hashMapOf("guild_requests" to npcConfig)
            every { EntityTracker.getNpcEntities() } returns hashMapOf()
            every { CustomQuestsConfig.getCustomQuests() } returns hashMapOf()
            every { DynamicQuest.hasAvailableQuests(player) } returns true
            every { ConfigurationLocation.serialize("guild-one") } returns Location(world, 8_000.0, 64.0, 0.0)
            every { ConfigurationLocation.serialize("guild-two") } returns Location(world, 9_000.0, 64.0, 0.0)

            readAvailableDungeonQuests(player).orEmpty() shouldBe emptyList()
            verify(exactly = 0) { DynamicQuest.hasAvailableQuests(player) }

            hasQuestPermission = true
            val offers = readAvailableDungeonQuests(player).orEmpty()

            offers.map { it.id }.toSet() shouldBe setOf("dynamic:guild_requests")
            offers.map { it.name }.toSet() shouldBe setOf("Задания гильдии")
            offers.size shouldBe 2
            verify(exactly = 1) { DynamicQuest.hasAvailableQuests(player) }
            verify(exactly = 0) { world.isChunkLoaded(any(), any()) }
        } finally {
            unmockStaticOfferStatics()
        }
    }

    "instanced NPC coordinates are rebound only into this player's matching dungeon instance" {
        mockOfferStatics()
        try {
            val instanceWorld = mockk<World> {
                every { uid } returns UUID.randomUUID()
                every { name } returns "dungeon-copy-42"
            }
            val instanceFields = mockk<com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields> {
                every { worldName } returns "native_dungeon"
                every { name } returns "Native Dungeon"
            }
            val instance = mockk<DungeonInstance> {
                every { world } returns instanceWorld
                every { contentPackagesConfigFields } returns instanceFields
            }
            val playerId = UUID.randomUUID()
            val player = mockk<Player> {
                every { uniqueId } returns playerId
                every { world } returns instanceWorld
                every { location } returns Location(instanceWorld, 0.0, 64.0, 0.0)
            }
            val npcConfig = npcFields(
                filename = "instance_quest_giver",
                interaction = NPCInteractions.NPCInteractionType.CUSTOM_QUEST_GIVER,
                questFilenames = listOf("instance-quest.yml"),
                locations = listOf("native_dungeon,200,65,300", "other_dungeon,200,65,300"),
                instanced = true,
            )
            val customFields = customQuestFields("Instance quest")
            every { PlayerData.isDataLoaded(playerId) } returns true
            every { PlayerData.getQuests(playerId) } returns arrayListOf()
            every { PlayerData.getMatchInstance(player) } returns instance
            every { NPCsConfig.getNpcEntities() } returns hashMapOf("instance_quest_giver" to npcConfig)
            every { EntityTracker.getNpcEntities() } returns hashMapOf()
            every { CustomQuestsConfig.getCustomQuests() } returns hashMapOf("instance-quest.yml" to customFields)
            every { CustomQuest.hasPermissionForQuest(player, customFields) } returns true
            every { DungeonInstance.getDungeonInstances() } returns setOf(instance)
            every { ConfigurationLocation.worldName("native_dungeon,200,65,300") } returns "native_dungeon"
            every { ConfigurationLocation.worldName("other_dungeon,200,65,300") } returns "other_dungeon"
            every { ConfigurationLocation.serializeWithInstance(instanceWorld, "native_dungeon,200,65,300") } returns
                Location(instanceWorld, 200.0, 65.0, 300.0)

            val offers = readAvailableDungeonQuests(player).orEmpty()

            offers.map { it.location.world?.uid } shouldBe listOf(instanceWorld.uid)
            offers.map { it.worldName } shouldBe listOf("Native Dungeon")
            verify(exactly = 1) {
                ConfigurationLocation.serializeWithInstance(instanceWorld, "native_dungeon,200,65,300")
            }
            verify(exactly = 0) {
                ConfigurationLocation.serializeWithInstance(instanceWorld, "other_dungeon,200,65,300")
            }
        } finally {
            unmockOfferStatics()
        }
    }
})

private fun mockOfferStatics() {
    mockkStatic(
        PlayerData::class,
        NPCsConfig::class,
        CustomQuestsConfig::class,
        CustomQuest::class,
        EntityTracker::class,
        ContentPackagesConfig::class,
        DungeonInstance::class,
        ConfigurationLocation::class,
    )
    every { ContentPackagesConfig.getDungeonPackages() } returns emptyMap()
    every { DungeonInstance.getDungeonInstances() } returns emptySet()
}

private fun unmockOfferStatics() = unmockkStatic(
    PlayerData::class,
    NPCsConfig::class,
    CustomQuestsConfig::class,
    CustomQuest::class,
    EntityTracker::class,
    ContentPackagesConfig::class,
    DungeonInstance::class,
    ConfigurationLocation::class,
)

private fun unmockStaticOfferStatics() {
    unmockkStatic(DynamicQuest::class)
    unmockOfferStatics()
}

private fun npcFields(
    filename: String,
    interaction: NPCInteractions.NPCInteractionType,
    questFilenames: List<String> = emptyList(),
    locations: List<String>,
    instanced: Boolean = false,
): NPCsConfigFields = mockk {
    every { isEnabled() } returns true
    every { getFilename() } returns filename
    every { getName() } returns "Гильдейский распорядитель"
    every { getInteractionType() } returns interaction
    every { getQuestFilenames() } returns ArrayList(questFilenames)
    every { getLocations() } returns ArrayList(locations)
    every { getSpawnLocation() } returns null
    every { isInstanced() } returns instanced
}

private fun customQuestFields(name: String): CustomQuestsConfigFields = mockk {
    every { questName } returns name
}

private fun customQuest(filename: String, accepted: Boolean, turnedIn: Boolean): CustomQuest {
    val objectives = mockk<QuestObjectives> { every { isTurnedIn() } returns turnedIn }
    return mockk {
        every { isAccepted() } returns accepted
        every { getQuestObjectives() } returns objectives
        every { getConfigurationFilename() } returns filename
    }
}
