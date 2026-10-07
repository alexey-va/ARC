package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

class ChestPreviewIconsTest : StringSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }
    "renders only bounded private item icons and blank background, reusing unchanged handles" {
        val worldId = UUID.randomUUID()
        val player = player(worldId)
        val harness = DisplayHarness()
        val renderer = ChestPreviewIcons(
            harness.owner,
            ChestPreviewSettings(maxItems = 2, backgroundOpacity = 40),
        )
        val frame = frame(
            worldId,
            listOf(
                ItemStack(Material.DIAMOND).apply { amount = 64 },
                ItemStack(Material.EMERALD).apply { amount = 12 },
                ItemStack(Material.GOLD_INGOT),
            ),
        )

        renderer.update(player, frame, 1f)
        renderer.update(player, frame, 1f)

        harness.itemPayloads.map(ItemStack::getType) shouldBe listOf(Material.DIAMOND, Material.EMERALD)
        harness.itemPayloads.map(ItemStack::getAmount) shouldBe listOf(1, 1)
        harness.itemDisplays.size shouldBe 2
        harness.textContents.size shouldBe 1
        PlainTextComponentSerializer.plainText().serialize(harness.textContents.single()).isBlank() shouldBe true
        harness.itemDisplays.forEach { display ->
            verify(exactly = 1) { display.isVisibleByDefault = false }
            verify(exactly = 1) { display.showTo(player) }
            verify { display.billboard = Display.Billboard.CENTER }
            verify { display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GUI }
            verify { display.brightness = Display.Brightness(15, 15) }
        }
        val backdrop = harness.textDisplays.single()
        verify(exactly = 1) { backdrop.isVisibleByDefault = false }
        verify(exactly = 1) { backdrop.showTo(player) }
        verify { backdrop.textOpacity = 0.toByte() }
        verify { backdrop.backgroundColor = Color.fromARGB(102, 15, 23, 30) }
        verify { backdrop.brightness = Display.Brightness(15, 15) }
        verify(exactly = 2) { harness.owner.spawnItem(any<Location>(), any<ItemStack>()) }
        verify(exactly = 1) { harness.owner.spawnText(any<Location>(), any<Component>()) }

        renderer.close()
        verify(exactly = 1) { harness.owner.close() }
    }

    "alpha zero omits the backdrop and empty selection removes the private scene" {
        val worldId = UUID.randomUUID()
        val player = player(worldId)
        val harness = DisplayHarness()
        val renderer = ChestPreviewIcons(
            harness.owner,
            ChestPreviewSettings(backgroundOpacity = 0),
        )

        renderer.update(player, frame(worldId, listOf(ItemStack(Material.PAPER))), 1f)
        harness.textDisplays.size shouldBe 0
        val icon = harness.itemDisplays.single()

        renderer.update(player, frame(worldId, emptyList()), 1f)
        renderer.clear(player)

        verify(exactly = 1) { icon.remove() }
        verify(exactly = 0) { harness.owner.spawnText(any<Location>(), any<Component>()) }
        renderer.close()
    }

    "block icons use a shallow top and side view while flat icons stay unmirrored" {
        val worldId = UUID.randomUUID()
        val harness = DisplayHarness()
        val renderer = ChestPreviewIcons(harness.owner, ChestPreviewSettings(backgroundOpacity = 0))
        renderer.update(player(worldId), frame(worldId, listOf(
            ItemStack(Material.OAK_PLANKS), ItemStack(Material.DIAMOND_PICKAXE),
        )), 1f)

        val rendered = harness.itemTransforms.map { transform ->
            Matrix4f().translation(transform.translation)
                .rotate(transform.leftRotation).scale(transform.scale).rotate(transform.rightRotation)
                // Minecraft applies this after the model's own GUI transform.
                .rotateY(Math.PI.toFloat())
        }
        // Vanilla block/block.json GUI rotation is [30, 225, 0].
        val authored = Quaternionf().rotationXYZ(
            Math.toRadians(30.0).toFloat(), Math.toRadians(225.0).toFloat(), 0f,
        )
        val blockTop = rendered[0].transformDirection(authored.transform(Vector3f(0f, 1f, 0f))).normalize()
        (blockTop.z in 0.40f..0.42f) shouldBe true // sin(24 degrees), previously sin(30) = 0.5.
        val blockFront = rendered[0].transformDirection(authored.transform(Vector3f(0f, 0f, -1f))).normalize()
        (blockFront.z > 0.74f) shouldBe true // A modest reduction from the inventory angle.
        // Generated/handheld GUI models have no extra rotation: their right stays screen-right.
        val flatRight = rendered[1].transformDirection(Vector3f(1f, 0f, 0f))
        (flatRight.distance(Vector3f(ChestPreviewIconGeometry.ICON_SCALE, 0f, 0f)) < 0.00001f) shouldBe true
        renderer.close()
    }

    "flat plants and custom block item models keep their authored GUI pose" {
        ChestPreviewIconGeometry.isBlockIcon(ItemStack(Material.POPPY)) shouldBe false
        val custom = ItemStack(Material.STONE).apply {
            itemMeta = itemMeta.apply { setCustomModelData(123) }
        }
        ChestPreviewIconGeometry.isBlockIcon(custom) shouldBe false
    }

    "changing a block slot to a tool restores its flat pose without replacing the handle" {
        val worldId = UUID.randomUUID()
        val player = player(worldId)
        val harness = DisplayHarness()
        val renderer = ChestPreviewIcons(harness.owner, ChestPreviewSettings(backgroundOpacity = 0))
        renderer.update(player, frame(worldId, listOf(ItemStack(Material.OAK_PLANKS))), 1f)
        renderer.update(player, frame(worldId, listOf(ItemStack(Material.DIAMOND_PICKAXE))), 1f)

        harness.itemDisplays.size shouldBe 1
        harness.itemTransforms.size shouldBe 2
        val flatPose = harness.itemTransforms.last().rightRotation
            .mul(Quaternionf().rotationY(Math.PI.toFloat()), Quaternionf())
        (flatPose.transform(Vector3f(1f, 0f, 0f)).distance(Vector3f(1f, 0f, 0f)) < 0.00001f) shouldBe true
        verify(exactly = 0) { harness.itemDisplays.single().remove() }
        renderer.close()
    }

    "per-viewer scenes have separate item and backdrop audiences" {
        val worldId = UUID.randomUUID()
        val first = player(worldId)
        val second = player(worldId)
        val harness = DisplayHarness()
        val renderer = ChestPreviewIcons(harness.owner, ChestPreviewSettings())

        renderer.update(first, frame(worldId, listOf(ItemStack(Material.PAPER))), 1f)
        renderer.update(second, frame(worldId, listOf(ItemStack(Material.BOOK))), 1f)

        harness.itemPayloads.map(ItemStack::getType) shouldBe listOf(Material.PAPER, Material.BOOK)
        verify(exactly = 1) { harness.itemDisplays[0].showTo(first) }
        verify(exactly = 0) { harness.itemDisplays[0].showTo(second) }
        verify(exactly = 1) { harness.itemDisplays[1].showTo(second) }
        verify(exactly = 0) { harness.itemDisplays[1].showTo(first) }
        verify(exactly = 1) { harness.textDisplays[0].showTo(first) }
        verify(exactly = 0) { harness.textDisplays[0].showTo(second) }
        verify(exactly = 1) { harness.textDisplays[1].showTo(second) }
        verify(exactly = 0) { harness.textDisplays[1].showTo(first) }

        renderer.close()
    }

    "switching containers replaces the scene at its destination instead of moving old icons" {
        val worldId = UUID.randomUUID()
        val harness = DisplayHarness()
        val renderer = ChestPreviewIcons(harness.owner, ChestPreviewSettings(backgroundOpacity = 0))
        val viewer = player(worldId)
        val first = frame(worldId, listOf(ItemStack(Material.PAPER)))
        renderer.update(viewer, first, 1f)
        renderer.update(viewer, first.copy(anchor = first.anchor.copy(x = first.anchor.x + 1)), 1f)
        harness.itemDisplays.size shouldBe 2
        verify(exactly = 1) { harness.itemDisplays[0].remove() }
        verify(exactly = 0) { harness.itemDisplays[0].teleport(any()) }
        renderer.close()
    }

    "bottom-anchored layout and backdrop bounds cover the twelve-icon maximum" {
        val scale = 2f
        val offsets = ChestPreviewIconGeometry.offsets(12, scale)
        val bounds = ChestPreviewIconGeometry.panelBounds(12, scale)
        val iconSize = ChestPreviewIconGeometry.ICON_SCALE * scale

        offsets.size shouldBe 12
        offsets.take(3).map { it.x } shouldBe listOf(-0.48f * scale, 0f, 0.48f * scale)
        offsets.take(3).map { it.y }.distinct().size shouldBe 1
        offsets.takeLast(3).map { it.y }.distinct().size shouldBe 1
        val bottomEdge = offsets.minOf { it.y } - iconSize / 2f
        val topEdge = offsets.maxOf { it.y } + iconSize / 2f
        (bottomEdge > 0f) shouldBe true
        (topEdge <= bounds.height) shouldBe true
        bounds.width shouldBe 3.04f
        bounds.height shouldBe 4f
        ChestPreviewIconGeometry.itemBounds(scale).width shouldBe 1.12f
    }

    "partial final row stays centered and one icon remains above the anchor" {
        val four = ChestPreviewIconGeometry.offsets(4, 1f)
        four.last().x shouldBe 0f
        four.last().y shouldBe 0.28f

        val one = ChestPreviewIconGeometry.offsets(1, 1f).single()
        one.x shouldBe 0f
        one.y shouldBe 0.28f
    }
})

