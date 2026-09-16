package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.QuestsConfig
import com.magmaguy.elitemobs.playerdata.database.PlayerData
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
            every { QuestsConfig.getQuestScoreboardProgressionLine(objective) } returns "Скелеты 3 / 10"
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
            every { QuestsConfig.getQuestScoreboardProgressionLine(objective) } returns "Скелеты 4 / 10"
            dungeonQuestInfo(listOf(first), owner, null).single().lines shouldBe listOf("Скелеты 4 / 10")
        } finally { unmockkStatic(QuestsConfig::class) }
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
