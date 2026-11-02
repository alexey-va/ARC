package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class GravesHudTest : FunSpec({
    test("air-anchor scenes stay private and stable and clean up on removal and world change") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("GravesHud")
            val owner = UUID.randomUUID()
            val world = mockk<World>(relaxed = true)
            every { world.uid } returns UUID.randomUUID()
            every { world.name } returns "world"
            every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns owner
            every { player.world } returns world
            every { player.location } returns Location(world, .5, 64.0, 2.0)
            every { player.isOnline } returns true
            val displays = mockk<PaperPacketDisplays>()
            val spawned = mutableListOf<PacketDisplay>()
            every { displays.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also(spawned::add) }
            every { displays.spawnText(any(), any()) } answers { mockk<PacketTextDisplay>(relaxed = true).also(spawned::add) }
            val cleanup = CompletableFuture<Void>()
            every { displays.closeAsync() } returns cleanup
            val hud = GravesHud(plugin, "survival", GravesConfig(ConfigManager.empty()).snapshot(), displays)
            val grave = GraveView(UUID.randomUUID(), owner, "survival", "world", 0, 64, 0, 1, -1, 20, emptyList())

            hud.refreshGlow(player, listOf(grave.copy(ownerId = UUID.randomUUID())))
            spawned.size shouldBe 0
            hud.refreshGlow(player, listOf(grave))
            hud.refreshGlow(player, listOf(grave))
            spawned.size shouldBe 3
            spawned.forEach { part ->
                verify(exactly = 1) { part.isVisibleByDefault = false }
                verify(exactly = 1) { part.showTo(player) }
            }
            verify(exactly = 0) { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }

            hud.refreshGlow(player, emptyList())
            spawned.forEach { part -> verify(exactly = 1) { part.remove() } }
            hud.refreshGlow(player, listOf(grave))
            val latest = spawned.takeLast(3)
            every { world.uid } returns UUID.randomUUID()
            hud.refresh(player, listOf(grave), grave.id, false)
            latest.forEach { part -> verify(exactly = 1) { part.remove() } }

            hud.closeAsync() shouldBe cleanup
            cleanup.isDone shouldBe false
            hud.refreshGlow(player, listOf(grave))
            spawned.size shouldBe 6
            cleanup.complete(null)
        }
    }
})
