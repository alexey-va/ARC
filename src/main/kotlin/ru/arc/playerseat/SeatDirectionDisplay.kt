package ru.arc.playerseat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

internal class SeatDirectionDisplay(private val displays: PaperPacketDisplays) : SeatDirections {
    constructor(plugin: Plugin) : this(PaperPacketDisplays(plugin, "player-seat-directions"))

    private val signals = mutableMapOf<UUID, Signal>()

    private class Signal(
        val carrierId: UUID,
        val arrow: List<PacketBlockDisplay>,
        val caption: PacketTextDisplay,
        var pose: SeatDirectionPose,
        var yaw: Float,
    ) {
        fun render(interpolationTicks: Int) {
            val rotation = seatHeadingRotation(yaw)
            arrow.forEachIndexed { index, display ->
                display.interpolationDuration = interpolationTicks
                display.transformation = pose.arrow[index].transform.turned(rotation)
            }
            caption.interpolationDuration = interpolationTicks
            caption.transformation = pose.caption.turned(rotation)
        }

        fun remove() {
            arrow.forEach(PacketDisplay::remove)
            caption.remove()
        }
    }

    override fun show(carrier: Player, rider: Player, yaw: Float, caption: String) {
        val riderId = rider.uniqueId
        if (signals[riderId]?.carrierId != carrier.uniqueId) hide(riderId)
        val signal = signals[riderId]
        val palette = SeatDirectionPalette.pick(signal?.pose?.palette)
        val text = palette.caption(caption)
        val height = carrier.height.toFloat()
        if (signal == null) {
            signals[riderId] = createSignal(carrier, rider, text, SeatDirectionPose(height, SeatArrowStyle.COMPASS, palette), yaw)
            return
        }
        val old = signal.pose
        val next = SeatDirectionPose(height, old.style.next(), palette)
        signal.arrow.forEachIndexed { index, display ->
            val material = next.arrow[index].material
            if (old.arrow[index].material != material) display.blockData = material.createBlockData()
        }
        signal.pose = next
        signal.caption.text(text)
        signal.render(0)
    }

    override fun turn(carrier: Player, rider: Player, yaw: Float) {
        val signal = signals[rider.uniqueId] ?: return
        if (carrier.uniqueId != signal.carrierId) return
        val next = seatTurnYaw(signal.yaw, yaw)
        val height = carrier.height.toFloat()
        if (next == signal.yaw && height == signal.pose.carrierHeight) return
        signal.yaw = next
        if (height != signal.pose.carrierHeight) signal.pose = SeatDirectionPose(height, signal.pose.style, signal.pose.palette)
        signal.render(2)
    }

    private fun createSignal(carrier: Player, rider: Player, text: Component, pose: SeatDirectionPose, yaw: Float): Signal {
        // A fixed display applies its entity yaw/pitch before its transform. Keep those axes world-aligned.
        val at = carrier.location.apply {
            this.yaw = 0f
            pitch = 0f
        }
        val parts = mutableListOf<PacketDisplay>()
        try {
            val arrow = pose.arrow.map { part ->
                displays.spawnBlock(at, part.material.createBlockData()).also(parts::add)
            }
            val caption = displays.spawnText(at, text).also(parts::add).apply {
                backgroundColor = Color.fromARGB(212, 35, 17, 31)
                isShadowed = true
            }
            for (part in parts) {
                part.isVisibleByDefault = false
                part.showTo(carrier)
                part.showTo(rider)
                part.brightness = Display.Brightness(15, 15)
                part.pauseWhenNotObserved = false
                part.attachTo(carrier)
            }
            return Signal(carrier.uniqueId, arrow, caption, pose, yaw % 720f).also { it.render(0) }
        } catch (failure: Exception) {
            parts.forEach(PacketDisplay::remove)
            throw failure
        }
    }

    override fun hide(riderId: UUID) {
        signals.remove(riderId)?.remove()
    }

    override fun close() {
        signals.clear()
        displays.close()
    }
}

