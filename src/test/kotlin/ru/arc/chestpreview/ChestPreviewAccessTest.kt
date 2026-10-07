package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.block.EnderChest
import org.bukkit.block.ShulkerBox
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
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
        target!!.states shouldBe listOf(f.states.getValue(f.single))
        target.anchor shouldBe InspectionHologramAnchor(f.world.uid, 8.5, 65.0, 8.5)
        target.containerBounds shouldBe BoundingBox(8.0, 64.0, 8.0, 9.0, 65.0, 9.0)
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
        target.states shouldBe listOf(f.states.getValue(right), f.states.getValue(left))
        target.anchor shouldBe InspectionHologramAnchor(f.world.uid, 9.0, 65.0, 8.5)
        target.containerBounds shouldBe BoundingBox(8.0, 64.0, 8.0, 10.0, 65.0, 9.0)
        f.verifyNoContents()
    }

    "locked obstructed and ungenerated loot chests never call protection or read contents" {
        val f = PreviewChestFixture()
        val state = f.states.getValue(f.single) as Chest
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

    "barrels trapped chests and each copper chest state are accepted as bounded containers" {
        val f = PreviewChestFixture()
        val barrelState = mockk<Barrel>()
        every { barrelState.isLocked } returns false
        every { barrelState.lootTable } returns null
        val barrel = f.special(Material.BARREL, barrelState)
        ChestPreviewAccess { _, _ -> true }.resolveBlock(f.player, barrel)!!.containerBounds shouldBe
            BoundingBox(8.0, 64.0, 8.0, 9.0, 65.0, 9.0)

        val chestMaterials = listOf(Material.TRAPPED_CHEST) + listOf(
            Material.COPPER_CHEST, Material.EXPOSED_COPPER_CHEST, Material.WEATHERED_COPPER_CHEST,
            Material.OXIDIZED_COPPER_CHEST, Material.WAXED_COPPER_CHEST,
            Material.WAXED_EXPOSED_COPPER_CHEST, Material.WAXED_WEATHERED_COPPER_CHEST,
            Material.WAXED_OXIDIZED_COPPER_CHEST,
        )
        for ((index, material) in chestMaterials.withIndex()) {
            val block = f.chest(10 + index, ChestData.Type.SINGLE, material)
            ChestPreviewAccess { _, _ -> true }.resolveBlock(f.player, block)!!.states shouldBe
                listOf(f.states.getValue(block))
        }
        f.verifyNoContents()
    }

    "shulker checks the facing swept volume and accepts an already-open box" {
        val f = PreviewChestFixture()
        val state = mockk<ShulkerBox>()
        val data = mockk<org.bukkit.block.data.Directional>()
        val sweptBounds = slot<BoundingBox>()
        val shulker = f.special(Material.SHULKER_BOX, state)
        every { state.isLocked } returns false
        every { state.lootTable } returns null
        every { state.isOpen } returns false
        every { state.blockData } returns data
        every { data.facing } returns BlockFace.NORTH
        every { f.player.wouldCollideUsing(capture(sweptBounds)) } returns true

        var protectionCalls = 0
        val access = ChestPreviewAccess { _, _ -> protectionCalls++; true }
        access.resolveBlock(f.player, shulker) shouldBe null
        sweptBounds.captured shouldBe BoundingBox(8.000001, 64.000001, 7.500001, 8.999999, 64.999999, 7.999999)
        protectionCalls shouldBe 0
        every { f.player.wouldCollideUsing(any()) } returns false
        access.resolveBlock(f.player, shulker)!!.states shouldBe listOf(state)
        every { state.isOpen } returns true
        access.resolveBlock(f.player, shulker)!!.states shouldBe listOf(state)
        protectionCalls shouldBe 2
        verify(exactly = 2) { f.player.wouldCollideUsing(any()) }
        f.verifyNoContents()
    }

    "all 17 shulker materials resolve through the container state API" {
        val f = PreviewChestFixture()
        val materials = listOf(
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
        )
        for ((index, material) in materials.withIndex()) {
            val state = mockk<ShulkerBox>()
            every { state.isLocked } returns false
            every { state.lootTable } returns null
            every { state.isOpen } returns true
            val block = f.block(material, x = index)
            every { block.getState(false) } returns state
            f.states[block] = state
            ChestPreviewAccess { _, _ -> true }.resolveBlock(f.player, block)!!.states shouldBe listOf(state)
        }
        f.verifyNoContents()
    }

    "Ender Chest uses its native obstruction guard" {
        val f = PreviewChestFixture()
        val state = mockk<EnderChest>()
        val block = f.special(Material.ENDER_CHEST, state)
        every { state.isBlocked } returns true
        var protectionCalls = 0
        val access = ChestPreviewAccess { _, _ -> protectionCalls++; true }
        access.resolveBlock(f.player, block) shouldBe null
        protectionCalls shouldBe 0
        every { state.isBlocked } returns false
        access.resolveBlock(f.player, block)!!.states shouldBe listOf(state)
        protectionCalls shouldBe 1
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
    val states = linkedMapOf<Block, BlockState>()
    val single: Block

    init {
        every { world.uid } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.isChunkSent(any<Long>()) } returns true
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        single = chest(8, ChestData.Type.SINGLE)
    }

    fun double(x: Int = 8): Pair<Block, Block> =
        chest(x, ChestData.Type.LEFT) to chest(x + 1, ChestData.Type.RIGHT)

    private fun chest(x: Int, type: ChestData.Type): Block {
        return chest(x, type, Material.CHEST)
    }

    fun chest(x: Int, type: ChestData.Type, material: Material): Block {
        val block = block(material, x, 64, 8)
        val state = mockk<Chest>()
        val data = mockk<ChestData>()
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

    fun special(material: Material, state: BlockState): Block = block(material).also {
        every { it.getState(false) } returns state
        states[it] = state
    }

    fun block(material: Material, x: Int = 8, y: Int = 64, z: Int = 8): Block = mockk<Block>().also {
        every { it.world } returns world
        every { it.x } returns x
        every { it.y } returns y
        every { it.z } returns z
        every { it.type } returns material
        every { it.location } answers { Location(world, x.toDouble(), y.toDouble(), z.toDouble()) }
        every { world.getBlockAt(x, y, z) } returns it
    }

    fun verifyNoContents() {
        states.values.filterIsInstance<Chest>().forEach { state ->
            verify(exactly = 0) { state.blockInventory }
            verify(exactly = 0) { state.inventory }
        }
        states.values.filterIsInstance<org.bukkit.block.Container>().filterNot { it is Chest }.forEach { state ->
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
