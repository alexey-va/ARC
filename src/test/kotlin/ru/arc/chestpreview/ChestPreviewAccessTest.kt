package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.entity.Player
import ru.arc.paper.api.InspectionHologramAnchor
import java.util.UUID

class ChestPreviewAccessTest : StringSpec({
    "protection construction does not resolve Lands when its API is absent" {
        val loader = LandsApiBlockingClassLoader(ChestPreviewAccessTest::class.java.classLoader)
        val protectionClass = Class.forName("ru.arc.chestpreview.ChestPreviewProtection", true, loader)
        val constructor = protectionClass.getDeclaredConstructor().apply { isAccessible = true }

        val protection = constructor.newInstance()

        protection.javaClass.classLoader shouldBe loader
    }

    "a permitted ordinary chest yields an anchor without reading inventory" {
        val f = PreviewChestFixture()
        val target = ChestPreviewAccess { _, _ -> true }.resolveBlock(f.player, f.single)
        target!!.halves shouldBe listOf(f.states.getValue(f.single))
        target.anchor shouldBe InspectionHologramAnchor(f.world.uid, 8.5, 65.0, 8.5)
        f.verifyNoContents()
    }

    "either half being denied hides a double chest regardless of aimed half" {
        val f = PreviewChestFixture()
        val (left, right) = f.double()
        for (denied in listOf(left, right)) {
            val access = ChestPreviewAccess { _, block -> block !== denied }
            access.resolveBlock(f.player, left) shouldBe null
            access.resolveBlock(f.player, right) shouldBe null
        }
        f.verifyNoContents()
    }

    "both aimed halves produce the same combined order and midpoint" {
        val f = PreviewChestFixture()
        val (left, right) = f.double()
        val access = ChestPreviewAccess { _, _ -> true }
        val target = access.resolveBlock(f.player, left)!!
        target shouldBe access.resolveBlock(f.player, right)
        target.halves shouldBe listOf(f.states.getValue(right), f.states.getValue(left))
        target.anchor shouldBe InspectionHologramAnchor(f.world.uid, 9.0, 65.0, 8.5)
        f.verifyNoContents()
    }

    "locked obstructed and ungenerated loot chests never call protection or read contents" {
        val f = PreviewChestFixture()
        val state = f.states.getValue(f.single)
        var calls = 0
        val access = ChestPreviewAccess { _, _ -> calls++; true }
        every { state.isLocked } returns true
        access.resolveBlock(f.player, f.single) shouldBe null
        every { state.isLocked } returns false
        every { state.isBlocked } returns true
        access.resolveBlock(f.player, f.single) shouldBe null
        every { state.isBlocked } returns false
        every { state.lootTable } returns mockk()
        access.resolveBlock(f.player, f.single) shouldBe null
        calls shouldBe 0
        f.verifyNoContents()
    }

    "an unavailable partner chunk is never loaded or inspected" {
        val f = PreviewChestFixture()
        val (left, right) = f.double(x = 15)
        every { f.world.isChunkLoaded(1, 0) } returns false
        ChestPreviewAccess { _, _ -> true }.resolveBlock(f.player, left) shouldBe null
        verify(exactly = 0) { f.world.getBlockAt(16, 64, 8) }
        verify(exactly = 0) { right.getState(any<Boolean>()) }
        f.verifyNoContents()
    }

    "unsent chunks and malformed paired metadata fail closed" {
        val f = PreviewChestFixture()
        every { f.player.isChunkSent(any<Long>()) } returns false
        ChestPreviewAccess { _, _ -> true }.resolveBlock(f.player, f.single) shouldBe null
        every { f.player.isChunkSent(any<Long>()) } returns true
        val (left, right) = f.double()
        every { (right.blockData as ChestData).facing } returns BlockFace.SOUTH
        ChestPreviewAccess { _, _ -> true }.resolveBlock(f.player, left) shouldBe null
        f.verifyNoContents()
    }

    "a protection outage or incompatible optional API never authorizes contents" {
        val f = PreviewChestFixture()
        ChestPreviewAccess { _, _ -> error("unavailable") }.resolveBlock(f.player, f.single) shouldBe null
        ChestPreviewAccess { _, _ -> throw NoSuchMethodError("unsupported plugin API") }
            .resolveBlock(f.player, f.single) shouldBe null
        f.verifyNoContents()
    }

    "ray trace is bounded by current interaction reach and skips unavailable corridors" {
        val f = PreviewChestFixture()
        every { f.player.eyeLocation } returns Location(f.world, 15.5, 65.6, 8.5, -90f, 0f)
        every { f.player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)!!.value } returns 2.0
        every { f.world.isChunkLoaded(1, 0) } returns false
        val access = ChestPreviewAccess { _, _ -> true }
        access.resolve(f.player, 4.5) shouldBe null
        verify(exactly = 0) { f.player.rayTraceBlocks(any<Double>(), any<FluidCollisionMode>()) }
        every { f.world.isChunkLoaded(1, 0) } returns true
        every { f.player.rayTraceBlocks(2.0, FluidCollisionMode.NEVER) } returns null
        access.resolve(f.player, 4.5) shouldBe null
        verify(exactly = 1) { f.player.rayTraceBlocks(2.0, FluidCollisionMode.NEVER) }
    }
})

private class PreviewChestFixture {
    val world = mockk<World>()
    val player = mockk<Player>()
    val states = linkedMapOf<Block, Chest>()
    val single: Block

    init {
        every { world.uid } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.isChunkSent(any<Long>()) } returns true
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        single = chest(8, ChestData.Type.SINGLE)
    }

    fun double(x: Int = 8): Pair<Block, Block> =
        chest(x, ChestData.Type.LEFT) to chest(x + 1, ChestData.Type.RIGHT)

    private fun chest(x: Int, type: ChestData.Type): Block {
        val block = mockk<Block>()
        val state = mockk<Chest>()
        val data = mockk<ChestData>()
        every { block.world } returns world
        every { block.x } returns x
        every { block.y } returns 64
        every { block.z } returns 8
        every { block.type } returns Material.CHEST
        every { block.blockData } returns data
        every { data.type } returns type
        every { data.facing } returns BlockFace.NORTH
        every { block.getState(false) } returns state
        every { state.isLocked } returns false
        every { state.isBlocked } returns false
        every { state.lootTable } returns null
        every { world.getBlockAt(x, 64, 8) } returns block
        states[block] = state
        return block
    }

    fun verifyNoContents() {
        states.values.forEach { state ->
            verify(exactly = 0) { state.blockInventory }
            verify(exactly = 0) { state.inventory }
        }
    }
}

/** Child-loads the real chest-preview implementation while hiding the optional provider API. */
private class LandsApiBlockingClassLoader(
    private val source: ClassLoader,
) : ClassLoader(source) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name.startsWith("me.angeschossen.lands.api.")) {
            throw ClassNotFoundException("Lands API intentionally absent from this test loader")
        }

        if (!name.startsWith("ru.arc.chestpreview.")) return super.loadClass(name, resolve)

        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { loaded ->
                if (resolve) resolveClass(loaded)
                return loaded
            }
            val resource = name.replace('.', '/') + ".class"
            val bytes = source.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: throw ClassNotFoundException(name)
            val loaded = defineClass(name, bytes, 0, bytes.size)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
