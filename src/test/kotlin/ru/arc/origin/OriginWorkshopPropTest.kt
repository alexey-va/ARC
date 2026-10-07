package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.roundToLong
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime

class OriginWorkshopPropTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "public workpiece is fully posed at one shared group pivot" {
        val harness = PropDisplayHarness()
        val piece = workshopPiece(OriginWorkshopPoint(0.20, 0.10, -0.30))
        val secondPiece = workshopPiece(OriginWorkshopPoint(-0.24, 0.0, 0.16), Material.SPRUCE_PLANKS)
        val prop = OriginWorkshopProp(
            owner = harness.owner,
            center = Location(null, 2.0, 64.0, 3.0, 45f, 20f),
            geometry = listOf(piece, secondPiece),
            onSpawn = { harness.spawned += it },
        )
        val display = harness.blocks[0]
        val secondDisplay = harness.blocks[1]

        harness.spawned shouldBe listOf(display, secondDisplay)
        prop.center.x shouldBe 2.0
        prop.center.yaw shouldBe 0f
        display.entityId shouldBe 1000
        verify { display.isVisibleByDefault = true }
        verify { secondDisplay.isVisibleByDefault = true }
        verify { display.viewRange = 0.5f }
        verify { secondDisplay.viewRange = 0.5f }
        verify(exactly = 0) { display.showTo(any()) }
        verify(exactly = 0) { secondDisplay.showTo(any()) }

        val rotation = Quaternionf().rotationY((Math.PI / 2).toFloat())
        val movedCenter = Location(null, 4.0, 70.0, -8.0)
        prop.move(movedCenter, rotation)
        val transforms = mutableListOf<Transformation>()
        verify(exactly = 2) { display.transformation = capture(transforms) }
        val secondTransforms = mutableListOf<Transformation>()
        verify(exactly = 2) { secondDisplay.transformation = capture(secondTransforms) }
        val localCorner = Vector3f(
            (piece.center.x - piece.size.x / 2f).toFloat(),
            (piece.center.y - piece.size.y / 2f).toFloat(),
            (piece.center.z - piece.size.z / 2f).toFloat(),
        )
        rotation.transform(localCorner)
        transforms.last().translation.distance(localCorner) shouldBeLessThan 1.0e-5f
        transforms.last().leftRotation.transform(Vector3f(1f, 0f, 0f))
            .distance(rotation.transform(Vector3f(1f, 0f, 0f))) shouldBeLessThan 1.0e-5f
        val secondLocalCorner = Vector3f(
            (secondPiece.center.x - secondPiece.size.x / 2f).toFloat(),
            (secondPiece.center.y - secondPiece.size.y / 2f).toFloat(),
            (secondPiece.center.z - secondPiece.size.z / 2f).toFloat(),
        )
        rotation.transform(secondLocalCorner)
        secondTransforms.last().translation.distance(secondLocalCorner) shouldBeLessThan 1.0e-5f
        verify { display.interpolationDuration = 2 }
        verify { display.teleportDuration = 1 }
        val movedLocations = mutableListOf<Location>()
        verify(exactly = 2) { display.teleport(capture(movedLocations)) }
        movedLocations.last().x shouldBe 4.0
        movedLocations.last().y shouldBe 70.0
        movedLocations.last().z shouldBe -8.0
        val secondMovedLocations = mutableListOf<Location>()
        verify(exactly = 2) { secondDisplay.teleport(capture(secondMovedLocations)) }
        secondMovedLocations.last().x shouldBe 4.0
        secondMovedLocations.last().y shouldBe 70.0
        secondMovedLocations.last().z shouldBe -8.0

