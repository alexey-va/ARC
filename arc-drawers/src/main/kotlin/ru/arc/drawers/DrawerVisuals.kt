package ru.arc.drawers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Axis
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.type.Barrel
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneTextStyle

/** Main-thread views of loaded drawers. The plugin owns chunk discovery and the display service. */
internal class DrawerVisuals(
    private val displays: PaperPacketDisplays,
    private val onFailure: (RuntimeException) -> Unit,
) {
    private val faces = mutableMapOf<Block, Face>()
    private var failureReported = false

    fun refresh(block: Block, drawer: Drawer, slot: Int? = null) = visualWork(block) {
        val front = (block.blockData as? Barrel)?.facing
        val current = faces[block]
        if (front == null || front.modY != 0) {
            faces.remove(block)?.scene?.close()
            return@visualWork
        }
        if (current != null && (current.front != front || current.layout != drawer.layout)) {
            faces.remove(block)?.scene?.close()
        }
        val face = faces.getOrPut(block) { Face(block, front, drawer.layout, displays) }
        face.update(drawer, slot)
    }

    fun forget(block: Block) = visualWork(block) { faces.remove(block)?.scene?.close() }

    private inline fun visualWork(block: Block, action: () -> Unit) {
        try {
            action()
        } catch (failure: RuntimeException) {
            try {
                faces.remove(block)?.scene?.close()
            } catch (cleanup: RuntimeException) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            if (!failureReported) {
                failureReported = true
                try {
                    onFailure(failure)
                } catch (_: RuntimeException) {
                    // The diagnostic is also outside the inventory transaction.
                }
            }
        }
    }

    private class Face(block: Block, val front: BlockFace, val layout: DrawerLayout, displays: PaperPacketDisplays) {
        val scene = PacketDisplayScene<String>(displays)
        // Keep packet entities in the barrel's chunk, even when the lid lies on a chunk boundary.
        private val anchor = block.location.add(0.5, 0.0, 0.5).apply { direction = front.direction }
        private val style = SceneDisplayStyle(
            viewRange = 0.25f, displayWidth = 1.25f, displayHeight = 1f,
            brightness = faceBrightness(block, front),
        )
        private val parts = mutableMapOf<String, ScenePart<String>>()
        private var previous: Drawer? = null

        fun update(drawer: Drawer, slot: Int?) {
            val old = previous
            if (old == null) {
                blockPart("plate", Material.CYAN_TERRACOTTA, -0.475f, 0.025f, -0.002f, 0.95f, 0.95f, 0.023f)
                // The four joined rails stand one texture pixel proud of the inset. Grain runs along each rail.
                blockPart("rim-top", Material.STRIPPED_OAK_LOG, -0.484375f, 0.9375f, -0.002f, 0.96875f, 0.046875f, 0.086f, Axis.X)
                blockPart("rim-bottom", Material.STRIPPED_OAK_LOG, -0.484375f, 0.015625f, -0.002f, 0.96875f, 0.046875f, 0.086f, Axis.X)
                blockPart("rim-left", Material.STRIPPED_OAK_LOG, -0.484375f, 0.0625f, -0.002f, 0.046875f, 0.875f, 0.086f)
                blockPart("rim-right", Material.STRIPPED_OAK_LOG, 0.4375f, 0.0625f, -0.002f, 0.046875f, 0.875f, 0.086f)
                if (layout != DrawerLayout.ONE) {
                    blockPart("rail", Material.STRIPPED_SPRUCE_LOG, -0.4375f, 0.484375f, 0.020f, 0.875f, 0.03125f, 0.035f, Axis.X)
                }
                if (layout == DrawerLayout.FOUR) {
                    blockPart("stile", Material.STRIPPED_SPRUCE_LOG, -0.015625f, 0.0625f, 0.020f, 0.03125f, 0.875f, 0.039f)
                }
            }
            var changed = old == null || old.locked != drawer.locked
            if (old?.locked != drawer.locked) {
                if (drawer.locked) {
                    blockPart("clasp", Material.COPPER_BLOCK, -0.035f, 0.909f, 0.078f, 0.070f, 0.060f, 0.018f)
                } else {
                    parts.remove("clasp")
                }
            }
            val indices = if (slot == null || old == null || old.locked != drawer.locked ||
                old.showCounts != drawer.showCounts) drawer.slots.indices else slot..slot
            for (i in indices) {
                val before = old?.slots?.get(i)
                val after = drawer.slots[i]
                val item = after.sample?.item
                // Center the contents in the usable opening between the rim and the middle divider.
                val x = layout.slotCenterX(i) * 0.90625f
                val y = 0.5f + (layout.slotCenterY(i) - 0.5f) * 0.90625f
                val large = layout == DrawerLayout.ONE
                if (before?.sample?.item != item || item != null && old?.showCounts != drawer.showCounts) {
                    if (item == null) {
                        parts.remove("item-$i")
                    } else {
                        val size = if (large) 0.49f else 0.30f
                        val offset = if (!drawer.showCounts) 0f else if (large) 0.09f else 0.055f
                        // Undo the ItemDisplay renderer's native half-turn to show the authored GUI face.
                        val icon = pose(x, y + offset, 0.050f, size, size, 0.028f, Quaternionf(0f, 1f, 0f, 0f))
                        parts["item-$i"] = ScenePart.Item("item-$i", icon, style, item, ItemDisplay.ItemDisplayTransform.GUI)
                    }
                    changed = true
                }
                val captionText = label(after, drawer.showCounts)
                if (before == null || captionText != label(before, old.showCounts)) {
                    if (captionText == null) {
                        parts.remove("label-$i")
                    } else {
                        val size = if (large) 0.50f else 0.37f
                        // TextDisplay's origin is the lower edge of its line.
                        val offset = if (item == null) -0.05f else if (large) -0.27f else -0.18f
                        val caption = pose(x, y + offset, 0.034f, size, size, size)
                        parts["label-$i"] = ScenePart.Text("label-$i", caption, style, Component.text(captionText, INK), LETTERING)
                    }
                    changed = true
                }
            }
            if (changed) scene.apply(SceneFrame(parts.values.toList(), AUDIENCE))
            previous = drawer
        }

        private fun label(slot: DrawerSlot, showCounts: Boolean): String? = when {
            slot.sample != null -> if (showCounts) slot.count.toString() else null
            layout == DrawerLayout.ONE -> "—"
            else -> null // The rails already mark every empty half or quarter.
        }

        private fun blockPart(key: String, material: Material, x: Float, y: Float, out: Float,
                              width: Float, height: Float, depth: Float, grain: Axis? = null) {
            val block = material.createBlockData()
            if (grain != null) (block as Orientable).axis = grain
            parts[key] = ScenePart.Block(key, pose(x, y, out, width, height, depth), style, block)
        }

        private fun pose(x: Float, y: Float, out: Float, width: Float, height: Float, depth: Float,
                         rightRotation: Quaternionf = Quaternionf()) = ScenePose(anchor, Transformation(
            Vector3f(x, y, 0.5f + out), Quaternionf(), Vector3f(width, height, depth), rightRotation,
        ))
    }

    private companion object {
        val AUDIENCE = SceneAudience.Public()
        val INK = TextColor.color(0xf5ead8)
        val LETTERING = SceneTextStyle(background = Color.fromARGB(0, 0, 0, 0), shadowed = true)

        fun faceBrightness(block: Block, front: BlockFace): Display.Brightness {
            val x = block.x + front.modX
            val z = block.z + front.modZ
            val lit = if (block.world.isChunkLoaded(x shr 4, z shr 4)) block.getRelative(front) else block
            return Display.Brightness(maxOf(7, lit.lightFromBlocks.toInt()), lit.lightFromSky.toInt())
        }
    }
}
