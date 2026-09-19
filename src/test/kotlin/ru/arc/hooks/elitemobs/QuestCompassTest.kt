package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import com.magmaguy.elitemobs.quests.dialogue.QuestDialogueBossBarManager
import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import org.bukkit.boss.BossBar as NativeBossBar

class QuestCompassTest : FreeSpec({
    beforeTest {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns emptyList()
    }
    afterTest { unmockkStatic(Bukkit::class) }

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

    "EliteMobs explorer without any tracked quest receives a white compass" {
        val player = player()
        every { player.uniqueId } returns UUID.randomUUID()
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        val compass = QuestCompass({ emptyMap() }, isEliteWorld = { true })
        compass.refresh()
        val capture = slot<BossBar>()
        verify(exactly = 1) { player.showBossBar(capture(capture)) }
        capture.captured.color() shouldBe BossBar.Color.WHITE
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(capture.captured.name()).contains('S') shouldBe true
        compass.close()
        verify(exactly = 1) { player.hideBossBar(capture.captured) }
    }

    "ambient compass leaves ordinary worlds alone and cleans up on exit and disconnect" {
        val player = player()
        every { player.uniqueId } returns UUID.randomUUID()
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        var elite = false
        val compass = QuestCompass({ emptyMap() }, isEliteWorld = { elite })
        compass.refresh()
        verify(exactly = 0) { player.showBossBar(any()) }
        elite = true
        compass.refresh()
        elite = false
        compass.refresh()
        verify(exactly = 1) { player.hideBossBar(any()) }
        elite = true
        compass.refresh()
        every { Bukkit.getOnlinePlayers() } returns emptyList()
        compass.refresh()
        verify(exactly = 2) { player.hideBossBar(any()) }
        compass.close()
    }

    "accepting and stopping tracking keeps exactly one compass for an explorer" {
        val player = player()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        val visible = mutableSetOf<BossBar>()
        every { player.showBossBar(any()) } answers { visible += firstArg<BossBar>(); Unit }
        every { player.hideBossBar(any()) } answers { visible -= firstArg<BossBar>(); Unit }
        var tracking = emptyMap<UUID, NativeBossBar>()
        val compass = QuestCompass({ tracking }, { player }, isEliteWorld = { true })
        compass.refresh()
        visible.size shouldBe 1
        tracking = mapOf(id to native(player))
        compass.refresh()
        visible.size shouldBe 1
        tracking = emptyMap()
        compass.refresh()
        visible.size shouldBe 1
        compass.close()
        visible.size shouldBe 0
    }

    "NPC dialogue also suspends the compass without a tracked quest" {
        val player = player()
        every { player.uniqueId } returns UUID.randomUUID()
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        mockkStatic(QuestDialogueBossBarManager::class)
        try {
            every { QuestDialogueBossBarManager.hasActiveSession(player) } returns false
            val compass = QuestCompass({ emptyMap() }, isEliteWorld = { true })
            compass.refresh()
            every { QuestDialogueBossBarManager.hasActiveSession(player) } returns true
            compass.refresh()
            verify(exactly = 1) { player.hideBossBar(any()) }
            every { QuestDialogueBossBarManager.hasActiveSession(player) } returns false
            compass.refresh()
            verify(exactly = 2) { player.showBossBar(any()) }
            compass.close()
        } finally {
            unmockkStatic(QuestDialogueBossBarManager::class)
        }
    }

    "nearby state refreshes once per second and immediately on world change" {
        val player = player()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val firstWorld = mockk<World> { every { uid } returns firstId }
        val secondWorld = mockk<World> { every { uid } returns secondId }
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.location } returns Location(firstWorld, 0.0, 64.0, 0.0)
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        var available = listOf(DungeonCompassPoint(firstId, 0.0, 64.0, 10.0, DungeonCompassPointKind.CHEST))
        val points = mockk<DungeonCompassPoints> { every { nearby(player) } answers { available } }
        val compass = QuestCompass({ emptyMap() }, isEliteWorld = { true }, points = points)
        compass.refresh()
        val capture = slot<BossBar>()
        verify { player.showBossBar(capture(capture)) }
        val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        plain.serialize(capture.captured.name())[37] shouldBe '▣'
        repeat(19) { compass.refresh() }
        verify(exactly = 1) { points.nearby(player) }
        available = emptyList()
        compass.refresh()
        plain.serialize(capture.captured.name()).contains('▣') shouldBe false
        verify(exactly = 2) { points.nearby(player) }
        every { player.location } returns Location(secondWorld, 0.0, 64.0, 0.0)
        compass.refresh()
        verify(exactly = 3) { points.nearby(player) }
        compass.close()
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