private class DisplayHarness {
    val owner = mockk<PaperPacketDisplays>(relaxed = true)
    val itemPayloads = mutableListOf<ItemStack>()
    val itemDisplays = mutableListOf<PacketItemDisplay>()
    val itemTransforms = mutableListOf<Transformation>()
    val textContents = mutableListOf<Component>()
    val textDisplays = mutableListOf<PacketTextDisplay>()

    init {
        every { owner.spawnItem(any(), any()) } answers {
            itemPayloads += secondArg<ItemStack>().clone()
            mockk<PacketItemDisplay>(relaxed = true).also { display ->
                itemDisplays += display
                every { display.transformation = any() } answers {
                    itemTransforms += firstArg<Transformation>()
                }
            }
        }
        every { owner.spawnText(any(), any()) } answers {
            textContents += secondArg<Component>()
            mockk<PacketTextDisplay>(relaxed = true).also(textDisplays::add)
        }
    }
}

private fun player(worldId: UUID): Player {
    val world = mockk<World>(relaxed = true)
    every { world.uid } returns worldId
    every { world.minHeight } returns -64
    every { world.maxHeight } returns 320
    every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
    every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns mockk {
        every { isPassable } returns true
    }
    every { world.rayTraceBlocks(any(), any(), any(), any(), any<Boolean>()) } returns null
    return mockk<Player>(relaxed = true).also { player ->
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.isChunkSent(any<Long>()) } returns true
        every { player.eyeLocation } returns Location(world, 2.5, 70.8, -6.5)
    }
}

private fun frame(worldId: UUID, items: List<ItemStack>) = ChestPreviewFrame(
    InspectionHologramAnchor(worldId, 2.5, 70.0, -3.5),
    items,
)