internal enum class SeatDirectionPalette(
    val rim: Material,
    val spine: Material,
    val face: Material,
    val shade: Material,
    val glove: Material,
    private val leadColor: Int,
    private val accentColor: Int,
) {
    ROSE(Material.NETHER_BRICKS, Material.QUARTZ_BLOCK, Material.PINK_WOOL, Material.PINK_CONCRETE,
        Material.PINK_CONCRETE, 0xFF679A, 0xFFD1E0),
    SCARLET(Material.RED_NETHER_BRICKS, Material.WHITE_CONCRETE, Material.REDSTONE_BLOCK, Material.RED_WOOL,
        Material.RED_CONCRETE, 0xFF7A70, 0xFFBCB1),
    ORCHID(Material.CRIMSON_PLANKS, Material.CALCITE, Material.PURPUR_BLOCK, Material.MAGENTA_WOOL,
        Material.MAGENTA_CONCRETE, 0xEB93F0, 0xFFD0F3),
    EMERALD(Material.POLISHED_BLACKSTONE, Material.IRON_BLOCK, Material.EMERALD_BLOCK, Material.DEEPSLATE_EMERALD_ORE,
        Material.LIME_CONCRETE, 0x6FF992, 0xCEFFD8),
    PEARL(Material.POLISHED_BLACKSTONE, Material.PINK_WOOL, Material.CALCITE, Material.IRON_BLOCK,
        Material.WHITE_CONCRETE, 0xFFF5F8, 0xFF92C3);

    fun caption(phrase: String): Component {
        val first = phrase.substringBefore(' ')
        return Component.text(first, TextColor.color(leadColor)).decorate(TextDecoration.BOLD)
            .append(Component.text(phrase.substring(first.length), TextColor.color(accentColor)))
    }

    companion object {
        fun pick(previous: SeatDirectionPalette?, random: Random = Random.Default): SeatDirectionPalette {
            if (previous == null || entries.size == 1) return entries.random(random)
            return entries[(previous.ordinal + random.nextInt(1, entries.size)) % entries.size]
        }
    }
}

internal class SeatArrowPart(val material: Material, val transform: Transformation)

internal enum class SeatArrowStyle {
    COMPASS, CHEVRONS, FLETCHED, POINTING_HAND;

    fun next(): SeatArrowStyle = entries[(ordinal + 1) % entries.size]
}

/** Small increments keep the display's linearly interpolated offset on the turning arc. */
internal fun seatTurnYaw(current: Float, target: Float): Float {
    val delta = Location.normalizeYaw(target - current)
    // A full quaternion period also preserves its sign across the +/-180 degree seam.
    return (current + delta.coerceIn(-30f, 30f)) % 720f
}

internal fun seatHeadingRotation(yaw: Float): Quaternionf = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())

internal fun Transformation.turned(heading: Quaternionf): Transformation = Transformation(
    heading.transform(Vector3f(translation)), Quaternionf(heading).mul(leftRotation), scale, rightRotation,
)

/** Offsets from the native passenger origin above the carrier, with +Z forward at Bukkit yaw zero. */
internal class SeatDirectionPose(val carrierHeight: Float, val style: SeatArrowStyle, val palette: SeatDirectionPalette) {
    private val slope = carrierHeight / 9f
    private val plane = Quaternionf().rotationX(-atan(slope))

    // Seven block handles in every shape let a gesture change the silhouette in place.
    val arrow = when (style) {
        SeatArrowStyle.COMPASS -> compass()
        SeatArrowStyle.CHEVRONS -> chevrons()
        SeatArrowStyle.FLETCHED -> fletched()
        SeatArrowStyle.POINTING_HAND -> pointingHand()
    }

    private fun compass() = listOf(
        shaft(palette.rim, 4.28f, 5.78f, .31f, .16f, 0f),
        shaft(palette.spine, 4.36f, 6.16f, .15f, .06f, .102f),
        wing(palette.rim, -1, 6.46f, 1f, .15f, 0f),
        wing(palette.face, -1, 6.40f, .87f, .060f, .091f),
        wing(palette.rim, 1, 6.46f, 1f, .15f, 0f),
        wing(palette.shade, 1, 6.40f, .87f, .060f, .091f),
        diamond(palette.spine, 6f, .141f, .14f, .05f, .99f),
    )

    private fun chevrons() = listOf(
        wing(palette.rim, -1, 5.28f, .75f, .16f, -.035f),
        wing(palette.rim, 1, 5.28f, .75f, .16f, -.035f),
        wing(palette.spine, -1, 5.22f, .62f, .055f, .066f),
        wing(palette.spine, 1, 5.22f, .62f, .055f, .066f),
        wing(palette.face, -1, 6.65f, 1.02f, .15f, .035f),
        wing(palette.face, 1, 6.65f, 1.02f, .15f, .035f),
        diamond(palette.shade, 6.17f, .116f, .19f, .06f, .80f),
    )

    private fun fletched() = listOf(
        shaft(palette.rim, 4.14f, 6.16f, .15f, .12f, 0f),
        shaft(palette.spine, 4.23f, 6.34f, .075f, .05f, .077f),
        feather(palette.face, -1),
        feather(palette.shade, 1),
        wing(palette.face, -1, 6.50f, .76f, .13f, .025f),
        wing(palette.shade, 1, 6.50f, .76f, .13f, .025f),
        diamond(palette.spine, 6.31f, .105f, .18f, .045f, .48f),
    )