        val hitbox = prop.hitboxMatrix(prop.pieces.single { it.geometry == piece })!!
        val hitboxCenter = hitbox.transformPosition(Vector3f(0.5f))
        val expectedCenter = Vector3f(4f, 70f, -8f).add(
            rotation.transform(Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat())),
        )
        hitboxCenter.distance(expectedCenter) shouldBeLessThan 1.0e-5f
    }

    "carried cubes and models mount without follower teleports and detach before placement" {
        for (model in listOf(null, ItemStack(Material.OAK_PLANKS))) {
            val harness = PropDisplayHarness()
            val prop = OriginWorkshopProp(harness.owner, Location(null, 0.0, 64.0, 0.0), listOf(workshopPiece()), model)
            val display = prop.itemDisplay ?: prop.pieces.single().display!!
            val carrier = mockk<Player>(relaxed = true)
            var feet = Location(null, 3.0, 64.0, 4.0)
            every { carrier.location } answers { feet.clone() }
            every { carrier.height } returns 1.8
            val pose = OriginWorkshopCarryPose(OriginWorkshopPoint(-0.4, 1.42, -0.1), Quaternionf())

            prop.carry(carrier, pose)
            feet = Location(null, 9.0, 66.0, 7.0)
            prop.carry(carrier, pose)

            verify(exactly = 2) { display.attachTo(carrier) }
            // Only the original stationary spawn pose teleports; walking changes no world anchor.
            verify(exactly = 1) { display.teleport(any()) }
            verify(exactly = 0) { display.remove() }
            prop.center.x shouldBe 8.6
            prop.center.y shouldBe 67.42
            val transforms = mutableListOf<Transformation>()
            verify { display.transformation = capture(transforms) }
            val shapeCorner = if (model == null) Vector3f(-0.06f, -0.04f, -0.05f) else Vector3f()
            transforms.last().translation.distance(shapeCorner.add(-0.4f, -0.38f, -0.1f)) shouldBeLessThan 1.0e-5f

            val placed = Location(null, 2.0, 65.0, 6.0)
            prop.move(placed, Quaternionf())
            verifyOrder { display.attachTo(carrier); display.detach(); display.teleport(placed) }
            verify(exactly = 2) { display.teleport(any()) }
            verify(exactly = 0) { display.remove() }
        }
    }

    "private placement marker is shown only to its viewer after pose setup" {
        val harness = PropDisplayHarness()
        val viewer = mockk<Player>(relaxed = true)
        val prop = OriginWorkshopProp(
            owner = harness.owner,
            center = Location(null, 1.0, 65.0, 2.0),
            geometry = listOf(workshopPiece()),
            privateViewer = viewer,
        )
        val marker = harness.blocks.single()

        verifyOrder {
            marker.transformation = any()
            marker.showTo(viewer)
        }
        verify { marker.isVisibleByDefault = false }
        verify { marker.showTo(viewer) }
        verify(exactly = 0) { marker.showTo(match { it !== viewer }) }
        prop.remove()
    }

    "saw and drill geometry keeps every spatially unchanged cube packet id" {
        val harness = PropDisplayHarness()
        val raw = originWorkshopCoarseBoardPieces(OriginWorkshopBoardModel.RAW)
        val prop = OriginWorkshopProp(harness.owner, Location(null, 0.0, 64.0, 0.0), raw)
        val initialIds = prop.pieces.associate { cuboidKey(it.geometry) to checkNotNull(it.display).entityId }

        val sawn = originWorkshopCoarseBoardPieces(OriginWorkshopBoardModel.CUT)
        prop.update(sawn)
        sawn.forEach { piece ->
            val key = cuboidKey(piece)
            prop.pieces.single { cuboidKey(it.geometry) == key }.display!!.entityId shouldBe initialIds.getValue(key)
        }
        harness.blocks shouldHaveSize raw.size
        val sawnKeys = sawn.mapTo(mutableSetOf(), ::cuboidKey)
        raw.filterNot { cuboidKey(it) in sawnKeys }.forEach { removedGeometry ->
            val removed = propDisplayFor(harness, initialIds.getValue(cuboidKey(removedGeometry)))
            verify(exactly = 1) { removed.remove() }
        }

        val drilled = originWorkshopCoarseBoardPieces(OriginWorkshopBoardModel.DRILLED_1)
        val sawnIds = prop.pieces.associate { cuboidKey(it.geometry) to checkNotNull(it.display).entityId }
        prop.update(drilled)
        drilled.forEach { piece ->
            val key = cuboidKey(piece)
            prop.pieces.single { cuboidKey(it.geometry) == key }.display!!.entityId shouldBe sawnIds.getValue(key)
        }
        harness.blocks shouldHaveSize raw.size
        val drilledKeys = drilled.mapTo(mutableSetOf(), ::cuboidKey)
        sawn.filterNot { cuboidKey(it) in drilledKeys }.forEach { removedGeometry ->
            val removed = propDisplayFor(harness, sawnIds.getValue(cuboidKey(removedGeometry)))
            verify(exactly = 1) { removed.remove() }
        }
        drilled.forEach { piece ->
            val survivor = prop.pieces.single { cuboidKey(it.geometry) == cuboidKey(piece) }.display!!
            verify(exactly = 0) { survivor.remove() }
        }
    }

    "near-equal cubes reserve their handles before changed cubes reuse leftovers" {
        val harness = PropDisplayHarness()
        val oldGeometry = listOf(
            workshopPiece(OriginWorkshopPoint(-0.2, 0.0, 0.0), Material.OAK_PLANKS),
            workshopPiece(OriginWorkshopPoint(0.2, 0.0, 0.0), Material.SPRUCE_PLANKS),
        )
        val prop = OriginWorkshopProp(harness.owner, Location(null, 0.0, 64.0, 0.0), oldGeometry)
        val first = prop.pieces[0].display!!
        val second = prop.pieces[1].display!!
        val updated = listOf(
            workshopPiece(OriginWorkshopPoint(0.3, 0.1, 0.0), Material.SPRUCE_PLANKS),
            workshopPiece(OriginWorkshopPoint(-0.199995, 0.0, 0.0), Material.BIRCH_PLANKS),
        )

        prop.update(updated)

        harness.blocks shouldHaveSize 2
        prop.pieces.map { it.display } shouldBe listOf(second, first)
        val changedData = slot<BlockData>()
        verify(exactly = 1) { first.blockData = capture(changedData) }
        changedData.captured.material shouldBe Material.BIRCH_PLANKS
        verify(exactly = 0) { second.blockData = any() }
    }

    "switching between model and cuboid rendering removes the old representation" {
        val harness = PropDisplayHarness()
        val geometry = listOf(workshopPiece())
        val prop = OriginWorkshopProp(
            harness.owner,
            Location(null, 0.0, 64.0, 0.0),
            geometry,
            ItemStack(Material.OAK_PLANKS),
        )
        val originalItem = harness.items.single()

        prop.update(geometry)
        prop.itemDisplay shouldBe null
        prop.pieces.single().display shouldBe harness.blocks.single()
        verify(exactly = 1) { originalItem.remove() }

        val block = harness.blocks.single()
        prop.update(geometry, ItemStack(Material.BIRCH_PLANKS))
        prop.pieces.single().display shouldBe null
        prop.itemDisplay shouldBe harness.items.last()
        verify(exactly = 1) { block.remove() }

        prop.remove()
        verify(exactly = 1) { harness.items.last().remove() }
    }

    "model updates retain the item packet and cleanup removes current handles once" {
        val harness = PropDisplayHarness()
        val board = workshopPiece()
        val prop = OriginWorkshopProp(
            harness.owner,
            Location(null, 0.0, 64.0, 0.0),
            listOf(board),
            ItemStack(Material.OAK_PLANKS),
        )
        val item = harness.items.single()
        val firstModel = item.itemStack.clone()
        val nextModel = ItemStack(Material.BIRCH_PLANKS)
        prop.update(listOf(board.copy(center = OriginWorkshopPoint(0.15, 0.0, 0.0))), nextModel)

        prop.itemDisplay shouldBe item
        harness.items shouldHaveSize 1
        verify { item.itemStack = match { it.type == Material.BIRCH_PLANKS } }
        verify(exactly = 0) { item.remove() }
        prop.move(Location(null, 3.0, 67.0, -2.0), Quaternionf().rotationY(0.7f))
        val transformations = mutableListOf<Transformation>()
        verify(exactly = 2) { item.transformation = capture(transformations) }
        val itemDirection = transformations.last().leftRotation.transform(Vector3f(1f, 0f, 0f))
        val expectedItemDirection = originWorkshopBoardItemDisplayRotation(Quaternionf().rotationY(0.7f))
            .transform(Vector3f(1f, 0f, 0f))
        itemDirection.distance(expectedItemDirection) shouldBeLessThan 1.0e-5f

        prop.remove()
        prop.remove()
        verify(exactly = 1) { item.remove() }
        firstModel.type shouldBe Material.OAK_PLANKS
    }

    "shrinking block geometry and final removal clean every allocated handle" {
        val harness = PropDisplayHarness()
        val geometry = listOf(
            workshopPiece(OriginWorkshopPoint(-0.1, 0.0, 0.0)),
            workshopPiece(OriginWorkshopPoint(0.1, 0.0, 0.0)),
            workshopPiece(OriginWorkshopPoint(0.3, 0.0, 0.0)),
        )
        val prop = OriginWorkshopProp(harness.owner, Location(null, 0.0, 64.0, 0.0), geometry)
        val displays = harness.blocks.toList()
        prop.update(geometry.take(1))
        verify(exactly = 1) { displays[1].remove() }
        verify(exactly = 1) { displays[2].remove() }

        prop.remove()
        prop.remove()
        displays.forEach { display -> verify(exactly = 1) { display.remove() } }
    }
})

