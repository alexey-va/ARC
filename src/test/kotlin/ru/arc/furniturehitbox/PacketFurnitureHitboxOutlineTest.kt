package ru.arc.furniturehitbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

class PacketFurnitureHitboxOutlineTest : StringSpec({
    "each viewer owns twelve private lines and unchanged frames reuse them" {
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        val block = mockk<BlockData>()
        val lines = mutableListOf<PacketBlockDisplay>()
        every { displays.spawnBlock(any(), block) } answers {
            mockk<PacketBlockDisplay>(relaxed = true).also(lines::add)
        }
        val first = mockk<Player> { every { uniqueId } returns UUID.randomUUID() }
        val second = mockk<Player> { every { uniqueId } returns UUID.randomUUID() }
        val target = outlineTarget()
        val outline = PacketFurnitureHitboxOutline(displays, block)
        outline.show(first, target)
        outline.show(first, target.copy(bounds = target.bounds.clone()))
        lines shouldHaveSize 12
        outline.show(second, target)
        lines shouldHaveSize 24
        lines.take(12).forEach { line ->
            verify(exactly = 1) { line.isVisibleByDefault = false; line.isGlowing = true; line.showTo(first) }
            verify(exactly = 0) { line.showTo(second) }
        }
        val moved = target.copy(bounds = target.bounds.clone().shift(0.0, 0.2, 0.0))
        outline.show(first, moved)
        lines shouldHaveSize 24
        outline.clear(first.uniqueId)
        outline.clear(first.uniqueId)
        lines.take(12).forEach { verify(exactly = 1) { it.remove() } }
        lines.drop(12).forEach { verify(exactly = 0) { it.remove() } }
        outline.close()
        verify(exactly = 1) { displays.close() }
    }

    "a partial frame creation failure removes every created line" {
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        val block = mockk<BlockData>()
        val lines = mutableListOf<PacketBlockDisplay>()
        every { displays.spawnBlock(any(), block) } answers {
            if (lines.size == 4) error("failed after four allocations")
            mockk<PacketBlockDisplay>(relaxed = true).also(lines::add)
        }
        val viewer = mockk<Player> { every { uniqueId } returns UUID.randomUUID() }
        val outline = PacketFurnitureHitboxOutline(displays, block)
        shouldThrow<IllegalStateException> { outline.show(viewer, outlineTarget()) }
        lines shouldHaveSize 4
        lines.forEach { verify(exactly = 1) { it.remove() } }
        outline.close()
    }
})

private fun outlineTarget(): FurnitureHitboxTarget {
    val worldId = UUID.randomUUID()
    val rootId = UUID.randomUUID()
    val world = mockk<World> { every { uid } returns worldId }
    val root = mockk<Entity> {
        every { uniqueId } returns rootId
        every { this@mockk.world } returns world
    }
    return FurnitureHitboxTarget(root, BoundingBox(1.0, 64.0, 3.0, 2.0, 65.0, 4.0))
}