    private fun pointingHand(): List<SeatArrowPart> {
        val degree = (PI / 180).toFloat()
        // Turn the wrist out so the side thumb and three curled knuckles show even when viewed along +Z.
        val palm = Quaternionf(plane).rotateY(38f * degree).rotateZ(-5f * degree)
        val origin = point(5.20f, -.219f * carrierHeight).add(-.51f, 0f, 0f)

        fun piece(material: Material, offset: Vector3f, size: Vector3f, bend: Float = 0f, turn: Float = 0f): SeatArrowPart =
            centered(material, palm.transform(offset).add(origin),
                Quaternionf(palm).rotateY(turn * degree).rotateX(bend * degree), size, Quaternionf())

        // The index keeps the exact heading. Its raised fingertip clears the fist in the lower camera.
        val indexLength = 1.53f
        val indexAxis = Quaternionf().rotationX(-22f * degree).rotateZ(22f * degree)
        val indexBase = palm.transform(Vector3f(.35f, .12f, .20f)).add(origin)
        val indexCenter = indexAxis.transform(Vector3f(0f, 0f, indexLength / 2)).add(indexBase)
        return listOf(
            piece(palette.spine, Vector3f(-.10f, -.01f, -.59f), Vector3f(.64f, .34f, .32f), turn = 12f),
            piece(palette.glove, Vector3f(0f, -.015f, -.12f), Vector3f(1.02f, .32f, .83f)),
            centered(palette.glove, indexCenter, indexAxis, Vector3f(.285f, .238f, indexLength), Quaternionf()),
            piece(palette.glove, Vector3f(.67f, .065f, -.085f), Vector3f(.265f, .253f, .78f), bend = -22f, turn = 53f),
            piece(palette.glove, Vector3f(.08f, .15f, .145f), Vector3f(.242f, .31f, .50f), bend = -42f, turn = 8f),
            piece(palette.glove, Vector3f(-.19f, .14f, .075f), Vector3f(.242f, .31f, .476f), bend = -47f, turn = 4f),
            piece(palette.glove, Vector3f(-.44f, .095f, -.03f), Vector3f(.219f, .31f, .442f), bend = -52f),
        )
    }

    // Both players share the carrier's horizontal position. Fixed text faces them from above the arrow.
    val caption = Transformation(
        Vector3f(0f, -.04f * carrierHeight, 5.84f), Quaternionf().rotateY(PI.toFloat()),
        Vector3f(1.85f), Quaternionf(),
    )

    private fun point(z: Float, lift: Float) =
        Vector3f(0f, -.52f * carrierHeight + (z - 4.30f) * slope + lift, z)

    private fun shaft(material: Material, from: Float, to: Float, width: Float, height: Float, lift: Float): SeatArrowPart {
        val depth = (to - from) * hypot(1f, slope)
        return centered(material, point((from + to) / 2f, lift), plane, Vector3f(width, height, depth), Quaternionf())
    }

    private fun diamond(material: Material, z: Float, lift: Float, width: Float, height: Float, length: Float): SeatArrowPart =
        centered(material, point(z, lift), plane, Vector3f(width / sqrt(2f), height, length / sqrt(2f)),
            Quaternionf().rotationY((PI / 4).toFloat()))

    private fun feather(material: Material, side: Int): SeatArrowPart = centered(
        material, point(4.66f, .06f).add(side * .12f, 0f, 0f),
        Quaternionf(plane).rotateY(-side * Math.toRadians(14.0).toFloat()),
        Vector3f(.21f / sqrt(2f), .11f, 1.10f / sqrt(2f)), Quaternionf().rotationY((PI / 4).toFloat()),
    )

    private fun centered(material: Material, at: Vector3f, left: Quaternionf, scale: Vector3f, right: Quaternionf): SeatArrowPart {
        val center = right.transform(Vector3f(.5f)).mul(scale)
        val corner = at.sub(left.transform(center))
        return SeatArrowPart(material, Transformation(corner, left, scale, right))
    }

    private fun wing(material: Material, side: Int, tipZ: Float, size: Float, height: Float, lift: Float): SeatArrowPart {
        // Turning a block before a nonuniform stretch gives each wing swept, parallel edges.
        val right = Quaternionf().rotationY(side * (PI / 3).toFloat())
        val scale = Vector3f(.50f * size, height, 1.32f * size)
        val seam = right.transform(Vector3f(0f, 0f, 1f)).mul(scale)
        val left = Quaternionf(plane).rotateZ(-side * Math.toRadians(12.0).toFloat()).rotateY(-atan2(seam.x, seam.z))
        // Align the inner edge with the spine. Its front corner is the shared sharp point.
        val innerCorner = Vector3f(if (side < 0) 1f else 0f, .5f, 1f)
        val corner = point(tipZ, lift).sub(left.transform(right.transform(innerCorner).mul(scale)))
        return SeatArrowPart(material, Transformation(corner, left, scale, right))
    }
}