private class PropDisplayHarness {
    private var nextEntityId = 1000
    val blocks = mutableListOf<PacketBlockDisplay>()
    val items = mutableListOf<PacketItemDisplay>()
    val spawned = mutableListOf<PacketDisplay>()
    val owner = mockk<PaperPacketDisplays>(relaxed = true)

    init {
        every { owner.spawnBlock(any(), any()) } answers { block(secondArg<BlockData>()) }
        every { owner.spawnItem(any(), any()) } answers { item(secondArg<ItemStack>()) }
    }

    private fun block(initial: BlockData): PacketBlockDisplay {
        var current = initial
        val display = mockk<PacketBlockDisplay>(relaxed = true)
        every { display.entityId } returns nextEntityId++
        every { display.blockData } answers { current }
        every { display.blockData = any() } answers { current = firstArg<BlockData>() }
        blocks += display
        return display
    }

    private fun item(initial: ItemStack): PacketItemDisplay {
        var current = initial.clone()
        val display = mockk<PacketItemDisplay>(relaxed = true)
        every { display.entityId } returns nextEntityId++
        every { display.itemStack } answers { current }
        every { display.itemStack = any() } answers { current = firstArg<ItemStack>().clone() }
        items += display
        return display
    }
}

private fun propDisplayFor(harness: PropDisplayHarness, id: Int): PacketBlockDisplay =
    harness.blocks.single { it.entityId == id }

private fun cuboidKey(piece: OriginWorkshopWorkpiecePiece): List<Long> = listOf(
    (piece.center.x * 100_000).roundToLong(),
    (piece.center.y * 100_000).roundToLong(),
    (piece.center.z * 100_000).roundToLong(),
    (piece.size.x * 100_000).roundToLong(),
    (piece.size.y * 100_000).roundToLong(),
    (piece.size.z * 100_000).roundToLong(),
)

private fun workshopPiece(
    center: OriginWorkshopPoint = OriginWorkshopPoint(0.0, 0.0, 0.0),
    material: Material = Material.OAK_PLANKS,
) = OriginWorkshopWorkpiecePiece(
    center = center,
    size = OriginWorkshopGamePartSize(0.12f, 0.08f, 0.10f),
    material = material,
)
