package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.announcements.AnnouncementPriority
import com.magmaguy.elitemobs.api.CustomEventStartEvent
import com.magmaguy.elitemobs.config.customevents.CustomEventsConfigFields
import com.magmaguy.elitemobs.events.CustomEvent
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.scheduler.BukkitTask

class EliteMobsEventAnnouncementGuardTest : FreeSpec({
    val guard = EliteMobsEventAnnouncementGuard()

    "handler skips cancelled native event starts and runs at highest priority" {
        val handler = EliteMobsEventAnnouncementGuard::class.java
            .getDeclaredMethod("onCustomEventStart", CustomEventStartEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        handler.priority shouldBe EventPriority.HIGHEST
        handler.ignoreCancelled shouldBe true
    }

    "native priority zero sends empty start and end announcements until normalized" {
        val recipients = List(2) { mockk<Player>(relaxed = true) }
        val world = mockk<World> { every { players } returns recipients }
        val native = announcementEvent("", "")
        val event = CustomEventStartEvent(native)

        // The native start/end callers guard only against null; priority zero still sends locally.
        fun announceMessages() {
            native.startMessage?.let { AnnouncementPriority.announce(it, world, native.announcementPriority) }
            native.endMessage?.let { AnnouncementPriority.announce(it, world, native.announcementPriority) }
        }

        announceMessages()
        recipients.forEach { player -> verify(exactly = 2) { player.sendMessage("") } }
        recipients.forEach { clearMocks(it, answers = false) }

        guard.onCustomEventStart(event)
        announceMessages()

        native.startMessage shouldBe null
        native.endMessage shouldBe null
        native.announcementPriority shouldBe 0
        event.customEvent shouldBeSameInstanceAs native
        event.isCancelled shouldBe false
        recipients.forEach { player -> verify(exactly = 0) { player.sendMessage(any<String>()) } }
    }

    "null whitespace and legacy formatting normalize independently without changing visible text" {
        val start = "§a  Начало события!§r  "
        val end = " §6Событие завершено.§r"
        for (empty in listOf(null, "", " \t\n ", "§a§l§r", " §x§0§0§f§f§a§a\t§r")) {
            val native = announcementEvent(empty, end)
            val event = CustomEventStartEvent(native)
            guard.onCustomEventStart(event)
            native.startMessage shouldBe null
            native.endMessage shouldBeSameInstanceAs end

            native.startMessage = start
            native.endMessage = empty
            guard.onCustomEventStart(event)
            native.startMessage shouldBeSameInstanceAs start
            native.endMessage shouldBe null
            event.isCancelled shouldBe false
        }
    }

    "visible announcements preserve the native event and gameplay state" {
        val start = "§aНачало события!§r"
        val end = "§6Событие завершено.§r"
        val native = announcementEvent(start, end)
        native.announcementPriority = 2
        val location = Location(null, 12.0, 64.0, 34.0)
        native.eventStartLocation = location
        val boss = mockk<CustomBossEntity>()
        native.primaryEliteMobs.add(boss)
        val watchdog = mockk<BukkitTask>()
        native.eventWatchdog = watchdog
        native.currentDay = 7
        val conditions = native.startConditions
        val config = native.customEventsConfigFields
        val startCommands = native.startEventCommands
        val endCommands = native.endEventCommands
        val bossFilenames = native.primaryCustomBossFilenames
        val event = CustomEventStartEvent(native)

        guard.onCustomEventStart(event)

        native.startMessage shouldBeSameInstanceAs start
        native.endMessage shouldBeSameInstanceAs end
        native.announcementPriority shouldBe 2
        native.startConditions shouldBeSameInstanceAs conditions
        native.customEventsConfigFields shouldBeSameInstanceAs config
        native.startEventCommands shouldBeSameInstanceAs startCommands
        native.endEventCommands shouldBeSameInstanceAs endCommands
        native.primaryCustomBossFilenames shouldBeSameInstanceAs bossFilenames
        native.primaryEliteMobs shouldBe listOf(boss)
        native.eventStartLocation shouldBeSameInstanceAs location
        native.eventWatchdog shouldBeSameInstanceAs watchdog
        native.currentDay shouldBe 7
        event.customEvent shouldBeSameInstanceAs native
        event.isCancelled shouldBe false
    }
})

private fun announcementEvent(start: String?, end: String?): CustomEvent {
    val config = CustomEventsConfigFields("announcement-guard.yml", true).apply {
        startMessage = start
        endMessage = end
        eventStartCommands = listOf("say start")
        eventEndCommands = listOf("say end")
        bossFilenames = listOf("event-boss.yml")
    }
    return object : CustomEvent(config) {
        override fun startModifiers() = Unit
        override fun eventWatchdog() = Unit
        override fun endModifiers() = Unit
    }
}
