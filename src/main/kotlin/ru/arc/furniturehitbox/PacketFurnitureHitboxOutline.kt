package ru.arc.furniturehitbox

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

internal data class FurnitureHitboxEdge(
    val x: Double,
    val y: Double,
    val z: Double,
    val width: Double,
    val height: Double,
    val depth: Double,
)

/** Twelve joined rods centered on the exact click-box edges, relative to its minimum corner. */
internal fun furnitureHitboxEdges(box: BoundingBox): List<FurnitureHitboxEdge> {
    val coordinates = listOf(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
    val dimensions = listOf(box.widthX, box.height, box.widthZ)
    require(coordinates.all(Double::isFinite) && dimensions.all { it > 0.0 && it <= 16.0 }) {
        "Native furniture click box must have finite positive dimensions up to 16 blocks"
    }
    val thickness = minOf(0.018, dimensions.min() / 4.0)
    val half = thickness / 2.0
    return buildList(12) {
        // Full X rails own the eight corners. Other rails meet their faces without overlapping.
        for (y in listOf(0.0, box.height)) for (z in listOf(0.0, box.widthZ)) {
            add(FurnitureHitboxEdge(-half, y - half, z - half, box.widthX + thickness, thickness, thickness))
        }
        for (x in listOf(0.0, box.widthX)) for (y in listOf(0.0, box.height)) {
            add(FurnitureHitboxEdge(x - half, y - half, half, thickness, thickness, box.widthZ - thickness))
        }
        for (x in listOf(0.0, box.widthX)) for (z in listOf(0.0, box.widthZ)) {
            add(FurnitureHitboxEdge(x - half, half, z - half, thickness, box.height - thickness, thickness))
        }
    }
}

/** Viewer-only glow remains visible when the real click box is inside opaque furniture. */
internal class PacketFurnitureHitboxOutline(
    private val displays: PaperPacketDisplays,
    private val block: BlockData,
) : FurnitureHitboxOutline {
    constructor(plugin: Plugin) : this(PaperPacketDisplays(plugin), Material.LIGHT_BLUE_CONCRETE.createBlockData())
    private data class Frame(val worldId: UUID, val rootId: UUID, val box: BoundingBox, val lines: List<PacketBlockDisplay>)
    private val frames = mutableMapOf<UUID, Frame>()

    override fun show(player: Player, target: FurnitureHitboxTarget) {
        val box = target.bounds
        val world = target.root.world
        val previous = frames[player.uniqueId]
        if (previous?.worldId == world.uid && previous.rootId == target.root.uniqueId && previous.box == box) return
        val edges = furnitureHitboxEdges(box)
        val origin = Location(world, box.minX, box.minY, box.minZ)
        val lines = previous?.lines?.takeIf { previous.worldId == world.uid } ?: run {
            clear(player.uniqueId)
            val created = mutableListOf<PacketBlockDisplay>()
            try {
                repeat(12) {
                    val display = displays.spawnBlock(origin, block)
                    created += display
                    display.apply {
                        isVisibleByDefault = false
                        isGlowing = true
                        glowColorOverride = Color.AQUA
                        brightness = Display.Brightness(15, 15)
                        viewRange = 0.25f
                        displayWidth = 0f
                        displayHeight = 0f
                        showTo(player)
                    }
                }
                created
            } catch (failure: Exception) {
                created.forEach(PacketBlockDisplay::remove)
                throw failure
            }
        }
        // Register ownership before updating metadata so a failed refresh can clear every handle.
        frames[player.uniqueId] = Frame(world.uid, target.root.uniqueId, box.clone(), lines)
        lines.zip(edges).forEach { (display, edge) ->
            display.teleport(origin)
            display.transformation = Transformation(
                Vector3f(edge.x.toFloat(), edge.y.toFloat(), edge.z.toFloat()),
                Quaternionf(),
                Vector3f(edge.width.toFloat(), edge.height.toFloat(), edge.depth.toFloat()),
                Quaternionf(),
            )
        }
    }

    override fun clear(viewerId: UUID) {
        frames.remove(viewerId)?.lines?.forEach(PacketBlockDisplay::remove)
    }

    override fun close() {
        frames.clear()
        displays.close()
    }
}
