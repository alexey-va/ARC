package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.QuestsConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.customquests.CustomQuestsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfigFields
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.utils.ConfigurationLocation
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.CustomQuest
import com.magmaguy.elitemobs.quests.Quest
import com.magmaguy.elitemobs.quests.QuestTracking
import com.magmaguy.elitemobs.quests.objectives.Objective
import com.magmaguy.elitemobs.quests.objectives.QuestObjectives
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.util.UUID

class DungeonQuestsTest : FreeSpec({
    "dialogue brightens gray quest prose without changing green progress or content" {
        val gray = net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY
        val green = net.kyori.adventure.text.format.NamedTextColor.GREEN
        val source = net.kyori.adventure.text.Component.text("Скелеты ", gray)
            .append(net.kyori.adventure.text.Component.text("3 / 10", green))
        val result = readableQuestText(source)
        result.color()!!.value() shouldBe 0xF2EEE8
        result.children().single().color() shouldBe green
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(result) shouldBe "Скелеты 3 / 10"
    }

    "only own unredeemed quests appear, tracked first with live native progression" {
        mockkStatic(QuestsConfig::class)
        try {
            val owner = UUID.randomUUID()
            val objective = mockk<Objective>()
            every { QuestsConfig.getQuestScoreboardProgressionLine(objective) } returns "&7➤ Скелеты 3 / 10"
            every { objective.isObjectiveCompleted } returns false
            fun quest(name: String, uuid: UUID = owner, redeemed: Boolean = false): Quest {
                val objectives = mockk<QuestObjectives>()
                every { objectives.isTurnedIn } returns redeemed
                every { objectives.isOver } returns false
                every { objectives.objectives } returns listOf(objective)
                return mockk<Quest>().also {
                    every { it.playerUUID } returns uuid
                    every { it.questID } returns UUID.randomUUID()
                    every { it.questName } returns name
                    every { it.questObjectives } returns objectives
                }
            }
            val first = quest("Первый")
            val tracked = quest("Отслеживаемый")
            val result = dungeonQuestInfo(listOf(first, quest("Чужой", UUID.randomUUID()), quest("Сдан", redeemed = true), tracked), owner, tracked.questID)
            result.map { it.name } shouldBe listOf("Отслеживаемый", "Первый")
            result.map { it.id } shouldBe listOf(tracked.questID, first.questID)
            result.first().tracked shouldBe true
            result.first().lines shouldBe listOf("Скелеты 3 / 10")
            result.first().lineStates shouldBe listOf(DungeonQuestGoalState.ACTIVE)
            every { objective.isObjectiveCompleted } returns true
            every { QuestsConfig.getQuestScoreboardProgressionLine(objective) } returns "&a✔ Скелеты 10 / 10"
            dungeonQuestInfo(listOf(first), owner, null).single().also {
                it.lines shouldBe listOf("Скелеты 10 / 10")
                it.lineStates shouldBe listOf(DungeonQuestGoalState.COMPLETE)
            }
        } finally { unmockkStatic(QuestsConfig::class) }
    }

    "long scoreboard goals keep their counter when compacted" {
        compactDungeonQuestText("Уничтожить очень длинное название монстра 123 / 500", 28) shouldBe
            "Уничтожить очень… 123 / 500"
    }

    "local menu includes giver and turn-in NPCs but excludes even tracked quests from another dungeon" {
        mockkStatic(PlayerData::class, QuestTracking::class, NPCsConfig::class,
            ContentPackagesConfig::class, DungeonInstance::class, ConfigurationLocation::class, CustomQuestsConfig::class)
        try {
            val owner = UUID.randomUUID()
            val world = mockk<org.bukkit.World> {
                every { name } returns "current_dungeon"
                every { uid } returns UUID.randomUUID()
            }
            val player = mockk<org.bukkit.entity.Player> {
                every { uniqueId } returns owner
                every { this@mockk.world } returns world
            }
            fun npc(id: String, location: String) = mockk<NPCsConfigFields> {
                every { filename } returns id
                every { locations } returns listOf(location)
                every { spawnLocation } returns null
                every { questFilenames } returns if (id == "local.yml") listOf("local-custom.yml") else emptyList()
            }
            fun quest(name: String, giver: String, taker: String) = mockk<Quest> {
                every { playerUUID } returns owner
                every { questID } returns UUID.randomUUID()
                every { questName } returns name
                every { questGiver } returns giver
                every { questTaker } returns taker
                every { questObjectives } returns mockk {
                    every { isTurnedIn } returns false
                    every { isOver } returns false
                    every { objectives } returns emptyList()
                }
            }
            val local = quest("Местное", "local.yml", "local.yml")
            val turnInHere = quest("Сдать здесь", "foreign.yml", "local.yml")
            val foreign = quest("Чужой данж", "foreign.yml", "foreign.yml")
            val custom = mockk<CustomQuest> {
                every { playerUUID } returns owner
                every { questID } returns UUID.randomUUID()
                every { questName } returns "Местная цепочка"
                every { questGiver } returns ""
                every { questTaker } returns ""
                every { configurationFilename } returns "local-custom.yml"
                every { questObjectives } returns mockk {
                    every { isTurnedIn } returns false
                    every { isOver } returns false
                    every { objectives } returns emptyList()
                }
            }
            every { CustomQuestsConfig.getCustomQuests() } returns hashMapOf("local-custom.yml" to mockk {
                every { isTrackable } returns true
            })
            every { PlayerData.isDataLoaded(owner) } returns true
            every { PlayerData.getQuests(owner) } returns arrayListOf(local, turnInHere, foreign, custom)
            every { QuestTracking.getPlayerTrackingQuests() } returns hashMapOf(owner to mockk {
                every { this@mockk.quest } returns foreign
            })
            every { ContentPackagesConfig.getDungeonPackages() } returns emptyMap()
            every { DungeonInstance.getDungeonInstances() } returns emptySet()
            every { NPCsConfig.getNpcEntities() } returns hashMapOf(
                "local.yml" to npc("local.yml", "current_dungeon,10,64,20"),
                "foreign.yml" to npc("foreign.yml", "unrelated_dungeon,10,64,20"),
            )
            every { ConfigurationLocation.worldName(any()) } answers { firstArg<String>().substringBefore(',') }

            readDungeonQuests(player, currentDungeonOnly = true)!!.map { it.name } shouldBe
                listOf("Местное", "Сдать здесь", "Местная цепочка")
            // The player's manually selected global tracker remains available to the scoreboard.
            readDungeonQuests(player)!!.first().name shouldBe "Чужой данж"
        } finally {
            unmockkStatic(PlayerData::class, QuestTracking::class, NPCsConfig::class,
                ContentPackagesConfig::class, DungeonInstance::class, ConfigurationLocation::class, CustomQuestsConfig::class)
        }
    }

    "does not read quests before EliteMobs data is fully loaded" {
        mockkStatic(PlayerData::class)
        try {
            val owner = UUID.randomUUID()
            val player = mockk<org.bukkit.entity.Player>()
            every { player.uniqueId } returns owner
            every { PlayerData.isDataLoaded(owner) } returns false

            readDungeonQuests(player) shouldBe null

            verify(exactly = 0) { PlayerData.getQuests(owner) }
        } finally {
            unmockkStatic(PlayerData::class)
        }
    }

    "tracking action sets the requested state and stays idempotent" {
        mockkStatic(PlayerData::class, QuestTracking::class)
        try {
            val owner = UUID.randomUUID()
            val player = mockk<org.bukkit.entity.Player>()
            every { player.uniqueId } returns owner
            val oldQuest = mockk<Quest>()
            every { oldQuest.questID } returns UUID.randomUUID()
            val targetQuest = mockk<Quest>()
            val targetId = UUID.randomUUID()
            every { targetQuest.questID } returns targetId
            val oldTracking = mockk<QuestTracking>()
            every { oldTracking.quest } returns oldQuest
            val targetTracking = mockk<QuestTracking>()
            every { targetTracking.quest } returns targetQuest
            val tracking = hashMapOf(owner to oldTracking)

            every { PlayerData.isInMemory(owner) } returns true
            every { PlayerData.getQuest(owner, targetId) } returns targetQuest
            every { QuestTracking.getPlayerTrackingQuests() } returns tracking
            every { oldTracking.stop() } answers { tracking.remove(owner); Unit }
            every { targetTracking.stop() } answers { tracking.remove(owner); Unit }
            every { QuestTracking.toggleTracking(player, targetQuest) } answers { tracking[owner] = targetTracking }

            changeDungeonQuestTracking(player, targetId, true) shouldBe DungeonQuestTrackingChange.TRACKED
            tracking[owner] shouldBe targetTracking
            changeDungeonQuestTracking(player, targetId, true) shouldBe DungeonQuestTrackingChange.TRACKED
            tracking[owner] shouldBe targetTracking
            changeDungeonQuestTracking(player, targetId, false) shouldBe DungeonQuestTrackingChange.UNTRACKED
            tracking shouldBe emptyMap()
            changeDungeonQuestTracking(player, targetId, false) shouldBe DungeonQuestTrackingChange.UNTRACKED
            tracking shouldBe emptyMap()
        } finally {
            unmockkStatic(PlayerData::class, QuestTracking::class)
        }
    }
})
