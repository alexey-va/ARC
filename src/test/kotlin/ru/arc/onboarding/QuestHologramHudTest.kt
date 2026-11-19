package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class QuestHologramHudTest : FreeSpec({
    val farm = OptionalQuestArea("sp11", 109.0, -64.0, 363.0, 298.0, 139.0, 575.0)
    fun questText(key: String) = MiniMessage.miniMessage().deserialize(OptionalQuestDialog.DEFAULT_TEXT.getValue(key))

    "hologram permission and provider state never remove the ordinary progress bar" {
        val player = mockk<Player>(relaxed = true)
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        every { player.isOnline } returns true
        every { player.isDead } returns false
        var allowed = false
        every { player.hasPermission(QuestGuidanceHudService.PERMISSION) } answers { allowed }
        val provider = mockk<QuestGuidanceHudService>(relaxed = true)
        var service: QuestGuidanceHudService? = provider
        var lookups = 0
        val hologram = QuestHologramHud(QuestGuidanceHudService.Source.OPTIONAL) { lookups++; service }
        val shown = mutableListOf<BossBar>()
        every { player.showBossBar(capture(shown)) } returns Unit
        val view = OptionalQuestView(OptionalQuestKind.FARM, true, false, emptySet())
        OptionalQuestHud(::questText, farm, hologram).use { hud ->
            hud.refresh(player, view)
            lookups shouldBe 0
            shown.size shouldBe 1
            allowed = true
            hud.refresh(player, view)
            shown.size shouldBe 1
            every { provider.show(player, QuestGuidanceHudService.Source.OPTIONAL, any()) } returns true
            hud.refresh(player, view)
            verify(exactly = 0) { player.hideBossBar(shown.first()) }
            allowed = false
            hud.refresh(player, view)
            verify(exactly = 2) { provider.clear(playerId, QuestGuidanceHudService.Source.OPTIONAL) }
            shown.size shouldBe 1
            allowed = true
            service = null
            hud.refresh(player, view)
            shown.size shouldBe 1
        }
    }

    "the saved first shift shows only in the farm footprint across movement, teleport and rejoin" {
        val id = UUID.randomUUID()
        val field = mockk<World> { every { name } returns farm.world }
        val spawn = mockk<World> { every { name } returns "rc_origin_spawn" }
        var location = Location(spawn, 200.0, 70.0, 400.0)
        fun viewer() = mockk<Player>(relaxed = true) {
            every { uniqueId } returns id
            every { isOnline } returns true
            every { isDead } returns false
            every { hasPermission(QuestGuidanceHudService.PERMISSION) } returns true
            every { this@mockk.location } answers { location }
        }
        val player = viewer()
        val arrival = OptionalQuestView(OptionalQuestKind.FARM, true, false, emptySet())
        val shift = arrival.copy(steps = setOf(OptionalQuestStep.FARM_VISITED))
        val source = QuestGuidanceHudService.Source.OPTIONAL
        val provider = mockk<QuestGuidanceHudService>(relaxed = true)
        every { provider.show(any(), source, any()) } returns true
        OptionalQuestHud(::questText, farm, QuestHologramHud(source) { provider }).use { hud ->
            hud.refresh(player, arrival) // The initial route to the farm still works from Spawn.
            verify(exactly = 1) { player.showBossBar(any()); provider.show(player, source, any()) }
            location = Location(field, 109.0, 70.0, 400.0)
            hud.refresh(player, shift)
            verify(exactly = 2) { provider.show(player, source, any()) }
            location.x = 108.9
            hud.refresh(player, shift)
            verify(exactly = 1) { player.hideBossBar(any()); provider.clear(id, source) }
            location.x = 109.0
            hud.refresh(player, shift)
            verify(exactly = 2) { player.showBossBar(any()) }
            location.world = spawn
            hud.refresh(player, shift)
            verify(exactly = 2) { player.hideBossBar(any()); provider.clear(id, source) }
            hud.clear(id)
            val rejoined = viewer()
            hud.refresh(rejoined, shift)
            verify(exactly = 0) { rejoined.showBossBar(any()); provider.show(rejoined, source, any()) }
            location.world = field
            hud.refresh(rejoined, shift)
            verify(exactly = 1) { rejoined.showBossBar(any()); provider.show(rejoined, source, any()) }
            hud.refresh(rejoined, shift.copy(completed = true, steps = OptionalQuestKind.FARM.steps))
            verify(exactly = 1) { rejoined.hideBossBar(any()) }
            verify(exactly = 3) { provider.clear(id, source) }
        }
    }

    "replacing or closing a provider clears only this guide's old source" {
        val player = mockk<Player>(relaxed = true)
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        every { player.hasPermission(QuestGuidanceHudService.PERMISSION) } returns true
        val first = mockk<QuestGuidanceHudService>(relaxed = true)
        val second = mockk<QuestGuidanceHudService>(relaxed = true)
        every { first.show(any(), any(), any()) } returns true
        every { second.show(any(), any(), any()) } returns true
        var service = first
        QuestHologramHud(QuestGuidanceHudService.Source.FIRST_HOME) { service }.use { hud ->
            hud.refresh(player, Component.text("Сохраните точку дома")) shouldBe true
            service = second
            hud.refresh(player, Component.text("Защитите участок")) shouldBe true
            verify(exactly = 1) { first.clear(playerId, QuestGuidanceHudService.Source.FIRST_HOME) }
        }
        verify(exactly = 1) { second.clear(playerId, QuestGuidanceHudService.Source.FIRST_HOME) }
    }

    "the opt-in permission is declared false rather than granted to operators or admin children" {
        val descriptor = Files.readString(Path.of("src/main/resources/plugin.yml"))
        val start = descriptor.indexOf("  ${QuestGuidanceHudService.PERMISSION}:\n")
        (start >= 0) shouldBe true
        val section = descriptor.substring(start).lineSequence().drop(1)
            .takeWhile { it.startsWith("    ") || it.isBlank() }.toList()
        section.any { it.trim() == "default: false" } shouldBe true
        descriptor.lineSequence().count { it.trim().startsWith("${QuestGuidanceHudService.PERMISSION}:") } shouldBe 1
    }
})
