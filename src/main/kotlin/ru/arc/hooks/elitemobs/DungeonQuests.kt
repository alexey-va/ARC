package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.QuestsConfig
import com.magmaguy.elitemobs.config.customquests.CustomQuestsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfig
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.CustomQuest
import com.magmaguy.elitemobs.quests.Quest
import com.magmaguy.elitemobs.quests.QuestTracking
import org.bukkit.entity.Player
import ru.arc.util.TextUtil
import java.util.UUID

internal enum class DungeonQuestGoalState { ACTIVE, COMPLETE, NEXT }

internal data class DungeonQuestInfo(
    val id: UUID,
    val name: String,
    val tracked: Boolean,
    val complete: Boolean,
    val trackable: Boolean,
    val lines: List<String>,
    val lineStates: List<DungeonQuestGoalState> = List(lines.size) {
        if (complete) DungeonQuestGoalState.NEXT else DungeonQuestGoalState.ACTIVE
    },
)

internal enum class DungeonQuestTrackingChange { TRACKED, UNTRACKED, LOADING, MISSING, UNTRACKABLE, FAILED }
internal enum class DungeonQuestAbandonChange { ABANDONED, LOADING, MISSING, FAILED }

internal fun readDungeonQuests(player: Player): List<DungeonQuestInfo>? {
    // Avoid a synchronous database lookup while the player's EliteMobs data is loading.
    if (!PlayerData.isInMemory(player.uniqueId)) return null
    val tracked = QuestTracking.getPlayerTrackingQuests()[player.uniqueId]?.quest?.questID
    return dungeonQuestInfo(PlayerData.getQuests(player.uniqueId).orEmpty(), player.uniqueId, tracked)
}

internal fun dungeonQuestInfo(quests: List<Quest>, owner: UUID, tracked: UUID?): List<DungeonQuestInfo> =
    quests.filter { it.playerUUID == owner && !it.questObjectives.isTurnedIn }
        .sortedByDescending { it.questID == tracked }
        .map { quest ->
            val objectives = quest.questObjectives
            val complete = objectives.isOver
            val activeObjectives = objectives.objectives.orEmpty().filterNotNull()
            val lines = activeObjectives.map { plainDungeonQuestText(QuestsConfig.getQuestScoreboardProgressionLine(it)) }.toMutableList()
            val states = activeObjectives.map {
                if (it.isObjectiveCompleted) DungeonQuestGoalState.COMPLETE else DungeonQuestGoalState.ACTIVE
            }.toMutableList()
            if (complete) {
                NPCsConfig.getNpcEntities()[quest.questTaker]?.name?.takeIf(String::isNotBlank)?.let { npc ->
                    lines += plainDungeonQuestText(QuestsConfig.getQuestTurnInObjective().replace("\$npcName", npc))
                    states += DungeonQuestGoalState.NEXT
                }
            }
            DungeonQuestInfo(
                quest.questID,
                quest.questName.orEmpty(),
                quest.questID == tracked,
                complete,
                quest.isTrackable(),
                lines,
                states,
            )
        }

private val questPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
private val leadingQuestMarker = Regex("^[\\s➤✔○▶]+")
private val questCounterSuffix = Regex("(\\d[\\d ]*\\s*/\\s*\\d[\\d ]*)$")

internal fun plainDungeonQuestText(value: String): String = questPlain.serialize(TextUtil.legacy(value))
    .replace(leadingQuestMarker, "")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun compactDungeonQuestText(value: String, limit: Int): String {
    require(limit >= 4)
    val text = plainDungeonQuestText(value)
    if (text.length <= limit) return text
    val counter = questCounterSuffix.find(text)?.value
    if (counter != null && counter.length + 3 < limit) {
        val labelLimit = limit - counter.length - 1
        val label = text.removeSuffix(counter).trimEnd().take(labelLimit - 1).trimEnd() + "…"
        return "$label $counter"
    }
    return text.take(limit - 1).trimEnd() + "…"
}

internal fun dungeonQuestChecklistLine(text: String, state: DungeonQuestGoalState): net.kyori.adventure.text.Component {
    val marker = when (state) {
        DungeonQuestGoalState.ACTIVE -> net.kyori.adventure.text.Component.text("○", net.kyori.adventure.text.format.TextColor.color(0xF4BD6A))
        DungeonQuestGoalState.COMPLETE -> net.kyori.adventure.text.Component.text("✔", net.kyori.adventure.text.format.TextColor.color(0x9BD48D))
        DungeonQuestGoalState.NEXT -> net.kyori.adventure.text.Component.text("▶", net.kyori.adventure.text.format.TextColor.color(0x92BED8))
    }
    return marker.append(net.kyori.adventure.text.Component.space())
        .append(net.kyori.adventure.text.Component.text(plainDungeonQuestText(text), net.kyori.adventure.text.format.TextColor.color(0xF2EEE8)))
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
}

private fun Quest.isTrackable(): Boolean = this !is CustomQuest ||
    CustomQuestsConfig.getCustomQuests()[configurationFilename]?.isTrackable == true

internal fun changeDungeonQuestTracking(player: Player, questId: UUID, enabled: Boolean): DungeonQuestTrackingChange {
    if (!PlayerData.isInMemory(player.uniqueId)) return DungeonQuestTrackingChange.LOADING
    val quest = PlayerData.getQuest(player.uniqueId, questId) ?: return DungeonQuestTrackingChange.MISSING
    if (!quest.isTrackable()) return DungeonQuestTrackingChange.UNTRACKABLE

    val current = QuestTracking.getPlayerTrackingQuests()[player.uniqueId]
    if (!enabled) {
        if (current?.quest?.questID == questId) current.stop()
        return DungeonQuestTrackingChange.UNTRACKED
    }
    if (current?.quest?.questID == questId) {
        return DungeonQuestTrackingChange.TRACKED
    }
    if (current != null) {
        current.stop()
    }
    QuestTracking.toggleTracking(player, quest)
    return if (QuestTracking.getPlayerTrackingQuests()[player.uniqueId]?.quest?.questID == questId)
        DungeonQuestTrackingChange.TRACKED else DungeonQuestTrackingChange.FAILED
}

internal fun abandonDungeonQuest(player: Player, questId: UUID): DungeonQuestAbandonChange {
    if (!PlayerData.isInMemory(player.uniqueId)) return DungeonQuestAbandonChange.LOADING
    if (PlayerData.getQuest(player.uniqueId, questId) == null) return DungeonQuestAbandonChange.MISSING

    Quest.stopPlayerQuest(player, questId.toString())
    return if (PlayerData.getQuest(player.uniqueId, questId) == null)
        DungeonQuestAbandonChange.ABANDONED else DungeonQuestAbandonChange.FAILED
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
