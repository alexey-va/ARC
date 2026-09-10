package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.QuestsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfig
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.Quest
import com.magmaguy.elitemobs.quests.QuestTracking
import org.bukkit.entity.Player
import java.util.UUID

internal data class DungeonQuestInfo(val name: String, val tracked: Boolean, val complete: Boolean, val lines: List<String>)

internal fun readDungeonQuests(player: Player): List<DungeonQuestInfo>? {
    // Avoid a synchronous database lookup while the player's EliteMobs data is loading.
    if (!PlayerData.isInMemory(player.uniqueId)) return null
    val tracked = QuestTracking.getPlayerTrackingQuests()[player.uniqueId]?.customQuest?.questID
    return dungeonQuestInfo(PlayerData.getQuests(player.uniqueId).orEmpty(), player.uniqueId, tracked)
}

internal fun dungeonQuestInfo(quests: List<Quest>, owner: UUID, tracked: UUID?): List<DungeonQuestInfo> =
    quests.filter { it.playerUUID == owner && !it.questObjectives.isTurnedIn }
        .sortedByDescending { it.questID == tracked }
        .map { quest ->
            val objectives = quest.questObjectives
            val complete = objectives.isOver
            val lines = if (complete) {
                val npc = NPCsConfig.getNpcEntities()[quest.questTaker]?.name
                if (npc.isNullOrBlank()) emptyList() else listOf(QuestsConfig.getQuestTurnInObjective().replace("\$npcName", npc))
            } else objectives.objectives.orEmpty().filterNotNull().map { QuestsConfig.getQuestScoreboardProgressionLine(it) }
            DungeonQuestInfo(quest.questName.orEmpty(), quest.questID == tracked, complete, lines)
        }

/** Quest prose stays readable on dialogue backgrounds; meaningful color accents are retained. */
internal fun readableQuestText(value: net.kyori.adventure.text.Component): net.kyori.adventure.text.Component {
    val light = net.kyori.adventure.text.format.TextColor.color(0xF2EEE8)
    fun brighten(node: net.kyori.adventure.text.Component): net.kyori.adventure.text.Component {
        val color = node.color()
        val channels = color?.let { listOf(it.red(), it.green(), it.blue()) }
        val gray = channels != null && channels.max() - channels.min() <= 24 && channels.max() < 220
        return (if (gray) node.color(light) else node).children(node.children().map(::brighten))
    }
    return brighten(value).colorIfAbsent(light)
}
