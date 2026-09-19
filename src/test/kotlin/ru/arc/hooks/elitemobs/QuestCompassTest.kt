package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import org.bukkit.boss.BossBar as NativeBossBar

class QuestCompassTest : FreeSpec({
    "pinned EliteMobs dependency exposes the exact supported compass field" {
        val compass = QuestCompass.create()
        (compass != null) shouldBe true
        compass?.close()
    }

    fun native(player: Player, visible: Boolean = true): NativeBossBar = mockk(relaxed = true) {
        every { isVisible } returns visible
        every { title } returns "-".repeat(63)
        every { players } returns listOf(player)
    }
    fun player(): Player = mockk(relaxed = true) {
        every { isOnline } returns true
        every { location } returns Location(null, 0.0, 0.0, 0.0)
    }

    "one white texture-free bar replaces native and follows dialogue suspension" {
        val id = UUID.randomUUID()
        val player = player()
        val native = native(player)
        val capture = slot<BossBar>()
        val compass = QuestCompass({ mapOf(id to native) }, { player }, isEliteWorld = { true })
        compass.refresh()
        compass.refresh()
        verify(exactly = 1) { player.showBossBar(capture(capture)) }
        capture.captured.color() shouldBe BossBar.Color.WHITE
        capture.captured.overlay() shouldBe BossBar.Overlay.PROGRESS
        capture.captured.flags().isEmpty() shouldBe true
        verify { native.isVisible = false }

        every { native.players } returns emptyList()
        compass.refresh()
        verify(exactly = 1) { player.hideBossBar(capture.captured) }
        every { native.players } returns listOf(player)
        compass.refresh()
        verify(exactly = 2) { player.showBossBar(capture.captured) }
        compass.close()
        compass.close()
        verify(exactly = 2) { player.hideBossBar(capture.captured) }
        verify(exactly = 1) { native.isVisible = true }
    }

    "quest switch retires old bar and stopping tracking removes the replacement" {
        val id = UUID.randomUUID()
        val player = player()
        val first = native(player)
        val second = native(player)
        var tracking = mapOf(id to first)
        val compass = QuestCompass({ tracking }, { player }, isEliteWorld = { true })
        compass.refresh()
        tracking = mapOf(id to second)
        compass.refresh()
        verify(exactly = 1) { first.isVisible = true }
        verify(exactly = 1) { player.hideBossBar(any()) }
        tracking = emptyMap()
        compass.refresh()
        verify(exactly = 1) { second.isVisible = true }
        verify(exactly = 2) { player.hideBossBar(any()) }
        compass.close()
    }

    "failure restores native presentation once and stops further work" {
        val id = UUID.randomUUID()
        val player = player()
        val native = native(player)
        var fail = false
        val warnings = mutableListOf<String>()
        val compass = QuestCompass({ if (fail) error("unsupported") else mapOf(id to native) }, { player }, warnings::add, { true })
        compass.refresh()
        fail = true
        repeat(3) { compass.refresh() }
        warnings.size shouldBe 1
        verify(exactly = 1) { native.isVisible = true }
        verify(exactly = 1) { player.hideBossBar(any()) }
    }

    "rotation refreshes the scale even if the native target text did not change" {
        val id = UUID.randomUUID()
        val player = player()
        val native = native(player)
        val compass = QuestCompass({ mapOf(id to native) }, { player }, isEliteWorld = { true })
        val capture = slot<BossBar>()
        compass.refresh()
        verify { player.showBossBar(capture(capture)) }
        val first = capture.captured.name()
        every { player.location } returns Location(null, 0.0, 0.0, 0.0, 90f, 0f)
        compass.refresh()
        (capture.captured.name() != first) shouldBe true
        verify(exactly = 1) { player.showBossBar(any()) }
        compass.close()
    }

    "an originally hidden native bar is never exposed on close" {
        val id = UUID.randomUUID()
        val player = player()
        val native = native(player, visible = false)
        val compass = QuestCompass({ mapOf(id to native) }, { player }, isEliteWorld = { true })
        compass.refresh()
        compass.close()
        verify(exactly = 0) { player.showBossBar(any()) }
        verify(exactly = 0) { native.isVisible = true }
    }
    "leaving EliteMobs worlds hides both bars and returning restores the same tracked quest" {
        val id = UUID.randomUUID()
        val player = player()
        val native = native(player)
        var inEliteWorld = false
        val compass = QuestCompass({ mapOf(id to native) }, { player }, isEliteWorld = { inEliteWorld })
        compass.refresh()
        verify(exactly = 0) { player.showBossBar(any()) }
        verify(exactly = 1) { native.isVisible = false }

        inEliteWorld = true
        compass.refresh()
        val capture = slot<BossBar>()
        verify(exactly = 1) { player.showBossBar(capture(capture)) }
        inEliteWorld = false
        repeat(2) { compass.refresh() }
        verify(exactly = 1) { player.hideBossBar(capture.captured) }
        verify(exactly = 0) { native.isVisible = true }

        inEliteWorld = true
        compass.refresh()
        verify(exactly = 2) { player.showBossBar(capture.captured) }
        compass.close()
    }

    "returning to an EliteMobs world during dialogue does not reveal the compass" {
        val id = UUID.randomUUID()
        val player = player()
        val native = native(player)
        var inEliteWorld = false
        val compass = QuestCompass({ mapOf(id to native) }, { player }, isEliteWorld = { inEliteWorld })
        compass.refresh()
        every { native.players } returns emptyList()
        inEliteWorld = true
        compass.refresh()
        verify(exactly = 0) { player.showBossBar(any()) }
        every { native.players } returns listOf(player)
        compass.refresh()
        verify(exactly = 1) { player.showBossBar(any()) }
        compass.close()
    }
})
