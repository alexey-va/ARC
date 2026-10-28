package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.QuestRewardEvent
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.CustomQuest
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.dungeons.ArcDungeonsPlugin
import ru.ruscrafting.onboarding.api.OptionalOnboardingProgressService
import java.util.logging.Level

/** Captures native turn-in, and recovers missed acknowledgements from EliteMobs' persisted quest marker. */
internal class PrimisOnboardingListener(private val plugin: ArcDungeonsPlugin) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onReward(event: QuestRewardEvent) {
        val quest = event.quest as? CustomQuest ?: return
        if (quest.configurationFilename == INTRO_QUEST && quest.playerUUID == event.player.uniqueId && quest.questObjectives.isTurnedIn) {
            record(event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) { recoverLater(event.player) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChanged(event: PlayerChangedWorldEvent) { recoverLater(event.player) }

    private fun recoverLater(player: Player, attempt: Int = 0) {
        plugin.taskScope.runLater(20L) {
            if (!player.isOnline || plugin.server.getPlayer(player.uniqueId) !== player) return@runLater
            if (!PlayerData.isDataLoaded(player.uniqueId)) {
                if (attempt < 5) recoverLater(player, attempt + 1)
                return@runLater
            }
            // Native metadata is restored from the quest cooldown record, unlike an OP/wildcard permission.
            if (player.getMetadata(INTRO_MARKER).any { it.owningPlugin?.name == "EliteMobs" && it.asBoolean() }) record(player)
        }
    }

    private fun record(player: Player, attempt: Int = 0) {
        val service = plugin.server.servicesManager.load(OptionalOnboardingProgressService::class.java) ?: return
        service.recordPrimisIntro(player.uniqueId, INTRO_QUEST).whenComplete { _, failure ->
            if (failure != null) {
                if (attempt < 3) plugin.taskScope.runLater(100L * (attempt + 1)) {
                    if (player.isOnline && plugin.server.getPlayer(player.uniqueId) === player) record(player, attempt + 1)
                } else plugin.logger.log(Level.WARNING, "Could not retain Primis introduction player=${player.uniqueId}", failure)
            }
        }
    }

    companion object {
        internal const val INTRO_QUEST = "primis_main_quest_1.yml"
        internal const val INTRO_MARKER = "elitequest.primis_main_quest_1.yml"
    }
}
