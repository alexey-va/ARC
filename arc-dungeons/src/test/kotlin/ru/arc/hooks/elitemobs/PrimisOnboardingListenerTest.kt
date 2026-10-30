package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.QuestRewardEvent
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.CustomQuest
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.metadata.MetadataValue
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicesManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.dungeons.ArcDungeonsPlugin
import ru.ruscrafting.onboarding.api.OptionalOnboardingProgressService
import ru.ruscrafting.onboarding.api.PrimisQuestGuidanceService
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PrimisOnboardingListenerTest : FreeSpec({
    "guidance follows the native accepted objective and turn-in states without completing the quest" {
        mockkStatic(PlayerData::class)
        try {
            val id = UUID.randomUUID()
            val player = mockk<Player> {
                every { getMetadata(PrimisOnboardingListener.INTRO_MARKER) } returns emptyList()
            }
            val services = mockk<ServicesManager>(relaxed = true)
            val server = mockk<Server> {
                every { servicesManager } returns services
                every { getPlayer(id) } returns player
                every { isPrimaryThread } returns true
            }
            val plugin = mockk<ArcDungeonsPlugin> { every { this@mockk.server } returns server }
            val quest = mockk<CustomQuest> {
                every { configurationFilename } returns PrimisOnboardingListener.INTRO_QUEST
                every { playerUUID } returns id
                every { questObjectives.isTurnedIn } returns false
                every { questObjectives.isOver } returns false
            }
            every { PlayerData.isDataLoaded(id) } returns false
            every { PlayerData.getQuests(id) } returns emptyList()
            PrimisOnboardingListener(plugin).use { guidance ->
                guidance.stage(id) shouldBe PrimisQuestGuidanceService.Stage.UNAVAILABLE
                every { PlayerData.isDataLoaded(id) } returns true
                guidance.stage(id) shouldBe PrimisQuestGuidanceService.Stage.NOT_ACCEPTED
                every { PlayerData.getQuests(id) } returns listOf(quest)
                guidance.stage(id) shouldBe PrimisQuestGuidanceService.Stage.ACTIVE_OBJECTIVE
                every { quest.questObjectives.isOver } returns true
                guidance.stage(id) shouldBe PrimisQuestGuidanceService.Stage.READY_TO_TURN_IN
                every { quest.questObjectives.isTurnedIn } returns true
                guidance.stage(id) shouldBe PrimisQuestGuidanceService.Stage.COMPLETED
                verify(exactly = 0) { services.load(OptionalOnboardingProgressService::class.java) }
            }
            verify(exactly = 1) { services.unregister(PrimisQuestGuidanceService::class.java, any()) }
        } finally { unmockkStatic(PlayerData::class) }
    }

    "only the player's real turned-in introductory quest reports completion" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player> { every { uniqueId } returns playerId }
        val service = mockk<OptionalOnboardingProgressService> {
            every { recordPrimisIntro(playerId, PrimisOnboardingListener.INTRO_QUEST) } returns CompletableFuture.completedFuture(null)
        }
        val services = mockk<ServicesManager>(relaxed = true) { every { load(OptionalOnboardingProgressService::class.java) } returns service }
        val server = mockk<Server> { every { servicesManager } returns services }
        val plugin = mockk<ArcDungeonsPlugin> { every { this@mockk.server } returns server }
        val listener = PrimisOnboardingListener(plugin)
        fun reward(filename: String, owner: UUID, turnedIn: Boolean): QuestRewardEvent {
            val quest = mockk<CustomQuest> {
                every { configurationFilename } returns filename
                every { playerUUID } returns owner
                every { questObjectives.isTurnedIn } returns turnedIn
            }
            return mockk { every { this@mockk.player } returns player; every { this@mockk.quest } returns quest }
        }
        listener.onReward(reward("another_quest.yml", playerId, true))
        listener.onReward(reward(PrimisOnboardingListener.INTRO_QUEST, UUID.randomUUID(), true))
        listener.onReward(reward(PrimisOnboardingListener.INTRO_QUEST, playerId, false))
        verify(exactly = 0) { service.recordPrimisIntro(any(), any()) }
        listener.onReward(reward(PrimisOnboardingListener.INTRO_QUEST, playerId, true))
        verify(exactly = 1) { service.recordPrimisIntro(playerId, PrimisOnboardingListener.INTRO_QUEST) }
    }

    "recovery requires EliteMobs-owned completion metadata and never trusts OP permissions" {
        mockkStatic(PlayerData::class)
        try {
            val playerId = UUID.randomUUID()
            var markers: List<MetadataValue> = emptyList()
            val player = mockk<Player> {
                every { uniqueId } returns playerId
                every { isOnline } returns true
                every { hasPermission(any<String>()) } returns true
                every { getMetadata(PrimisOnboardingListener.INTRO_MARKER) } answers { markers }
            }
            every { PlayerData.isDataLoaded(playerId) } returns true
            val service = mockk<OptionalOnboardingProgressService> {
                every { recordPrimisIntro(playerId, PrimisOnboardingListener.INTRO_QUEST) } returns CompletableFuture.completedFuture(null)
            }
            val services = mockk<ServicesManager>(relaxed = true) { every { load(OptionalOnboardingProgressService::class.java) } returns service }
            val server = mockk<Server> { every { servicesManager } returns services; every { getPlayer(playerId) } returns player }
            val callbacks = mutableListOf<() -> Unit>()
            val tasks = mockk<LifecycleTaskScope> {
                every { runLater(any<Long>(), any<() -> Unit>()) } answers { callbacks += secondArg<() -> Unit>(); null }
            }
            val plugin = mockk<ArcDungeonsPlugin> {
                every { this@mockk.server } returns server
                every { taskScope } returns tasks
            }
            val event = mockk<PlayerChangedWorldEvent> { every { this@mockk.player } returns player }
            val listener = PrimisOnboardingListener(plugin)
            listener.onWorldChanged(event)
            callbacks.removeAt(0).invoke()
            verify(exactly = 0) { service.recordPrimisIntro(any(), any()) }
            val owner = mockk<Plugin> { every { name } returns "EliteMobs" }
            markers = listOf(mockk { every { owningPlugin } returns owner; every { asBoolean() } returns true })
            listener.onWorldChanged(event)
            callbacks.removeAt(0).invoke()
            verify(exactly = 1) { service.recordPrimisIntro(playerId, PrimisOnboardingListener.INTRO_QUEST) }
            verify(exactly = 0) { player.hasPermission(any<String>()) }
        } finally { unmockkStatic(PlayerData::class) }
    }
})
