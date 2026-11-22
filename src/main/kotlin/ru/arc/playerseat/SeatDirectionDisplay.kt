package ru.arc.playerseat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
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

internal class SeatDirectionDisplay(plugin: Plugin) : SeatDirections {
    private val displays = PaperPacketDisplays(plugin, "player-seat-directions")
    private val signals = mutableMapOf<UUID, Signal>()

    private class Signal(val carrierId: UUID, val arrow: List<PacketBlockDisplay>, val caption: PacketTextDisplay) {
        fun remove() {
            arrow.forEach(PacketDisplay::remove)
            caption.remove()
        }
    }

    override fun show(carrier: Player, rider: Player, yaw: Float, caption: String) {
        val pose = SeatDirectionPose(yaw, carrier.height.toFloat())
        val text = seatDirectionCaption(caption)
        val riderId = rider.uniqueId
        if (signals[riderId]?.carrierId != carrier.uniqueId) hide(riderId)
        val signal = signals.getOrPut(riderId) { createSignal(carrier, rider, text, pose) }
        signal.arrow.forEachIndexed { index, block -> block.transformation = pose.arrow[index].transform }
        signal.caption.transformation = pose.caption
        signal.caption.text(text)
    }

    private fun createSignal(carrier: Player, rider: Player, text: Component, pose: SeatDirectionPose): Signal {
        // A fixed display applies its entity yaw/pitch before its transform. Keep those axes world-aligned.
        val at = carrier.location.apply {
            yaw = 0f
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
            return Signal(carrier.uniqueId, arrow, caption)
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

internal fun seatDirectionCaption(phrase: String): Component {
    val first = phrase.substringBefore(' ')
    val (lead, accent) = when (first) {
        "Туда" -> 0xFF6999 to 0xFFD4E4
        "Пиздуй" -> 0xFF8274 to 0xFFB4D2
        "Живее" -> 0x95F983 to 0xE4FFD9
        "Газуй" -> 0xDB9CFF to 0xFF9AC8
        else -> 0xFFECEB to 0xFF80CF
    }
    return Component.text(first, TextColor.color(lead)).decorate(TextDecoration.BOLD)
        .append(Component.text(phrase.substring(first.length), TextColor.color(accent)))
}

internal class SeatArrowPart(val material: Material, val transform: Transformation)

/** Offsets from the native passenger origin above the carrier, with +Z forward at Bukkit yaw zero. */
internal class SeatDirectionPose(yaw: Float, private val carrierHeight: Float) {
    private val heading = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble() % 360.0).toFloat())
    private val slope = carrierHeight / 9f
    private val plane = Quaternionf(heading).rotateX(-atan(slope))

    // Quartz spine and two folded faces on a dark rim.
    val arrow = listOf(
        shaft(Material.NETHER_BRICKS, 4.28f, 5.78f, .31f, .16f, 0f),
        shaft(Material.QUARTZ_BLOCK, 4.36f, 6.16f, .15f, .06f, .102f),
        wing(Material.NETHER_BRICKS, -1, 6.46f, 1f, .15f, 0f),
        wing(Material.PINK_WOOL, -1, 6.40f, .87f, .060f, .091f),
        wing(Material.NETHER_BRICKS, 1, 6.46f, 1f, .15f, 0f),
        wing(Material.MAGENTA_CONCRETE, 1, 6.40f, .87f, .060f, .091f),
        centered(Material.QUARTZ_BLOCK, 6f, .141f, Vector3f(.14f / sqrt(2f), .05f, .99f / sqrt(2f)),
            Quaternionf().rotationY((PI / 4).toFloat())),
    )

    // Both players share the carrier's horizontal position. Fixed text faces them from above the arrow.
    val caption = Transformation(
        heading.transform(Vector3f(0f, -.04f * carrierHeight, 5.84f)), Quaternionf(heading).rotateY(PI.toFloat()),
        Vector3f(1.85f), Quaternionf(),
    )

    private fun point(z: Float, lift: Float) =
        heading.transform(Vector3f(0f, -.52f * carrierHeight + (z - 4.30f) * slope + lift, z))

    private fun shaft(material: Material, from: Float, to: Float, width: Float, height: Float, lift: Float): SeatArrowPart {
        val depth = (to - from) * hypot(1f, slope)
        return centered(material, (from + to) / 2f, lift, Vector3f(width, height, depth), Quaternionf())
    }

    private fun centered(material: Material, z: Float, lift: Float, scale: Vector3f, right: Quaternionf): SeatArrowPart {
        val center = right.transform(Vector3f(.5f)).mul(scale)
        val corner = point(z, lift).sub(plane.transform(center))
        return SeatArrowPart(material, Transformation(corner, plane, scale, right))
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
